package assign2.consumer.v3.service.db;

import assign2.consumer.v3.config.DbConfig;
import assign2.consumer.v3.model.QueueMessage;
import assign2.consumer.v3.service.ConsumerMetrics;
import assign2.consumer.v3.service.rabbitmq.CircuitBreaker;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Drains MessageBatchQueue and batch-inserts into MySQL.
 * <p>
 * Each writer thread loops:
 *   1. drainTo(batch, BATCH_SIZE)
 *   2. If batch is full OR flush interval elapsed → flush()
 * <p>
 * Flush is protected by a CircuitBreaker (5 failures → 30s cooldown).
 * On SQL exception: exponential backoff retry (up to 3 times), then log to DLQ log.
 * INSERT IGNORE handles duplicate message_id idempotently.
 */
public class DbBatchWriter {

  private static final Logger logger = Logger.getLogger(DbBatchWriter.class.getName());

  private static final String INSERT_SQL =
      "INSERT IGNORE INTO messages "
      + "(message_id, room_id, user_id, username, message, message_type, "
      + " client_timestamp, server_timestamp, server_id) "
      + "VALUES (?,?,?,?,?,?,?,?,?)";

  // Circuit breaker: 5 consecutive flush failures → open for 30s
  private final CircuitBreaker circuitBreaker = new CircuitBreaker(5, 30_000);

  private final HikariDataSource dataSource;
  private final ExecutorService executor;
  private volatile boolean running = false;

  public DbBatchWriter() {
    HikariConfig config = new HikariConfig();
    config.setDriverClassName("com.mysql.cj.jdbc.Driver");
    config.setJdbcUrl(DbConfig.jdbcUrl());
    config.setUsername(DbConfig.USER);
    config.setPassword(DbConfig.PASS);
    config.setMaximumPoolSize(DbConfig.POOL_SIZE);
    config.setConnectionTimeout(5_000);
    config.setPoolName("db-batch-pool");
    this.dataSource = new HikariDataSource(config);
    this.executor = Executors.newFixedThreadPool(DbConfig.WRITER_THREADS, r -> {
      Thread t = new Thread(r, "db-writer-" + System.nanoTime());
      t.setDaemon(true);
      return t;
    });
    logger.info("DbBatchWriter initialized: poolSize=" + DbConfig.POOL_SIZE
        + ", writerThreads=" + DbConfig.WRITER_THREADS
        + ", batchSize=" + DbConfig.BATCH_SIZE
        + ", flushIntervalMs=" + DbConfig.FLUSH_INTERVAL_MS);
  }

  public void start() {
    running = true;
    for (int i = 0; i < DbConfig.WRITER_THREADS; i++) {
      executor.submit(this::writerLoop);
    }
    logger.info("DbBatchWriter started.");
  }

  public void shutdown() {
    running = false;
    executor.shutdown();
    try {
      if (!executor.awaitTermination(15, TimeUnit.SECONDS)) {
        executor.shutdownNow();
      }
    } catch (InterruptedException e) {
      executor.shutdownNow();
      Thread.currentThread().interrupt();
    }
    // Final drain
    List<QueueMessage> remaining = new ArrayList<>();
    MessageBatchQueue.getInstance().drainTo(remaining, Integer.MAX_VALUE);
    if (!remaining.isEmpty()) {
      flush(remaining);
    }
    dataSource.close();
    circuitBreaker.summary();
    logger.info("DbBatchWriter shutdown complete.");
  }

  // ── Writer loop ─────────────────────────────────────────────────────────────

  private void writerLoop() {
    List<QueueMessage> batch = new ArrayList<>(DbConfig.BATCH_SIZE);
    long lastFlushMs = System.currentTimeMillis();

    while (running || MessageBatchQueue.getInstance().size() > 0) {
      MessageBatchQueue.getInstance().drainTo(batch, DbConfig.BATCH_SIZE - batch.size());
      ConsumerMetrics.getInstance().setDbQueueDepth(MessageBatchQueue.getInstance().size());

      boolean batchFull    = batch.size() >= DbConfig.BATCH_SIZE;
      boolean intervalElapsed = (System.currentTimeMillis() - lastFlushMs) >= DbConfig.FLUSH_INTERVAL_MS;

      if (batchFull || (intervalElapsed && !batch.isEmpty())) {
        flush(batch);
        batch.clear();
        lastFlushMs = System.currentTimeMillis();
      } else if (!running) {
        break;
      } else {
        try { Thread.sleep(10); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
      }
    }
  }

  // ── Flush ───────────────────────────────────────────────────────────────────

  private void flush(List<QueueMessage> batch) {
    if (batch.isEmpty()) return;

    if (!circuitBreaker.allowRequest()) {
      logger.warning("DbBatchWriter: circuit OPEN, dropping batch of " + batch.size()
          + " — DB may be unavailable.");
      ConsumerMetrics.getInstance().recordDbFailure();
      return;
    }

    int attempt = 0;
    long backoffMs = 100;
    while (attempt < 3) {
      try {
        executeBatch(batch);
        circuitBreaker.recordSuccess();
        ConsumerMetrics.getInstance().recordDbInserts(batch.size());
        return;
      } catch (SQLException e) {
        attempt++;
        logger.warning("DbBatchWriter flush failed (attempt " + attempt + "): " + e.getMessage());
        if (attempt >= 3) {
          circuitBreaker.recordFailure();
          ConsumerMetrics.getInstance().recordDbFailure();
          logToDlq(batch, e);
          return;
        }
        try { Thread.sleep(backoffMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
        backoffMs *= 2;
      }
    }
  }

  private void executeBatch(List<QueueMessage> batch) throws SQLException {
    try (Connection conn = dataSource.getConnection();
         PreparedStatement ps = conn.prepareStatement(INSERT_SQL)) {
      Timestamp serverTs = new Timestamp(System.currentTimeMillis());
      for (QueueMessage msg : batch) {
        ps.setString(1, msg.getMessageId());
        ps.setString(2, msg.getRoomId());
        ps.setString(3, msg.getUserId());
        ps.setString(4, msg.getUsername());
        ps.setString(5, msg.getMessage());
        ps.setString(6, msg.getMessageType());
        ps.setTimestamp(7, parseClientTimestamp(msg.getTimestamp()));
        ps.setTimestamp(8, serverTs);
        ps.setString(9, msg.getServerId() != null ? msg.getServerId() : "unknown");
        ps.addBatch();
      }
      ps.executeBatch();
    }
  }

  private Timestamp parseClientTimestamp(String iso8601) {
    try {
      return Timestamp.from(Instant.parse(iso8601));
    } catch (Exception e) {
      return new Timestamp(System.currentTimeMillis());
    }
  }

  private void logToDlq(List<QueueMessage> batch, Exception cause) {
    logger.severe("DLQ: " + batch.size() + " messages failed after 3 retries. "
        + "First messageId=" + batch.get(0).getMessageId()
        + ", cause=" + cause.getMessage());
  }
}

package assign2.consumer.service;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Singleton metrics tracker for the consumer service.
 * <p>
 * Tracks: - Redis publish success / failure counts - RabbitMQ basicAck / basicNack / discard counts
 * - Throughput (messages processed per second) - Per-message latency (min / avg / max)
 * <p>
 * Logs a summary every REPORT_INTERVAL_SECONDS seconds. Call startReporting() once on startup,
 * stopReporting() on shutdown.
 */
public class ConsumerMetrics {

  private static final Logger logger = Logger.getLogger(ConsumerMetrics.class.getName());
  private static final int REPORT_INTERVAL_SECONDS = 10;

  // ── Singleton ─────────────────────────────────────────────────────────────
  private static final ConsumerMetrics INSTANCE = new ConsumerMetrics();

  public static ConsumerMetrics getInstance() {
    return INSTANCE;
  }

  private ConsumerMetrics() {
  }

  // ── Counters ──────────────────────────────────────────────────────────────
  private final AtomicLong redisPublishSuccess = new AtomicLong();
  private final AtomicLong redisPublishFailure = new AtomicLong();

  private final AtomicLong rabbitmqAck         = new AtomicLong();
  private final AtomicLong rabbitmqNackRequeue  = new AtomicLong();  // requeue=true
  private final AtomicLong rabbitmqNackDiscard  = new AtomicLong();  // requeue=false
  private final AtomicLong duplicateDiscard     = new AtomicLong();  // dedup hit

  // For throughput calculation — snapshot of ack count at last report
  private final AtomicLong lastAckSnapshot  = new AtomicLong();
  private volatile long    lastReportTimeMs = System.currentTimeMillis();

  // ── Latency tracking in nanoseconds (cumulative, all messages) ───────────
  private final AtomicLong latencyTotalNs = new AtomicLong(0);
  private final AtomicLong latencyCount   = new AtomicLong(0);
  private final AtomicLong latencyMinNs   = new AtomicLong(Long.MAX_VALUE);
  private final AtomicLong latencyMaxNs   = new AtomicLong(0);

  // ── Scheduler ─────────────────────────────────────────────────────────────
  private ScheduledExecutorService scheduler;

  /**
   * Starts the periodic reporting thread. Call once on application startup.
   */
  public void startReporting() {
    this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "metrics-reporter");
      t.setDaemon(true);
      return t;
    });
    this.scheduler.scheduleAtFixedRate(this::report, REPORT_INTERVAL_SECONDS,
        REPORT_INTERVAL_SECONDS, TimeUnit.SECONDS);
    logger.info("ConsumerMetrics reporting started (interval=" + REPORT_INTERVAL_SECONDS + "s).");
  }

  /**
   * Stops the reporting thread and prints a final summary. Call on shutdown.
   */
  public void stopReporting() {
    if (this.scheduler != null) {
      this.scheduler.shutdown();
    }
    this.report();  // final report
    logger.info("ConsumerMetrics reporting stopped.");
  }

  // ── Record methods ────────────────────────────────────────────────────────

  public void recordRedisSuccess() {
    this.redisPublishSuccess.incrementAndGet();
  }

  public void recordRedisFailure() {
    this.redisPublishFailure.incrementAndGet();
  }

  public void recordAck() {
    this.rabbitmqAck.incrementAndGet();
  }

  public void recordNackRequeue() {
    this.rabbitmqNackRequeue.incrementAndGet();
  }

  public void recordNackDiscard() {
    this.rabbitmqNackDiscard.incrementAndGet();
  }

  public void recordDuplicateDiscard() {
    this.duplicateDiscard.incrementAndGet();
  }

  /**
   * Records end-to-end latency for a single message (receipt → ack/nack).
   * Updates cumulative total, count, min, and max using CAS loops (no locks).
   */
  public void recordLatency(long latencyNs) {
    this.latencyTotalNs.addAndGet(latencyNs);
    this.latencyCount.incrementAndGet();
    long cur;
    do { cur = this.latencyMinNs.get(); }
    while (latencyNs < cur && !this.latencyMinNs.compareAndSet(cur, latencyNs));
    do { cur = this.latencyMaxNs.get(); }
    while (latencyNs > cur && !this.latencyMaxNs.compareAndSet(cur, latencyNs));
  }

  // ── Reporting ─────────────────────────────────────────────────────────────

  private void report() {
    long now = System.currentTimeMillis();
    long currentAck = this.rabbitmqAck.get();
    long prevAck    = this.lastAckSnapshot.getAndSet(currentAck);
    long elapsedMs  = now - this.lastReportTimeMs;
    this.lastReportTimeMs = now;

    double throughput = elapsedMs > 0 ? (currentAck - prevAck) * 1000.0 / elapsedMs : 0.0;

    long count   = this.latencyCount.get();
    double minMs = count > 0 ? this.latencyMinNs.get() / 1_000_000.0 : 0.0;
    double maxMs = this.latencyMaxNs.get() / 1_000_000.0;
    double avgMs = count > 0 ? this.latencyTotalNs.get() / 1_000_000.0 / count : 0.0;

    logger.info(String.format(
        "=== ConsumerMetrics ===%n"
            + "  Throughput         : %.1f msg/s (last %ds)%n"
            + "  Redis publish      : success=%-8d failure=%d%n"
            + "  RabbitMQ ack       : %-8d%n"
            + "  RabbitMQ nack      : requeue=%-6d discard=%d%n"
            + "  Duplicate discard  : %d%n"
            + "  Latency (all msgs) : min=%.2fms avg=%.2fms max=%.2fms count=%d",
        throughput, REPORT_INTERVAL_SECONDS,
        this.redisPublishSuccess.get(), this.redisPublishFailure.get(),
        this.rabbitmqAck.get(),
        this.rabbitmqNackRequeue.get(), this.rabbitmqNackDiscard.get(),
        this.duplicateDiscard.get(),
        minMs, avgMs, maxMs, count));
  }
}

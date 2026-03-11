package assign2.consumer.service;

import com.sun.management.OperatingSystemMXBean;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
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

  // ── System metrics state (for delta calculations between reports) ─────────
  private volatile long prevNetRxBytes  = -1;
  private volatile long prevNetTxBytes  = -1;
  private volatile long prevDiskReadSectors  = -1;
  private volatile long prevDiskWriteSectors = -1;

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

    String sys = systemSnapshot();
    logger.info(String.format(
        "=== ConsumerMetrics ===%n"
            + "  Throughput         : %.1f msg/s (last %ds)%n"
            + "  Redis publish      : success=%-8d failure=%d%n"
            + "  RabbitMQ ack       : %-8d%n"
            + "  RabbitMQ nack      : requeue=%-6d discard=%d%n"
            + "  Duplicate discard  : %d%n"
            + "  Latency (all msgs) : min=%.2fms avg=%.2fms max=%.2fms count=%d%n"
            + "%s",
        throughput, REPORT_INTERVAL_SECONDS,
        this.redisPublishSuccess.get(), this.redisPublishFailure.get(),
        this.rabbitmqAck.get(),
        this.rabbitmqNackRequeue.get(), this.rabbitmqNackDiscard.get(),
        this.duplicateDiscard.get(),
        minMs, avgMs, maxMs, count, sys));
  }

  // ── System Metrics ────────────────────────────────────────────────────────

  private String systemSnapshot() {
    StringBuilder sb = new StringBuilder();

    // CPU + Memory via JVM MXBeans
    try {
      OperatingSystemMXBean os =
          (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
      double cpuProcess = os.getProcessCpuLoad() * 100.0;
      double cpuSystem  = os.getSystemCpuLoad()  * 100.0;
      MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
      long heapUsedMb  = heap.getUsed()  / (1024 * 1024);
      long heapMaxMb   = heap.getMax()   / (1024 * 1024);
      sb.append(String.format(
          "  CPU (process/system): %.1f%% / %.1f%%%n"
        + "  Heap memory        : %d MB used / %d MB max",
          cpuProcess, cpuSystem, heapUsedMb, heapMaxMb));
    } catch (Exception e) {
      sb.append("  CPU/Memory         : N/A");
    }

    // Network I/O — /proc/net/dev (Linux only)
    try (BufferedReader br = new BufferedReader(new FileReader("/proc/net/dev"))) {
      long rxBytes = 0, txBytes = 0;
      String line;
      br.readLine(); br.readLine(); // skip 2 header lines
      while ((line = br.readLine()) != null) {
        line = line.trim();
        if (line.startsWith("lo:")) continue;
        String[] parts = line.split("\\s+");
        // format: iface: rx_bytes ... tx_bytes ...
        // after split: [0]=iface, [1]=rx_bytes, [9]=tx_bytes
        if (parts.length >= 10) {
          rxBytes += Long.parseLong(parts[1]);
          txBytes += Long.parseLong(parts[9]);
        }
      }
      long rxDelta = prevNetRxBytes < 0 ? 0 : rxBytes - prevNetRxBytes;
      long txDelta = prevNetTxBytes < 0 ? 0 : txBytes - prevNetTxBytes;
      prevNetRxBytes = rxBytes; prevNetTxBytes = txBytes;
      sb.append(String.format("%n  Net I/O (delta)    : RX=%s TX=%s",
          humanBytes(rxDelta), humanBytes(txDelta)));
    } catch (IOException e) {
      sb.append("\n  Net I/O            : N/A");
    }

    // Disk I/O — /proc/diskstats (Linux only)
    try (BufferedReader br = new BufferedReader(new FileReader("/proc/diskstats"))) {
      long readSectors = 0, writeSectors = 0;
      String line;
      while ((line = br.readLine()) != null) {
        String[] parts = line.trim().split("\\s+");
        // skip loop/ram devices; real disks: sd*, nvme*, xvd*
        if (parts.length >= 13 && parts[2].matches("(sd|nvme|xvd).*")) {
          readSectors  += Long.parseLong(parts[5]);
          writeSectors += Long.parseLong(parts[9]);
        }
      }
      long rdDelta = prevDiskReadSectors  < 0 ? 0 : (readSectors  - prevDiskReadSectors)  * 512;
      long wrDelta = prevDiskWriteSectors < 0 ? 0 : (writeSectors - prevDiskWriteSectors) * 512;
      prevDiskReadSectors = readSectors; prevDiskWriteSectors = writeSectors;
      sb.append(String.format("%n  Disk I/O (delta)   : read=%s write=%s",
          humanBytes(rdDelta), humanBytes(wrDelta)));
    } catch (IOException e) {
      sb.append("\n  Disk I/O           : N/A");
    }

    return sb.toString();
  }

  private static String humanBytes(long bytes) {
    if (bytes < 1024)             return bytes + " B";
    if (bytes < 1024 * 1024)     return String.format("%.1f KB", bytes / 1024.0);
    return String.format("%.1f MB", bytes / (1024.0 * 1024));
  }
}

package assign2.consumer.v3.service;

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
 * Singleton metrics tracker for consumer-v3.
 * Extends consumer metrics with DB write tracking (batch inserts, failures, queue depth).
 */
public class ConsumerMetrics {

  private static final Logger logger = Logger.getLogger(ConsumerMetrics.class.getName());
  private static final int REPORT_INTERVAL_SECONDS = 10;

  private static final ConsumerMetrics INSTANCE = new ConsumerMetrics();
  public static ConsumerMetrics getInstance() { return INSTANCE; }
  private ConsumerMetrics() {}

  // ── Existing counters ──────────────────────────────────────────────────────
  private final AtomicLong redisPublishSuccess = new AtomicLong();
  private final AtomicLong redisPublishFailure = new AtomicLong();
  private final AtomicLong rabbitmqAck         = new AtomicLong();
  private final AtomicLong rabbitmqNackRequeue  = new AtomicLong();
  private final AtomicLong rabbitmqNackDiscard  = new AtomicLong();
  private final AtomicLong duplicateDiscard     = new AtomicLong();
  private final AtomicLong lastAckSnapshot      = new AtomicLong();
  private volatile long    lastReportTimeMs     = System.currentTimeMillis();
  private final AtomicLong latencyTotalNs = new AtomicLong(0);
  private final AtomicLong latencyCount   = new AtomicLong(0);
  private final AtomicLong latencyMinNs   = new AtomicLong(Long.MAX_VALUE);
  private final AtomicLong latencyMaxNs   = new AtomicLong(0);

  // ── DB counters ────────────────────────────────────────────────────────────
  private final AtomicLong dbBatchInserts  = new AtomicLong();  // rows successfully inserted
  private final AtomicLong dbBatchFailures = new AtomicLong();  // flush failures (after retries)
  private final AtomicLong dbQueueDepth    = new AtomicLong();  // latest snapshot

  // ── System metrics state ───────────────────────────────────────────────────
  private volatile long prevNetRxBytes       = -1;
  private volatile long prevNetTxBytes       = -1;
  private volatile long prevDiskReadSectors  = -1;
  private volatile long prevDiskWriteSectors = -1;

  private ScheduledExecutorService scheduler;

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

  public void stopReporting() {
    if (this.scheduler != null) this.scheduler.shutdown();
    this.report();
    logger.info("ConsumerMetrics reporting stopped.");
  }

  // ── Record methods ─────────────────────────────────────────────────────────
  public void recordRedisSuccess()    { redisPublishSuccess.incrementAndGet(); }
  public void recordRedisFailure()    { redisPublishFailure.incrementAndGet(); }
  public void recordAck()             { rabbitmqAck.incrementAndGet(); }
  public void recordNackRequeue()     { rabbitmqNackRequeue.incrementAndGet(); }
  public void recordNackDiscard()     { rabbitmqNackDiscard.incrementAndGet(); }
  public void recordDuplicateDiscard(){ duplicateDiscard.incrementAndGet(); }
  public void recordDbInserts(int n)  { dbBatchInserts.addAndGet(n); }
  public void recordDbFailure()       { dbBatchFailures.incrementAndGet(); }
  public void setDbQueueDepth(long d) { dbQueueDepth.set(d); }

  public void recordLatency(long latencyNs) {
    latencyTotalNs.addAndGet(latencyNs);
    latencyCount.incrementAndGet();
    long cur;
    do { cur = latencyMinNs.get(); } while (latencyNs < cur && !latencyMinNs.compareAndSet(cur, latencyNs));
    do { cur = latencyMaxNs.get(); } while (latencyNs > cur && !latencyMaxNs.compareAndSet(cur, latencyNs));
  }

  private void report() {
    long now = System.currentTimeMillis();
    long currentAck = rabbitmqAck.get();
    long prevAck    = lastAckSnapshot.getAndSet(currentAck);
    long elapsedMs  = now - lastReportTimeMs;
    lastReportTimeMs = now;

    double throughput = elapsedMs > 0 ? (currentAck - prevAck) * 1000.0 / elapsedMs : 0.0;
    long count   = latencyCount.get();
    double minMs = count > 0 ? latencyMinNs.get() / 1_000_000.0 : 0.0;
    double maxMs = latencyMaxNs.get() / 1_000_000.0;
    double avgMs = count > 0 ? latencyTotalNs.get() / 1_000_000.0 / count : 0.0;

    logger.info(String.format(
        "=== ConsumerMetrics ===%n"
            + "  Throughput         : %.1f msg/s (last %ds)%n"
            + "  Redis publish      : success=%-8d failure=%d%n"
            + "  RabbitMQ ack       : %-8d%n"
            + "  RabbitMQ nack      : requeue=%-6d discard=%d%n"
            + "  Duplicate discard  : %d%n"
            + "  Latency (all msgs) : min=%.2fms avg=%.2fms max=%.2fms count=%d%n"
            + "  DB inserts         : %-8d failures=%d queueDepth=%d%n"
            + "%s",
        throughput, REPORT_INTERVAL_SECONDS,
        redisPublishSuccess.get(), redisPublishFailure.get(),
        rabbitmqAck.get(),
        rabbitmqNackRequeue.get(), rabbitmqNackDiscard.get(),
        duplicateDiscard.get(),
        minMs, avgMs, maxMs, count,
        dbBatchInserts.get(), dbBatchFailures.get(), dbQueueDepth.get(),
        systemSnapshot()));
  }

  private String systemSnapshot() {
    StringBuilder sb = new StringBuilder();
    try {
      OperatingSystemMXBean os = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
      double cpuProcess = os.getProcessCpuLoad() * 100.0;
      double cpuSystem  = os.getSystemCpuLoad()  * 100.0;
      MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
      long heapUsedMb = heap.getUsed() / (1024 * 1024);
      long heapMaxMb  = heap.getMax()  / (1024 * 1024);
      sb.append(String.format("  CPU (process/system): %.1f%% / %.1f%%%n  Heap memory        : %d MB used / %d MB max",
          cpuProcess, cpuSystem, heapUsedMb, heapMaxMb));
    } catch (Exception e) {
      sb.append("  CPU/Memory         : N/A");
    }
    try (BufferedReader br = new BufferedReader(new FileReader("/proc/net/dev"))) {
      long rxBytes = 0, txBytes = 0;
      String line; br.readLine(); br.readLine();
      while ((line = br.readLine()) != null) {
        line = line.trim();
        if (line.startsWith("lo:")) continue;
        String[] parts = line.split("\\s+");
        if (parts.length >= 10) { rxBytes += Long.parseLong(parts[1]); txBytes += Long.parseLong(parts[9]); }
      }
      long rxDelta = prevNetRxBytes < 0 ? 0 : rxBytes - prevNetRxBytes;
      long txDelta = prevNetTxBytes < 0 ? 0 : txBytes - prevNetTxBytes;
      prevNetRxBytes = rxBytes; prevNetTxBytes = txBytes;
      sb.append(String.format("%n  Net I/O (delta)    : RX=%s TX=%s", humanBytes(rxDelta), humanBytes(txDelta)));
    } catch (IOException e) { sb.append("\n  Net I/O            : N/A"); }
    try (BufferedReader br = new BufferedReader(new FileReader("/proc/diskstats"))) {
      long readSectors = 0, writeSectors = 0; String line;
      while ((line = br.readLine()) != null) {
        String[] parts = line.trim().split("\\s+");
        if (parts.length >= 13 && parts[2].matches("(sd|nvme|xvd).*")) {
          readSectors += Long.parseLong(parts[5]); writeSectors += Long.parseLong(parts[9]);
        }
      }
      long rdDelta = prevDiskReadSectors  < 0 ? 0 : (readSectors  - prevDiskReadSectors)  * 512;
      long wrDelta = prevDiskWriteSectors < 0 ? 0 : (writeSectors - prevDiskWriteSectors) * 512;
      prevDiskReadSectors = readSectors; prevDiskWriteSectors = writeSectors;
      sb.append(String.format("%n  Disk I/O (delta)   : read=%s write=%s", humanBytes(rdDelta), humanBytes(wrDelta)));
    } catch (IOException e) { sb.append("\n  Disk I/O           : N/A"); }
    return sb.toString();
  }

  private static String humanBytes(long bytes) {
    if (bytes < 1024)         return bytes + " B";
    if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
    return String.format("%.1f MB", bytes / (1024.0 * 1024));
  }
}
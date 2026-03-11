package assign2.server.v2.service;

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
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Singleton metrics reporter for server-v2.
 * Logs RabbitMQ publish rate (producer rate) and system metrics every 10s.
 *
 * Lifecycle: AppLifecycle calls init() then startReporting() on context init,
 * and stopReporting() on context destroy.
 */
public class ServerMetrics {

  private static final Logger logger = Logger.getLogger(ServerMetrics.class.getName());
  private static final int REPORT_INTERVAL_SECONDS = 10;

  private static final ServerMetrics INSTANCE = new ServerMetrics();

  public static ServerMetrics getInstance() { return INSTANCE; }

  private ServerMetrics() {}

  private volatile Supplier<MessagePublisher> publisherSupplier;
  private final AtomicLong lastPublishSnapshot = new AtomicLong();
  private volatile long lastReportTimeMs = System.currentTimeMillis();

  // System metrics state for delta calculations
  private volatile long prevNetRxBytes       = -1;
  private volatile long prevNetTxBytes       = -1;
  private volatile long prevDiskReadSectors  = -1;
  private volatile long prevDiskWriteSectors = -1;

  private ScheduledExecutorService scheduler;

  public void init(Supplier<MessagePublisher> publisherSupplier) {
    this.publisherSupplier = publisherSupplier;
  }

  public void startReporting() {
    this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "server-metrics-reporter");
      t.setDaemon(true);
      return t;
    });
    this.scheduler.scheduleAtFixedRate(this::report, REPORT_INTERVAL_SECONDS,
        REPORT_INTERVAL_SECONDS, TimeUnit.SECONDS);
    logger.info("ServerMetrics reporting started (interval=" + REPORT_INTERVAL_SECONDS + "s).");
  }

  public void stopReporting() {
    if (this.scheduler != null) {
      this.scheduler.shutdown();
    }
    this.report();
    logger.info("ServerMetrics reporting stopped.");
  }

  private void report() {
    long now = System.currentTimeMillis();
    long elapsedMs = now - this.lastReportTimeMs;
    this.lastReportTimeMs = now;

    String publishRateLine;
    MessagePublisher pub = this.publisherSupplier != null ? this.publisherSupplier.get() : null;
    if (pub != null) {
      long current = pub.getPublishCount();
      long prev    = this.lastPublishSnapshot.getAndSet(current);
      double rate  = elapsedMs > 0 ? (current - prev) * 1000.0 / elapsedMs : 0.0;
      publishRateLine = String.format("  Publish rate       : %.1f msg/s (last %ds)%n"
          + "  Publish total      : %d", rate, REPORT_INTERVAL_SECONDS, current);
      pub.logMetrics();
    } else {
      publishRateLine = "  Publish rate       : (no connections yet)";
    }

    String sys = systemSnapshot();
    logger.info(String.format("=== ServerMetrics ===%n%s%n%s", publishRateLine, sys));
  }

  // ── System Metrics ────────────────────────────────────────────────────────

  private String systemSnapshot() {
    StringBuilder sb = new StringBuilder();

    try {
      OperatingSystemMXBean os =
          (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
      double cpuProcess = os.getProcessCpuLoad() * 100.0;
      double cpuSystem  = os.getSystemCpuLoad()  * 100.0;
      MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
      long heapUsedMb  = heap.getUsed() / (1024 * 1024);
      long heapMaxMb   = heap.getMax()  / (1024 * 1024);
      sb.append(String.format(
          "  CPU (process/system): %.1f%% / %.1f%%%n"
        + "  Heap memory        : %d MB used / %d MB max",
          cpuProcess, cpuSystem, heapUsedMb, heapMaxMb));
    } catch (Exception e) {
      sb.append("  CPU/Memory         : N/A");
    }

    try (BufferedReader br = new BufferedReader(new FileReader("/proc/net/dev"))) {
      long rxBytes = 0, txBytes = 0;
      String line;
      br.readLine(); br.readLine();
      while ((line = br.readLine()) != null) {
        line = line.trim();
        if (line.startsWith("lo:")) continue;
        String[] parts = line.split("\\s+");
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

    try (BufferedReader br = new BufferedReader(new FileReader("/proc/diskstats"))) {
      long readSectors = 0, writeSectors = 0;
      String line;
      while ((line = br.readLine()) != null) {
        String[] parts = line.trim().split("\\s+");
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
    if (bytes < 1024)           return bytes + " B";
    if (bytes < 1024 * 1024)   return String.format("%.1f KB", bytes / 1024.0);
    return String.format("%.1f MB", bytes / (1024.0 * 1024));
  }
}

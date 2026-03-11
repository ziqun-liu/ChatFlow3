package assign2.client.metrics;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public class Metrics {

  private final LongAdder successCount = new LongAdder();
  private final LongAdder failureCount = new LongAdder();
  private final LongAdder sendAttempts = new LongAdder();
  private final LongAdder connectionCount = new LongAdder();
  private final LongAdder connectionFailureCount = new LongAdder();
  private final LongAdder reconnectionCount = new LongAdder();
  private final LongAdder retrySuccessCount = new LongAdder();
  private final LongAdder retryCount = new LongAdder();

  private final AtomicLong peakQueueDepth = new AtomicLong(0);
  private final AtomicLong totalQueueDepthSamples = new AtomicLong(0);
  private final AtomicLong queueDepthSampleCount = new AtomicLong(0);
  private final ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();
  private final ConcurrentHashMap<Integer, AtomicLong> roomSuccess = new ConcurrentHashMap<>();
  private final ConcurrentLinkedQueue<String> csvLines = new ConcurrentLinkedQueue<>();
  private final ConcurrentHashMap<Long, AtomicLong> throughputBuckets = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, AtomicLong> messageTypeCounts = new ConcurrentHashMap<>();


  private volatile long startNs = 0L;
  private volatile long endNs = 0L;
  private volatile long startWallMs = 0L;

  public void start() {
    this.startNs = System.nanoTime();
    this.startWallMs = System.currentTimeMillis();
    this.endNs = 0L;
  }

  public void stop() {
    this.endNs = System.nanoTime();
  }

  public void recordSuccess() {
    this.successCount.increment();
  }

  public void recordSuccess(int statusCode) {
    this.successCount.increment();
  }

  public void recordFailure() {
    this.failureCount.increment();
  }

  public void recordSendAttempt() {
    this.sendAttempts.increment();
  }

  public void recordSendAttempts(long n) {
    this.sendAttempts.add(n);
  }

  public void recordConnection() {
    this.connectionCount.increment();
  }

  public void recordConnectionFailure() {
    this.connectionFailureCount.increment();
  }

  public void recordReconnection() {
    this.reconnectionCount.increment();
  }

  public void recordRetrySuccess() {
    this.retrySuccessCount.increment();
  }

  public void recordRetry() {
    this.retryCount.increment();
  }

  public void recordQueueDepth(int total) {
    long cur;
    do {
      cur = this.peakQueueDepth.get();
    } while (total > cur && !this.peakQueueDepth.compareAndSet(cur, total));
    this.totalQueueDepthSamples.addAndGet(total);
    this.queueDepthSampleCount.incrementAndGet();
  }

  public long getPeakQueueDepth() {
    return this.peakQueueDepth.get();
  }

  public double getAvgQueueDepth() {
    long count = this.queueDepthSampleCount.get();
    return count > 0 ? (double) this.totalQueueDepthSamples.get() / count : 0.0;
  }

  public long getSuccessCount() {
    return this.successCount.sum();
  }

  public long getFailureCount() {
    return this.failureCount.sum();
  }

  public long getSendAttempts() {
    return this.sendAttempts.sum();
  }

  public long getConnectionCount() {
    return this.connectionCount.sum();
  }

  public long getConnectionFailureCount() {
    return this.connectionFailureCount.sum();
  }

  public long getReconnectionCount() {
    return this.reconnectionCount.sum();
  }

  public long getRetryCount() {
    return this.retryCount.sum();
  }

  public long getTotalProcessed() {
    return this.successCount.sum() + this.failureCount.sum();
  }

  public long getRetrySuccessCount() {
    return this.retrySuccessCount.sum();
  }

  public void recordLatency(long latencyMs) {
    latencies.add(latencyMs);
  }

  public void recordRoomSuccess(int roomId) {
    roomSuccess.computeIfAbsent(roomId, k -> new AtomicLong(0)).incrementAndGet();
  }

  // Replaces recordSuccess(statusCode) + recordLatency() + recordRoomSuccess() for main phase.
  // Also records CSV row, throughput bucket, and message type distribution.
  public void recordMessageMetrics(long sendTimestampMs, String messageType, long latencyMs,
      int statusCode, int roomId) {
    successCount.increment();
    latencies.add(latencyMs);
    roomSuccess.computeIfAbsent(roomId, k -> new AtomicLong(0)).incrementAndGet();
    messageTypeCounts.computeIfAbsent(messageType, k -> new AtomicLong(0)).incrementAndGet();
    long bucketKey = (sendTimestampMs - startWallMs) / 10_000;
    if (bucketKey >= 0) {
      throughputBuckets.computeIfAbsent(bucketKey, k -> new AtomicLong(0)).incrementAndGet();
    }
    csvLines.add(
        sendTimestampMs + "," + messageType + "," + latencyMs + "," + statusCode + "," + roomId);
  }

  public void writeCsv(String filePath) {
    try {
      new java.io.File(filePath).getParentFile().mkdirs();
      try (PrintWriter pw = new PrintWriter(new FileWriter(filePath))) {
        pw.println("timestamp_ms,messageType,latency_ms,statusCode,roomId");
        for (String line : csvLines) {
          pw.println(line);
        }
      }
      System.out.println("  CSV written to: " + filePath);
    } catch (IOException e) {
      System.err.println("Failed to write CSV: " + e.getMessage());
    }
  }

  public void writeChart(String filePath) {
    try {
      new java.io.File(filePath).getParentFile().mkdirs();
      long maxBucket = throughputBuckets.keySet().stream().mapToLong(Long::longValue).max()
          .orElse(0);
      StringBuilder labels = new StringBuilder();
      StringBuilder data = new StringBuilder();
      for (long b = 0; b <= maxBucket; b++) {
        if (b > 0) {
          labels.append(",");
          data.append(",");
        }
        labels.append("\"").append(b * 10).append("s\"");
        long count = throughputBuckets.getOrDefault(b, new AtomicLong(0)).get();
        data.append(String.format("%.0f", count / 10.0));
      }
      String html = "<!DOCTYPE html>\n<html>\n<head>\n" + "<title>Throughput Over Time</title>\n"
          + "<script src=\"https://cdn.jsdelivr.net/npm/chart.js\"></script>\n"
          + "</head>\n<body>\n" + "<h2>Throughput Over Time (10s buckets)</h2>\n"
          + "<canvas id=\"chart\" width=\"900\" height=\"400\"></canvas>\n" + "<script>\n"
          + "new Chart(document.getElementById('chart'), {\n" + "  type: 'line',\n" + "  data: {\n"
          + "    labels: [" + labels + "],\n" + "    datasets: [{\n"
          + "      label: 'Throughput (msg/s)',\n" + "      data: [" + data + "],\n"
          + "      borderColor: 'rgb(75, 192, 192)',\n"
          + "      backgroundColor: 'rgba(75, 192, 192, 0.1)',\n" + "      tension: 0.1,\n"
          + "      fill: true\n" + "    }]\n" + "  },\n" + "  options: {\n" + "    scales: {\n"
          + "      y: { beginAtZero: true, title: { display: true, text: 'msg/s' } },\n"
          + "      x: { title: { display: true, text: 'Time (seconds)' } }\n" + "    }\n" + "  }\n"
          + "});\n" + "</script>\n</body>\n</html>\n";
      try (PrintWriter pw = new PrintWriter(new FileWriter(filePath))) {
        pw.print(html);
      }
      System.out.println("  Chart written to: " + filePath);
    } catch (IOException e) {
      System.err.println("Failed to write chart: " + e.getMessage());
    }
  }

  public void throughputChart() {
    if (throughputBuckets.isEmpty()) {
      System.out.println("  No throughput data.");
      return;
    }
    long maxBucket = throughputBuckets.keySet().stream().mapToLong(Long::longValue).max().orElse(0);
    long maxCount = throughputBuckets.values().stream().mapToLong(AtomicLong::get).max().orElse(1);
    int barWidth = 40;
    System.out.println("  Throughput over time (10s buckets):");
    for (long b = 0; b <= maxBucket; b++) {
      long count = throughputBuckets.getOrDefault(b, new AtomicLong(0)).get();
      double msgPerSec = count / 10.0;
      int bars = (int) (count * barWidth / maxCount);
      System.out.printf("  %4ds-%4ds | %-40s %,.0f msg/s%n", b * 10, (b + 1) * 10, "█".repeat(bars),
          msgPerSec);
    }
  }

  public void messageTypeDistribution() {
    System.out.println("  Message type distribution:");
    messageTypeCounts.entrySet().stream().sorted(Map.Entry.comparingByKey())
        .forEach(e -> System.out.printf("    %-6s: %,d%n", e.getKey(), e.getValue().get()));
  }

  public double elapsedSeconds() {
    long end = (this.endNs == 0L) ? System.nanoTime() : this.endNs;
    if (this.startNs == 0L) {
      return 0.0;
    }
    return (end - this.startNs) / 1_000_000_000.0;
  }

  public double throughput() {
    double s = elapsedSeconds();
    if (s <= 0.0) {
      return 0.0;
    }
    return this.successCount.sum() / s;
  }

  public void latencyStats() {
    long[] sorted = latencies.stream().mapToLong(Long::longValue).sorted().toArray();
    if (sorted.length == 0) {
      System.out.println("  No latency data.");
      return;
    }
    double mean = Arrays.stream(sorted).average().orElse(0);
    long median = sorted[sorted.length / 2];
    long p95 = sorted[(int) (sorted.length * 0.95)];
    long p99 = sorted[(int) (sorted.length * 0.99)];
    long min = sorted[0];
    long max = sorted[sorted.length - 1];

    System.out.printf("  Mean latency        : %.2f ms%n", mean);
    System.out.printf("  Median latency      : %d ms%n", median);
    System.out.printf("  p95 latency         : %d ms%n", p95);
    System.out.printf("  p99 latency         : %d ms%n", p99);
    System.out.printf("  Min latency         : %d ms%n", min);
    System.out.printf("  Max latency         : %d ms%n", max);
  }

  public void roomThroughput() {
    double elapsed = elapsedSeconds();
    System.out.println("  Per-room throughput:");
    roomSuccess.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(
        e -> System.out.printf("    Room %2d: %,.2f msg/s%n", e.getKey(),
            e.getValue().get() / elapsed));
  }

  public void summary(String phase) {
    System.out.println("========================================");
    System.out.println("  " + phase);
    System.out.println("========================================");
    System.out.printf("  Successful messages : %,d%n", this.successCount.sum());
    System.out.printf("  - First attempt      : %,d%n",
        successCount.sum() - retrySuccessCount.sum());
    System.out.printf("  - After retry        : %,d%n", retrySuccessCount.sum());
    System.out.printf("  Failed messages     : %,d%n", this.failureCount.sum());
    System.out.printf("  Total processed     : %,d%n", this.getTotalProcessed());
    System.out.printf("  Send attempts       : %,d%n", this.sendAttempts.sum());
    System.out.printf("  Wall time           : %.3f seconds%n", this.elapsedSeconds());
    System.out.printf("  Throughput          : %,.2f msg/s%n", this.throughput());
    System.out.printf("  Total connections   : %,d%n", this.connectionCount.sum());
    System.out.printf("  Connection failures : %,d%n", this.connectionFailureCount.sum());
    System.out.printf("  Reconnections       : %,d%n", this.reconnectionCount.sum());
    System.out.printf("  Total retries       : %,d%n", this.retryCount.sum());
    System.out.printf("  Client blocking queue peak depth    : %,d%n", this.getPeakQueueDepth());
    System.out.printf("  Client blocking queue avg queue depth     : %.1f%n",
        this.getAvgQueueDepth());
    System.out.println("========================================");
  }
}

package assign2.client;

import assign2.client.connection.ConnectionManager;
import assign2.client.metrics.Metrics;
import assign2.client.model.ChatMessage;
import assign2.client.producer.MessageGenerator;
import assign2.client.sender.SenderWorker;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class ClientMain {

  // Priority: WS_URI env var > default localhost
  // Production: export WS_URI="ws://ALB_DNS/server/chat/"
  private static final String WS_URI = resolveWsUri();

  private static String resolveWsUri() {
    String envVal = System.getenv("WS_URI");
    if (envVal != null && !envVal.isEmpty()) return envVal;
    return "ws://localhost:8080/server/chat/";
  }
  private static final int TOTAL_MESSAGES = 500_000;

  private static final int WARMUP_THREADS = 32;
  private static final int MESSAGE_PER_THREAD = 1000;
  private static final int NUM_ROOMS = 20;
  private static final int QUEUE_CAPACITY = 10_000;

  public static void main(String[] args) throws Exception {
    runWarmup();
    runMainPhase();
  }

  // ============================== Warmup ==============================
  private static void runWarmup() throws Exception {
    int warmupTotal = WARMUP_THREADS * MESSAGE_PER_THREAD;  // 32_000
    BlockingQueue<ChatMessage> sharedQueue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);

    Thread warmupGenerator = new Thread(new MessageGenerator(sharedQueue, warmupTotal),
        "warmup-producer");
    warmupGenerator.start();
    Thread.sleep(500);

    Metrics warmupMetrics = new Metrics();
    ConnectionManager connMgr = new ConnectionManager(WS_URI, warmupMetrics);
    ExecutorService warmupExecutor = Executors.newFixedThreadPool(WARMUP_THREADS);

    warmupMetrics.start();

    for (int threadId = 0; threadId < WARMUP_THREADS; threadId++) {
      int roomId = threadId % NUM_ROOMS + 1;
      warmupExecutor.submit(() -> {
        try {
          for (int i = 0; i < MESSAGE_PER_THREAD; i++) {
            ChatMessage msg = sharedQueue.poll(2, TimeUnit.SECONDS);
            if (msg == null) {
              break;
            }
            warmupMetrics.recordSendAttempt();
            connMgr.acquire(roomId);
            try {
              ClientEndpoint endpoint = connMgr.conn(roomId);
              String ack = endpoint.sendAndAwaitAck(msg.toJson(), msg.getMessageId(), 5000);
              if (ack != null) {
                warmupMetrics.recordSuccess();
              } else {
                warmupMetrics.recordFailure();
              }
            } finally {
              connMgr.release(roomId);
            }
          }
        } catch (Exception e) {
          System.err.println("[Warmup] " + e.getMessage());
        }
      });
    }

    warmupExecutor.shutdown();
    warmupExecutor.awaitTermination(10, TimeUnit.MINUTES);
    warmupMetrics.stop();
    connMgr.closeAll();

    warmupGenerator.join();
    System.out.println("Warmup complete.");
    warmupMetrics.summary("Warmup");
  }


  // ============================== Main Phase ==============================
  private static void runMainPhase() throws Exception {
    int numSenders = Math.max(200, Runtime.getRuntime().availableProcessors() * 16);

    // roomId -> BlockingQueue
    Map<Integer, BlockingQueue<ChatMessage>> queues = new HashMap<>();
    for (int roomId = 1; roomId <= NUM_ROOMS; roomId++) {
      queues.put(roomId, new LinkedBlockingQueue<>(QUEUE_CAPACITY));
    }

    Metrics mainMetrics = new Metrics();
    ConnectionManager mainConnMgr = new ConnectionManager(WS_URI, mainMetrics);

    // 1. Start the generator - concurrent with the consumer
    Thread mainGenerator = new Thread(new MessageGenerator(queues, TOTAL_MESSAGES),
        "main-phase-producer");
    mainGenerator.start();
    Thread.sleep(500);

    mainMetrics.start();

    // 2. Start the consumer - concurrent with the generator
    ExecutorService mainExecutor = Executors.newFixedThreadPool(numSenders);
    for (int workerId = 0; workerId < numSenders; workerId++) {
      int roomId = workerId % NUM_ROOMS + 1;
      mainExecutor.submit(new SenderWorker(roomId, queues.get(roomId), mainConnMgr, mainMetrics));
    }

    // 3. Wait for the generator to finish
    mainGenerator.join();

    // 4. Put poison messages
    for (int workerId = 0; workerId < numSenders; workerId++) {
      int roomId = workerId % NUM_ROOMS + 1;
      queues.get(roomId).put(ChatMessage.POISON);
    }

    mainExecutor.shutdown();
    mainExecutor.awaitTermination(10, TimeUnit.MINUTES);
    mainMetrics.stop();
    mainConnMgr.closeAll();

    System.out.println("Main Phase completed");
    mainMetrics.summary("Main Phase");
    mainMetrics.latencyStats();
    mainMetrics.messageTypeDistribution();
    mainMetrics.roomThroughput();
    mainMetrics.throughputChart();
    // mainMetrics.writeCsv("results/metrics.csv");
    // mainMetrics.writeChart("results/throughput.html");
  }
}

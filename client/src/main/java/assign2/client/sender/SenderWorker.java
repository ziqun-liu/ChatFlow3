package assign2.client.sender;

import assign2.client.ClientEndpoint;
import assign2.client.connection.ConnectionManager;
import assign2.client.metrics.Metrics;
import assign2.client.model.ChatMessage;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

public class SenderWorker implements Runnable {

  private static final int MAX_RETRIES = 5;
  private static final long BASE_BACKOFF_MS = 100;

  private final int roomId;
  private final BlockingQueue<ChatMessage> queue;
  private final ConnectionManager connMgr;
  private final Metrics metrics;

  public SenderWorker(int roomId, BlockingQueue<ChatMessage> queue, ConnectionManager connMgr,
      Metrics metrics) {
    this.roomId = roomId;
    this.queue = queue;
    this.connMgr = connMgr;
    this.metrics = metrics;
  }

  @Override
  public void run() {
    try {
      while (true) {
        ChatMessage msg = queue.poll(2, TimeUnit.SECONDS);
        if (msg == null || msg == ChatMessage.POISON) {
          break;
        }
        metrics.recordSendAttempt();
        sendWithRetry(msg);
      }
    } catch (Exception e) {
      System.err.println("[SenderWorker room=" + roomId + "] " + e.getMessage());
    }
  }

  private void sendWithRetry(ChatMessage msg) throws InterruptedException {
    for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
      connMgr.acquire(roomId);
      try {
        ClientEndpoint client = connMgr.conn(roomId);
        long startMs = System.currentTimeMillis();
        String ack = client.sendAndAwaitAck(msg.toJson(), 5000);
        if (ack != null) {
          long latencyMs = System.currentTimeMillis() - startMs;
          int statusCode = "OK".equals(parseStatus(ack)) ? 200 : 400;
          metrics.recordMessageMetrics(startMs, msg.getMessageType(), latencyMs, statusCode, roomId);
          if (attempt > 0) {
            metrics.recordRetrySuccess();
          }
          return;
        }
        // timeout — reconnect and retry
        connMgr.reconnect(roomId);
      } catch (Exception e) {
        System.err.println("[Retry " + attempt + " room=" + roomId + "] " + e.getMessage());
        try {
          connMgr.reconnect(roomId);
        } catch (Exception re) {
          System.err.println("[Reconnect failed room=" + roomId + "] " + re.getMessage());
        }
      } finally {
        connMgr.release(roomId);
      }
      long backoff = BASE_BACKOFF_MS * (1L << attempt);
      Thread.sleep(backoff);
    }
    metrics.recordFailure();
  }

  private String parseStatus(String responseJson) {
    try {
      JsonObject obj = JsonParser.parseString(responseJson).getAsJsonObject();
      return obj.get("status").getAsString();
    } catch (Exception e) {
      return "UNKNOWN";
    }
  }
}

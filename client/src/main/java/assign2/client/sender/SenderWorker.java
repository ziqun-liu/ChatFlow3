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
        ChatMessage msg = this.queue.poll(2, TimeUnit.SECONDS);
        if (msg == null || msg == ChatMessage.POISON) {
          break;
        }
        this.metrics.recordSendAttempt();
        sendWithRetry(msg);
      }
    } catch (Exception e) {
      System.err.println("[SenderWorker room=" + this.roomId + "] " + e.getMessage());
    }
  }

  private void sendWithRetry(ChatMessage msg) throws InterruptedException {
    for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
      this.connMgr.acquire(this.roomId);
      try {
        ClientEndpoint client = this.connMgr.conn(this.roomId);
        long startMs = System.currentTimeMillis();
        String ack = client.sendAndAwaitAck(msg.toJson(), msg.getMessageId(), 5000);
        if (ack != null) {
          long latencyMs = System.currentTimeMillis() - startMs;
          int statusCode = isAckSuccess(ack) ? 200 : 400;
          this.metrics.recordMessageMetrics(startMs, msg.getMessageType(), latencyMs, statusCode, roomId);
          if (attempt > 0) {
            this.metrics.recordRetrySuccess();
          }
          return;
        }
        // timeout — reconnect and retry
        this.connMgr.reconnect(this.roomId);
      } catch (Exception e) {
        System.err.println("[Retry " + attempt + " room=" + this.roomId + "] " + e.getMessage());
        try {
          this.connMgr.reconnect(this.roomId);
        } catch (Exception re) {
          System.err.println("[Reconnect failed room=" + this.roomId + "] " + re.getMessage());
        }
      } finally {
        this.connMgr.release(this.roomId);
      }
      long backoff = BASE_BACKOFF_MS * (1L << attempt);
      Thread.sleep(backoff);
    }
    this.metrics.recordFailure();
  }

  // Returns true if the ACK indicates the server successfully published the message.
  // type=ACK means publish succeeded; type=ERROR means validation or publish failed.
  private boolean isAckSuccess(String responseJson) {
    try {
      JsonObject obj = JsonParser.parseString(responseJson).getAsJsonObject();
      return "ACK".equals(obj.get("type").getAsString());
    } catch (Exception e) {
      return false;
    }
  }
}

package assign2.server.v2.service;

import assign2.server.v2.config.RedisConfig;
import assign2.server.v2.model.ChatResponse;
import assign2.server.v2.model.QueueMessage;
import com.google.gson.Gson;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import javax.websocket.Session;
import redis.clients.jedis.JedisPubSub;
import redis.clients.jedis.Jedis;

/**
 * Subscribes to Redis Pub/Sub channels "room.*" and broadcasts messages
 * to local WebSocket sessions held by this server-v2 instance.
 *
 * Replaces BroadcastServlet — instead of waiting for HTTP POST from Consumer,
 * this subscriber is push-notified by Redis when Consumer publishes a message.
 *
 * Runs on a single background daemon thread. Redis psubscribe() blocks until
 * unsubscribe() is called or the connection is closed.
 *
 * Lifecycle: call start() once on application startup, stop() on shutdown.
 */
public class RedisSubscriber {

  private static final Logger logger = Logger.getLogger(RedisSubscriber.class.getName());
  private static final String CHANNEL_PATTERN = "room.*";
  private static final Gson GSON = new Gson();

  private volatile JedisPubSub pubSub;
  private volatile Thread subscriberThread;

  /**
   * Starts the Redis subscriber on a background daemon thread.
   * Only call once — subsequent calls are no-ops if already running.
   */
  public void start() {
    if (this.subscriberThread != null && this.subscriberThread.isAlive()) {
      logger.warning("RedisSubscriber already running, ignoring start().");
      return;
    }

    this.pubSub = new JedisPubSub() {
      @Override
      public void onPMessage(String pattern, String channel, String message) {
        // channel = "room.3", message = QueueMessage JSON
        handleMessage(channel, message);
      }

      @Override
      public void onPSubscribe(String pattern, int subscribedChannels) {
        logger.info("Redis psubscribe: pattern=" + pattern
            + ", channels=" + subscribedChannels);
      }
    };

    this.subscriberThread = new Thread(() -> {
      // Retry loop — reconnect if Redis drops
      while (!Thread.currentThread().isInterrupted()) {
        try (Jedis jedis = new Jedis(RedisConfig.HOST, RedisConfig.PORT)) {
          logger.info("RedisSubscriber connecting: host=" + RedisConfig.HOST
              + ", port=" + RedisConfig.PORT);
          jedis.psubscribe(this.pubSub, CHANNEL_PATTERN);
          // psubscribe() returns only when unsubscribed intentionally (stop() called)
          break;
        } catch (Exception e) {
          logger.warning("RedisSubscriber disconnected, retrying in 3s: " + e.getMessage());
        }
        try { Thread.sleep(3000); } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          break;
        }
      }
      logger.info("RedisSubscriber thread exiting.");
    }, "redis-subscriber");

    this.subscriberThread.setDaemon(true);
    this.subscriberThread.start();
    logger.info("RedisSubscriber started.");
  }

  /**
   * Stops the Redis subscriber gracefully. Also interrupts the thread to exit
   * any retry backoff sleep.
   */
  public void stop() {
    if (pubSub != null && pubSub.isSubscribed()) {
      pubSub.punsubscribe();
    }
    if (subscriberThread != null) {
      subscriberThread.interrupt();
    }
    logger.info("RedisSubscriber stopped.");
  }

  // ── Helpers ──────────────────────────────────────────────────────────────

  private void handleMessage(String channel, String json) {
    // 1. Deserialize
    QueueMessage msg;
    try {
      msg = GSON.fromJson(json, QueueMessage.class);
      if (msg == null || msg.getRoomId() == null || msg.getMessageId() == null) {
        throw new IllegalArgumentException("Missing required fields");
      }
    } catch (Exception e) {
      logger.warning("Invalid Redis message on channel=" + channel + ": " + e.getMessage());
      return;
    }

    // 2. Look up local sessions for this room
    String roomId = msg.getRoomId();
    Set<Session> sessions = RoomManager.getSessions(roomId);

    if (sessions == null || sessions.isEmpty()) {
      // No clients connected to this room on this instance — other instances may have them
      logger.fine("No sessions for room=" + roomId + ", nothing to broadcast.");
      return;
    }

    // 3. Broadcast to all open sessions
    String broadcastJson = new ChatResponse(msg).toJson();
    AtomicInteger sent = new AtomicInteger(0);
    AtomicInteger failed = new AtomicInteger(0);

    for (Session session : sessions) {
      synchronized (session) {
        if (session.isOpen()) {
          try {
            session.getBasicRemote().sendText(broadcastJson);
            sent.incrementAndGet();
          } catch (IOException e) {
            logger.warning("Failed to broadcast to session=" + session.getId()
                + ", room=" + roomId + ": " + e.getMessage());
            failed.incrementAndGet();
          }
        }
      }
    }

    logger.fine("Broadcast complete: room=" + roomId + ", messageId=" + msg.getMessageId()
        + ", sent=" + sent + ", failed=" + failed);
  }
}

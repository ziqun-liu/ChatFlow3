package assign2.consumer.service;

import assign2.consumer.config.RedisConfig;
import assign2.consumer.model.QueueMessage;
import java.util.logging.Logger;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.params.SetParams;

/**
 * Publishes QueueMessage JSON to a Redis Pub/Sub channel.
 *
 * Channel naming: "room.{roomId}" — mirrors RabbitMQ queue naming convention.
 * server-v2 instances subscribe to "room.*" and broadcast to their local WebSocket sessions.
 *
 * Delivery guarantee: fire-and-forget (Redis Pub/Sub has no ACK mechanism).
 * RabbitMQ at-least-once delivery is preserved up to this point via basicAck after publish().
 */
public class RedisPublisher {

  private static final Logger logger = Logger.getLogger(RedisPublisher.class.getName());

  private final JedisPool jedisPool;

  public RedisPublisher() {
    this.jedisPool = new JedisPool(RedisConfig.HOST, RedisConfig.PORT);
    logger.info("RedisPublisher initialized: host=" + RedisConfig.HOST + ", port=" + RedisConfig.PORT);
  }

  /**
   * Publishes the message JSON to Redis channel "room.{roomId}".
   * Returns true on success, false if an exception occurred.
   */
  public boolean publish(QueueMessage msg) {
    String channel = "room." + msg.getRoomId();
    String json = msg.toJson();
    try (Jedis jedis = jedisPool.getResource()) {
      jedis.publish(channel, json);
      logger.fine("Published to Redis: channel=" + channel + ", messageId=" + msg.getMessageId());
      ConsumerMetrics.getInstance().recordRedisSuccess();
      return true;
    } catch (Exception e) {
      logger.warning("Redis publish failed: channel=" + channel
          + ", messageId=" + msg.getMessageId()
          + ", error=" + e.getMessage());
      ConsumerMetrics.getInstance().recordRedisFailure();
      return false;
    }
  }

  private static final int DEDUP_TTL_SECONDS = 30;
  private static final String DEDUP_KEY_PREFIX = "msg:dedup:";

  /**
   * Returns true if this messageId has already been processed (duplicate).
   * Uses Redis SET NX EX — atomic check-and-set with TTL.
   * Fail-open: returns false if Redis is unavailable.
   */
  public boolean isDuplicate(String messageId) {
    try (Jedis jedis = jedisPool.getResource()) {
      String result = jedis.set(
          DEDUP_KEY_PREFIX + messageId, "1",
          new SetParams().nx().ex(DEDUP_TTL_SECONDS));
      return result == null;  // null = key existed = duplicate
    } catch (Exception e) {
      logger.warning("Dedup check failed for messageId=" + messageId + ": " + e.getMessage());
      return false;  // fail open
    }
  }

  public void close() {
    jedisPool.close();
    logger.info("RedisPublisher closed.");
  }
}

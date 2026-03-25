package assign2.consumer.v3.service;

import assign2.consumer.v3.config.RedisConfig;
import assign2.consumer.v3.model.QueueMessage;
import java.time.Duration;
import java.util.logging.Logger;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.params.SetParams;

public class RedisPublisher {

  private static final Logger logger = Logger.getLogger(RedisPublisher.class.getName());

  private final JedisPool jedisPool;

  public RedisPublisher() {
    JedisPoolConfig poolConfig = new JedisPoolConfig();
    poolConfig.setMaxTotal(RedisConfig.POOL_MAX_TOTAL);
    poolConfig.setMaxWait(Duration.ofMillis(RedisConfig.POOL_MAX_WAIT_MS));
    poolConfig.setTestOnBorrow(true);
    this.jedisPool = new JedisPool(poolConfig, RedisConfig.HOST, RedisConfig.PORT);
    logger.info("RedisPublisher initialized: host=" + RedisConfig.HOST + ", port=" + RedisConfig.PORT);
  }

  public boolean publish(QueueMessage msg) {
    String channel = "room." + msg.getRoomId();
    try (Jedis jedis = jedisPool.getResource()) {
      jedis.publish(channel, msg.toJson());
      ConsumerMetrics.getInstance().recordRedisSuccess();
      return true;
    } catch (Exception e) {
      logger.warning("Redis publish failed: channel=" + channel + ", error=" + e.getMessage());
      ConsumerMetrics.getInstance().recordRedisFailure();
      return false;
    }
  }

  private static final int DEDUP_TTL_SECONDS = 30;
  private static final String DEDUP_KEY_PREFIX = "msg:dedup:";

  public boolean isDuplicate(String messageId) {
    try (Jedis jedis = jedisPool.getResource()) {
      String result = jedis.set(DEDUP_KEY_PREFIX + messageId, "1",
          new SetParams().nx().ex(DEDUP_TTL_SECONDS));
      return result == null;
    } catch (Exception e) {
      logger.warning("Dedup check failed for messageId=" + messageId + ": " + e.getMessage());
      return false;
    }
  }

  public void close() {
    jedisPool.close();
  }
}
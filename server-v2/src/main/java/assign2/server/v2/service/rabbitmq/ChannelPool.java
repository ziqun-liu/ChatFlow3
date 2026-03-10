package assign2.server.v2.service.rabbitmq;

import assign2.server.v2.config.RabbitMQConfig;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;

/**
 * Singleton channel pool for RabbitMQ.
 * One shared Connection, multiple pre-created Channels.
 * Channels are NOT thread-safe, so each thread must borrow/return its own channel.
 *
 * Usage:
 *   Channel ch = ChannelPool.getInstance().borrow();
 *   try { ... } finally { ChannelPool.getInstance().returnChannel(ch); }
 */
public class ChannelPool {

  private static final Logger logger = Logger.getLogger(ChannelPool.class.getName());
  private static final int POOL_SIZE = 20;

  private static volatile ChannelPool instance;

  private final Connection connection;
  private final BlockingQueue<Channel> pool;

  private ChannelPool() throws IOException, TimeoutException {
    ConnectionFactory factory = new ConnectionFactory();
    factory.setHost(RabbitMQConfig.HOST);
    factory.setPort(RabbitMQConfig.PORT);
    factory.setUsername(RabbitMQConfig.USERNAME);
    factory.setPassword(RabbitMQConfig.PASSWORD);

    this.connection = factory.newConnection("server-v2-pool");
    this.pool = new ArrayBlockingQueue<>(POOL_SIZE);

    for (int i = 0; i < POOL_SIZE; i++) {
      Channel ch = connection.createChannel();
      // Declare exchange once per channel — idempotent, safe to repeat
      ch.exchangeDeclare(RabbitMQConfig.EXCHANGE, RabbitMQConfig.EXCHANGE_TYPE, true);
      // Enable publisher confirms on this channel
      ch.confirmSelect();
      pool.add(ch);
    }
    logger.info("ChannelPool initialized: " + POOL_SIZE + " channels, exchange="
        + RabbitMQConfig.EXCHANGE);
  }

  public static ChannelPool getInstance() throws IOException, TimeoutException {
    if (instance == null) {
      synchronized (ChannelPool.class) {
        if (instance == null) {
          instance = new ChannelPool();
        }
      }
    }
    return instance;
  }

  /**
   * Borrows a channel from the pool. Blocks until one is available.
   */
  public Channel borrow() throws InterruptedException {
    return pool.take();
  }

  /**
   * Returns a channel to the pool. If the channel is broken, replaces it with a new one.
   */
  public void returnChannel(Channel channel) {
    if (channel != null && channel.isOpen()) {
      pool.offer(channel);
    } else {
      // Channel is broken — replace with a fresh one
      try {
        Channel fresh = connection.createChannel();
        fresh.exchangeDeclare(RabbitMQConfig.EXCHANGE, RabbitMQConfig.EXCHANGE_TYPE, true);
        fresh.confirmSelect();
        pool.offer(fresh);
        logger.warning("Replaced broken channel with a new one.");
      } catch (Exception e) {
        logger.severe("Failed to replace broken channel: " + e.getMessage());
      }
    }
  }

  public void shutdown() {
    try {
      pool.forEach(ch -> {
        try { ch.close(); } catch (Exception ignored) {}
      });
      connection.close();
      logger.info("ChannelPool shut down.");
    } catch (Exception e) {
      logger.warning("Error during ChannelPool shutdown: " + e.getMessage());
    }
  }
}

package assign2.server.v2.service;

import assign2.server.v2.config.RabbitMQConfig;
import assign2.server.v2.model.QueueMessage;
import assign2.server.v2.service.rabbitmq.ChannelPool;
import assign2.server.v2.service.rabbitmq.CircuitBreaker;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import java.util.logging.Logger;

/**
 * Publishes a QueueMessage to RabbitMQ using publisher confirms. Borrows a channel from
 * ChannelPool, publishes, waits for confirm, then returns channel.
 * <p>
 * Wraps publish attempts with a CircuitBreaker: - OPEN state: fast-fails immediately without
 * touching RabbitMQ - HALF_OPEN state: allows one probe through to test recovery - CLOSED state:
 * normal operation
 * <p>
 * Returns true if RabbitMQ confirmed receipt, false otherwise.
 */
public class MessagePublisher {

  private static final Logger logger = Logger.getLogger(MessagePublisher.class.getName());
  private static final long CONFIRM_TIMEOUT_MS = 3000;

  // Open after 5 consecutive failures; retry after 30 seconds.
  private static final int CB_FAILURE_THRESHOLD = 5;
  private static final long CB_COOLDOWN_MS = 30_000;

  private final ChannelPool channelPool;
  private final CircuitBreaker circuitBreaker;

  public MessagePublisher(ChannelPool channelPool) {
    this.channelPool = channelPool;
    this.circuitBreaker = new CircuitBreaker(CB_FAILURE_THRESHOLD, CB_COOLDOWN_MS);
  }

  /**
   * Publishes message to chat.exchange with routing key room.{roomId}. Blocks until RabbitMQ
   * confirms (or times out), unless circuit is OPEN.
   *
   * @return true if confirmed by RabbitMQ, false on timeout, error, or open circuit
   */
  public boolean publish(QueueMessage queueMsg) {
    // Fast-fail if circuit is OPEN — avoids waiting CONFIRM_TIMEOUT_MS per message
    if (!this.circuitBreaker.allowRequest()) {
      logger.warning(
          "CircuitBreaker OPEN — skipping publish for messageId=" + queueMsg.getMessageId());
      return false;
    }

    Channel channel = null;
    try {
      channel = this.channelPool.borrow();

      String routingKey = RabbitMQConfig.routingKey(queueMsg.getRoomId());
      byte[] body = queueMsg.toJson().getBytes("UTF-8");

      AMQP.BasicProperties props = new AMQP.BasicProperties.Builder().contentType(
              "application/json").deliveryMode(2)           // persistent
          .messageId(queueMsg.getMessageId()).build();

      channel.basicPublish(RabbitMQConfig.EXCHANGE, routingKey, props, body);

      // Wait for RabbitMQ broker confirm — blocks up to CONFIRM_TIMEOUT_MS
      boolean confirmed = channel.waitForConfirms(CONFIRM_TIMEOUT_MS);
      if (confirmed) {
        this.circuitBreaker.recordSuccess();
      } else {
        logger.warning("RabbitMQ nack for messageId=" + queueMsg.getMessageId());
        this.circuitBreaker.recordFailure();
      }
      return confirmed;

    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      logger.warning("Interrupted while waiting for confirm: " + e.getMessage());
      this.circuitBreaker.recordFailure();
      return false;
    } catch (Exception e) {
      logger.severe(
          "Failed to publish messageId=" + queueMsg.getMessageId() + ": " + e.getMessage());
      this.circuitBreaker.recordFailure();
      return false;
    } finally {
      this.channelPool.returnChannel(channel);
    }
  }

  public void logMetrics() {
    this.circuitBreaker.summary();
  }
}

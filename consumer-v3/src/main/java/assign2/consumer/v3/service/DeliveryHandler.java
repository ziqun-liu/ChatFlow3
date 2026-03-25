package assign2.consumer.v3.service;

import assign2.consumer.v3.model.ProcessingResult;
import assign2.consumer.v3.model.ProcessingResult.Status;
import assign2.consumer.v3.model.QueueMessage;
import assign2.consumer.v3.service.db.MessageBatchQueue;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Envelope;
import java.io.IOException;
import java.util.logging.Logger;

/**
 * Layer 3 — Result Execution.
 * Same ack/nack policy as consumer v2, plus non-blocking offer() to the DB batch queue
 * after every successful basicAck.
 */
public class DeliveryHandler {

  private static final Logger logger = Logger.getLogger(DeliveryHandler.class.getName());

  private final RedisPublisher redisPublisher;

  public DeliveryHandler(RedisPublisher redisPublisher) {
    this.redisPublisher = redisPublisher;
  }

  public void handle(Channel channel, Envelope envelope, ProcessingResult result)
      throws IOException {

    long deliveryTag = envelope.getDeliveryTag();
    long latencyNs = System.nanoTime() - result.getReceivedAt();
    boolean isRedeliver = envelope.isRedeliver();

    if (result.getStatus() == Status.MALFORMED) {
      channel.basicAck(deliveryTag, false);
      ConsumerMetrics.getInstance().recordNackDiscard();
      ConsumerMetrics.getInstance().recordLatency(latencyNs);
      logOutcome("UNKNOWN", "UNKNOWN", "UNKNOWN", "MALFORMED_DISCARD", latencyNs, isRedeliver);
      return;
    }

    QueueMessage msg = result.getMessage();

    if (isRedeliver && redisPublisher.isDuplicate(msg.getMessageId())) {
      channel.basicAck(deliveryTag, false);
      ConsumerMetrics.getInstance().recordDuplicateDiscard();
      ConsumerMetrics.getInstance().recordLatency(latencyNs);
      logOutcome(msg.getMessageId(), msg.getRoomId(), msg.getMessageType(), "DUPLICATE_DISCARD", latencyNs, isRedeliver);
      return;
    }

    boolean success = redisPublisher.publish(msg);

    if (success) {
      channel.basicAck(deliveryTag, false);
      // Non-blocking offer to DB batch queue — never stalls RabbitMQ consumer thread
      MessageBatchQueue.getInstance().offer(msg);
      ConsumerMetrics.getInstance().recordAck();
      ConsumerMetrics.getInstance().recordLatency(latencyNs);
      logOutcome(msg.getMessageId(), msg.getRoomId(), msg.getMessageType(), "ACK", latencyNs, isRedeliver);
    } else if (isRedeliver) {
      channel.basicNack(deliveryTag, false, false);
      ConsumerMetrics.getInstance().recordNackDiscard();
      ConsumerMetrics.getInstance().recordLatency(latencyNs);
      logOutcome(msg.getMessageId(), msg.getRoomId(), msg.getMessageType(), "NACK_DISCARD", latencyNs, isRedeliver);
    } else {
      channel.basicNack(deliveryTag, false, true);
      ConsumerMetrics.getInstance().recordNackRequeue();
      ConsumerMetrics.getInstance().recordLatency(latencyNs);
      logOutcome(msg.getMessageId(), msg.getRoomId(), msg.getMessageType(), "NACK_REQUEUE", latencyNs, isRedeliver);
    }
  }

  private void logOutcome(String messageId, String roomId, String messageType, String outcome,
      long latencyNs, boolean isRedeliver) {
    logger.fine(String.format(
        "DELIVERY messageId=%s roomId=%s messageType=%s outcome=%s latencyMs=%.2f redeliver=%b",
        messageId, roomId, messageType, outcome, latencyNs / 1_000_000.0, isRedeliver));
  }
}
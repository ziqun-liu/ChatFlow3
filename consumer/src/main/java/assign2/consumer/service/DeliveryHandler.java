package assign2.consumer.service;

import assign2.consumer.model.ProcessingResult;
import assign2.consumer.model.ProcessingResult.Status;
import assign2.consumer.model.QueueMessage;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Envelope;
import java.io.IOException;
import java.util.logging.Logger;

/**
 * Layer 3 — Result Execution Owns the complete ack/nack policy: Redis publish + ack/nack decision +
 * metrics. Channel is passed per-call because it is created inside RoomConsumer.run() and is not
 * known at DeliveryHandler construction time.
 * <p>
 * Ack policy: MALFORMED                         → basicAck (discard)        + recordNackDiscard
 * SUCCESS + Redis ok                → basicAck                  + recordAck SUCCESS + Redis fail +
 * redeliver  → basicNack(requeue=false)  + recordNackDiscard SUCCESS + Redis fail + first try  →
 * basicNack(requeue=true)   + recordNackRequeue
 * <p>
 * Per-message outcome is logged as a structured INFO line for observability: DELIVERY messageId=X
 * roomId=Y outcome=ACK latencyMs=3 redeliver=false
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

    // Dedup check — skip if already processed (nack+requeue duplicate)
    if (redisPublisher.isDuplicate(msg.getMessageId())) {
      channel.basicAck(deliveryTag, false);
      ConsumerMetrics.getInstance().recordDuplicateDiscard();
      ConsumerMetrics.getInstance().recordLatency(latencyNs);
      logOutcome(msg.getMessageId(), msg.getRoomId(), msg.getMessageType(), "DUPLICATE_DISCARD",
          latencyNs, isRedeliver);
      return;
    }

    boolean success = redisPublisher.publish(msg);

    if (success) {
      channel.basicAck(deliveryTag, false);
      ConsumerMetrics.getInstance().recordAck();
      ConsumerMetrics.getInstance().recordLatency(latencyNs);
      logOutcome(msg.getMessageId(), msg.getRoomId(), msg.getMessageType(), "ACK", latencyNs,
          isRedeliver);
    } else if (isRedeliver) {
      channel.basicNack(deliveryTag, false, false); // requeue=false
      ConsumerMetrics.getInstance().recordNackDiscard();
      ConsumerMetrics.getInstance().recordLatency(latencyNs);
      logOutcome(msg.getMessageId(), msg.getRoomId(), msg.getMessageType(), "NACK_DISCARD",
          latencyNs, isRedeliver);
    } else {
      channel.basicNack(deliveryTag, false, true);  // requeue=true
      ConsumerMetrics.getInstance().recordNackRequeue();
      ConsumerMetrics.getInstance().recordLatency(latencyNs);
      logOutcome(msg.getMessageId(), msg.getRoomId(), msg.getMessageType(), "NACK_REQUEUE",
          latencyNs, isRedeliver);
    }
  }

  private void logOutcome(String messageId, String roomId, String messageType, String outcome,
      long latencyNs, boolean isRedeliver) {
    logger.info(String.format(
        "DELIVERY messageId=%s roomId=%s messageType=%s outcome=%s latencyMs=%.2f redeliver=%b",
        messageId, roomId, messageType, outcome, latencyNs / 1_000_000.0, isRedeliver));
  }
}

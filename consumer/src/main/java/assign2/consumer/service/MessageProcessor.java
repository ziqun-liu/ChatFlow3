package assign2.consumer.service;

import assign2.consumer.model.ProcessingResult;
import assign2.consumer.model.QueueMessage;
import java.util.logging.Logger;

/**
 * Layer 2 — Message Processing
 * Converts raw RabbitMQ delivery bytes into a ProcessingResult.
 * Stateless: no Channel, no Redis, no metrics.
 */
public class MessageProcessor {

  private static final Logger logger = Logger.getLogger(MessageProcessor.class.getName());

  /**
   * Parses raw delivery bytes into a ProcessingResult.
   * receivedAt is captured in Layer 1 before this call and threaded through
   * to ProcessingResult so Layer 3 can compute end-to-end latency.
   * Returns ProcessingResult.malformed() if the body cannot be decoded,
   * the JSON is invalid, or required fields (messageId, roomId) are missing.
   * Never throws.
   */
  public ProcessingResult process(byte[] body, long receivedAt) {
    String json;
    try {
      json = new String(body, "UTF-8");
    } catch (Exception e) {
      logger.warning("Cannot decode delivery body: " + e.getMessage());
      return ProcessingResult.malformed(receivedAt);
    }

    QueueMessage msg;
    try {
      msg = QueueMessage.fromJson(json);
    } catch (Exception e) {
      logger.warning("Malformed message, discarding: " + e.getMessage());
      return ProcessingResult.malformed(receivedAt);
    }

    if (msg == null || msg.getMessageId() == null || msg.getRoomId() == null) {
      logger.warning("Missing required fields (messageId or roomId), discarding.");
      return ProcessingResult.malformed(receivedAt);
    }

    logger.fine("Parsed: messageId=" + msg.getMessageId() + ", room=" + msg.getRoomId());
    return ProcessingResult.success(msg, receivedAt);
  }
}

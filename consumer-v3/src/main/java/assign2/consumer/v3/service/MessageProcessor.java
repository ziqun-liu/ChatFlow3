package assign2.consumer.v3.service;

import assign2.consumer.v3.model.ProcessingResult;
import assign2.consumer.v3.model.QueueMessage;
import java.util.logging.Logger;

/**
 * Layer 2 — Message Processing.
 * Converts raw RabbitMQ delivery bytes into a ProcessingResult. Stateless.
 */
public class MessageProcessor {

  private static final Logger logger = Logger.getLogger(MessageProcessor.class.getName());

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

    return ProcessingResult.success(msg, receivedAt);
  }
}
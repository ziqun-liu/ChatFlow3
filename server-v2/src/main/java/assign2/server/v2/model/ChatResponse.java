package assign2.server.v2.model;

import com.google.gson.Gson;
import java.time.Instant;
import java.util.List;


public class ChatResponse {

  private static final Gson GSON = new Gson();

  private final String messageId;
  private final String userId;
  private final String username;
  private final String message;
  private final String timestamp;
  private final String messageType;
  private final String serverTimestamp;
  private final String status;
  private final String error;
  private final String type;

  // ACK response — sent directly back to the sender after successful publish to RabbitMQ
  public static ChatResponse ack(String messageId) {
    return new ChatResponse(messageId);
  }

  // Error response with messageId (for validation or publish failures when messageId is known)
  public static ChatResponse error(String messageId, String errorMsg) {
    return new ChatResponse(messageId, errorMsg);
  }

  // Error response without messageId (e.g. JSON parse failure before messageId is available)
  public static ChatResponse error(String errorMsg) {
    return new ChatResponse((String) null, errorMsg);
  }

  private ChatResponse(String messageId, String errorMsg) {
    this.messageId = messageId;
    this.userId = null;
    this.username = null;
    this.message = null;
    this.timestamp = null;
    this.messageType = null;
    this.serverTimestamp = Instant.now().toString();
    this.status = "ERROR";
    this.error = errorMsg;
    this.type = "ERROR";
  }

  private ChatResponse(String messageId) {
    this.messageId = messageId;
    this.userId = null;
    this.username = null;
    this.message = null;
    this.timestamp = null;
    this.messageType = null;
    this.serverTimestamp = Instant.now().toString();
    this.status = "OK";
    this.error = null;
    this.type = "ACK";
  }

  // Broadcast response — built from QueueMessage
  public ChatResponse(QueueMessage msg) {
    this.messageId   = msg.getMessageId();
    this.userId      = msg.getUserId();
    this.username    = msg.getUsername();
    this.message     = msg.getMessage();
    this.timestamp   = msg.getTimestamp();
    this.messageType = msg.getMessageType();
    this.serverTimestamp = Instant.now().toString();
    this.status = "OK";
    this.error  = null;
    this.type   = "BROADCAST";
  }

  // Error response
  public ChatResponse(List<String> errors) {
    this.messageId = null;
    this.userId = null;
    this.username = null;
    this.message = null;
    this.timestamp = null;
    this.messageType = null;
    this.serverTimestamp = Instant.now().toString();
    this.status = "ERROR";
    this.error = String.join("; ", errors);
    this.type = "ERROR";
  }

  public String toJson() {
    return GSON.toJson(this);
  }
}

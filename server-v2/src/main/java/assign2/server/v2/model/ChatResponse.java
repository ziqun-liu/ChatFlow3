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

  // Success response
  public ChatResponse(ChatMessageDto msg) {
    this.messageId = msg.getMessageId();
    this.userId = msg.getUserId();
    this.username = msg.getUsername();
    this.message = msg.getMessage();
    this.timestamp = msg.getTimestamp();
    this.messageType = msg.getMessageType();
    this.serverTimestamp = Instant.now().toString();
    this.status = "OK";
    this.error = null;
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
  }

  public String toJson() {
    return GSON.toJson(this);
  }
}

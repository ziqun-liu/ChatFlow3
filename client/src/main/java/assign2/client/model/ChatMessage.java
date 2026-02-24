package assign2.client.model;

import com.google.gson.Gson;
import java.time.Instant;
import java.util.UUID;

public class ChatMessage {

  private static final Gson GSON = new Gson();
  public static final ChatMessage POISON = new ChatMessage("0", "poison", "poison", "TEXT", 0);

  private final String messageId;
  private final String userId;
  private final String username;
  private final String message;
  private final String timestamp;
  private final String messageType;
  private final int roomId;

  public ChatMessage(String userId, String username, String message, String messageType,
      int roomId) {
    this.messageId = UUID.randomUUID().toString();
    this.userId = userId;
    this.username = username;
    this.message = message;
    this.timestamp = Instant.now().toString();
    this.messageType = messageType;
    this.roomId = roomId;
  }

  public String toJson() {
    return GSON.toJson(
        new JsonPayload(this.messageId, this.userId, this.username, this.message, this.timestamp,
            this.messageType));
  }

  public int getRoomId() {
    return this.roomId;
  }

  public String getMessageType() {
    return this.messageType;
  }

  public String getMessageId() {
    return messageId;
  }

  // Inner class matching server's expected JSON structure (no roomId in payload)
  private static class JsonPayload {

    final String messageId;
    final String userId;
    final String username;
    final String message;
    final String timestamp;
    final String messageType;

    JsonPayload(String messageId, String userId, String username, String message, String timestamp,
        String messageType) {
      this.messageId = messageId;
      this.userId = userId;
      this.username = username;
      this.message = message;
      this.timestamp = timestamp;
      this.messageType = messageType;
    }
  }
}
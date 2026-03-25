package assign2.consumer.v3.model;

import com.google.gson.Gson;

/**
 * Mirrors the QueueMessage published by server-v2.
 * Used to deserialize messages consumed from RabbitMQ.
 */
public class QueueMessage {

  private static final Gson GSON = new Gson();

  private String messageId;
  private String roomId;
  private String userId;
  private String username;
  private String message;
  private String timestamp;
  private String messageType;
  private String serverId;
  private String clientIp;

  public QueueMessage() {}

  public static QueueMessage fromJson(String json) {
    return GSON.fromJson(json, QueueMessage.class);
  }

  public String toJson() {
    return GSON.toJson(this);
  }

  public String getMessageId()   { return messageId; }
  public String getRoomId()      { return roomId; }
  public String getUserId()      { return userId; }
  public String getUsername()    { return username; }
  public String getMessage()     { return message; }
  public String getTimestamp()   { return timestamp; }
  public String getMessageType() { return messageType; }
  public String getServerId()    { return serverId; }
  public String getClientIp()    { return clientIp; }
}
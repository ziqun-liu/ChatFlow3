package assign2.server.v2.model;

import com.google.gson.Gson;

/**
 * Message format published to RabbitMQ.
 * Superset of ChatMessageDto — adds roomId, serverId, clientIp for routing and tracing.
 */
public class QueueMessage {

    private static final Gson GSON = new Gson();

    private final String messageId;
    private final String roomId;
    private final String userId;
    private final String username;
    private final String message;
    private final String timestamp;
    private final String messageType;
    private final String serverId;
    private final String clientIp;

    public QueueMessage(ChatMessageDto dto, String roomId, String serverId, String clientIp) {
        this.messageId = dto.getMessageId();
        this.roomId = roomId;
        this.userId = dto.getUserId();
        this.username = dto.getUsername();
        this.message = dto.getMessage();
        this.timestamp = dto.getTimestamp();
        this.messageType = dto.getMessageType();
        this.serverId = serverId;
        this.clientIp = clientIp;
    }

    public String toJson() {
        return GSON.toJson(this);
    }

    public String getMessageId() {
        return messageId;
    }

    public String getRoomId() {
        return roomId;
    }

    public String getUserId() {
        return userId;
    }

    public String getUsername() {
        return username;
    }

    public String getMessage() {
        return message;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public String getMessageType() {
        return messageType;
    }

    public String getServerId() {
        return serverId;
    }

    public String getClientIp() {
        return clientIp;
    }
}

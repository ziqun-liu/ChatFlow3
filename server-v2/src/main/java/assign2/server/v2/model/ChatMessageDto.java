package assign2.server.v2.model;

import com.google.gson.Gson;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class ChatMessageDto {

    private static final Gson GSON = new Gson();
    private static final Set<String> VALID_TYPES = Set.of("TEXT", "JOIN", "LEAVE");

    private String messageId;
    private String userId;
    private String username;
    private String message;
    private String timestamp;
    private String messageType;

    // For Gson deserialization
    public ChatMessageDto() {
    }

    public static ChatMessageDto fromJson(String json) {
        return GSON.fromJson(json, ChatMessageDto.class);
    }

    public List<String> validate() {
        List<String> errors = new ArrayList<>();

        if (messageId == null || messageId.isBlank()) {
            errors.add("messageId is required");
        } else {
            try {
                UUID.fromString(messageId);
            } catch (IllegalArgumentException e) {
                errors.add("messageId must be a valid UUID");
            }
        }

        if (userId == null || userId.isBlank()) {
            errors.add("userId is required");
        } else {
            try {
                int id = Integer.parseInt(userId);
                if (id < 1 || id > 100000) {
                    errors.add("userId must be between 1 and 100000");
                }
            } catch (NumberFormatException e) {
                errors.add("userId must be a numeric string");
            }
        }

        if (username == null || !username.matches("^[a-zA-Z0-9]{3,20}$")) {
            errors.add("username must be 3-20 alphanumeric characters");
        }

        if (message == null || message.isEmpty() || message.length() > 500) {
            errors.add("message must be 1-500 characters");
        }

        if (timestamp == null || timestamp.isBlank()) {
            errors.add("timestamp is required");
        } else {
            try {
                Instant.parse(timestamp);
            } catch (DateTimeParseException e) {
                errors.add("timestamp must be valid ISO-8601");
            }
        }

        if (messageType == null || !VALID_TYPES.contains(messageType)) {
            errors.add("messageType must be one of: TEXT, JOIN, LEAVE");
        }

        return errors;
    }

    public String getMessageId() {
        return messageId;
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
}

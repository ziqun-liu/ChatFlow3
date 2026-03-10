package assign2.server.v2.controller;

import assign2.server.v2.model.ChatMessageDto;
import assign2.server.v2.model.ChatResponse;
import assign2.server.v2.model.QueueMessage;
import assign2.server.v2.model.UserInfo;
import assign2.server.v2.service.rabbitmq.ChannelPool;
import assign2.server.v2.service.MessagePublisher;
import java.util.concurrent.TimeoutException;
import assign2.server.v2.service.RedisSubscriber;
import assign2.server.v2.service.RoomManager;
import assign2.server.v2.service.UserManager;
import java.io.IOException;
import java.util.List;
import java.util.logging.Logger;
import javax.websocket.*;
import javax.websocket.server.PathParam;

/**
 * WebSocket server endpoint.
 * <p>
 * Assignment 2 responsibilities: onOpen/onClose  — maintain rooms map (used by RedisSubscriber)
 * onMessage       — validate, publish to RabbitMQ, ACK sender directly
 * <p>
 * Broadcast is triggered by RedisSubscriber when Consumer publishes to Redis Pub/Sub.
 */
@javax.websocket.server.ServerEndpoint("/chat/{roomId}")
public class ServerEndpoint {

  private static final Logger logger = Logger.getLogger(ServerEndpoint.class.getName());

  // Lazily initialized on first message — avoids blocking Tomcat startup if RabbitMQ is down.
  private static volatile MessagePublisher publisher;

  // RedisSubscriber: single instance per JVM, started once on first WebSocket connection.
  private static final RedisSubscriber redisSubscriber = new RedisSubscriber();
  private static volatile boolean redisStarted = false;

  // SERVER_ID identifies this instance in multi-server deployments (set via env var or default)
  private static final String SERVER_ID =
      System.getenv("SERVER_ID") != null ? System.getenv("SERVER_ID") : "server-1";

  @OnOpen
  public void onOpen(Session session, @PathParam("roomId") String roomId) {
    RoomManager.addSession(roomId, session);
    logger.fine("Session opened: session=" + session.getId() + ", room=" + roomId);
    startRedisSubscriberOnce();
  }

  @OnMessage
  public void onMessage(String message, Session session, @PathParam("roomId") String roomId) {
    logger.fine("Message received: session=" + session.getId() + ", room=" + roomId);

    // 1. Deserialize
    ChatMessageDto chatMsg;
    try {
      chatMsg = ChatMessageDto.fromJson(message);
    } catch (Exception e) {
      sendError(session, null, "Invalid JSON format: " + e.getMessage());
      return;
    }

    // 2. Validate
    List<String> errors = chatMsg.validate();
    if (!errors.isEmpty()) {
      sendError(session, chatMsg.getMessageId(), String.join("; ", errors));
      return;
    }

    // 2b. JOIN enforcement — TEXT/LEAVE require a prior JOIN in this session
    String messageType = chatMsg.getMessageType();
    boolean hasJoined = Boolean.TRUE.equals(session.getUserProperties().get("joined"));
    if (!hasJoined && ("TEXT".equals(messageType) || "LEAVE".equals(messageType))) {
      sendError(session, chatMsg.getMessageId(),
          "Must JOIN the room before sending " + messageType);
      return;
    }
    if ("JOIN".equals(messageType)) {
      session.getUserProperties().put("joined", true);
    }

    // 3. Register user on first message (userId not available at onOpen)
    session.getUserProperties().putIfAbsent("userId", chatMsg.getUserId());
    UserManager.register(chatMsg.getUserId(),
        new UserInfo(chatMsg.getUserId(), chatMsg.getUsername(), roomId, session,
            System.currentTimeMillis()));

    // 4. Publish to RabbitMQ (with publisher confirms)
    String clientIp = session.getUserProperties()
        .getOrDefault("javax.websocket.endpoint.remoteAddress", "unknown").toString();
    QueueMessage queueMsg = new QueueMessage(chatMsg, roomId, SERVER_ID, clientIp);

    boolean confirmed = getPublisher().publish(queueMsg);

    // 5. ACK back to sender — only after RabbitMQ confirms receipt
    if (confirmed) {
      logger.info(
          "roomId=" + roomId + ",messageType=" + messageType + ",messageId=" + chatMsg.getMessageId() + " gets broadcasted");
      sendAck(session, chatMsg.getMessageId());
    } else {
      sendError(session, chatMsg.getMessageId(), "Failed to publish message, please retry");
    }
  }

  @OnClose
  public void onClose(Session session, @PathParam("roomId") String roomId, CloseReason reason) {
    String userId = (String) session.getUserProperties().get("userId");
    if (userId != null) {
      UserManager.remove(userId);
    }
    RoomManager.removeSession(roomId, session);
    logger.fine(
        "Session closed: session=" + session.getId() + ", room=" + roomId + ", reason=" + reason);
  }

  @OnError
  public void onError(Session session, Throwable exception) {
    logger.severe(
        "Transport error: session=" + session.getId() + ", error=" + exception.getMessage());
  }

  // ── Helpers ──────────────────────────────────────────────────────────────

  private void sendAck(Session session, String messageId) {
    synchronized (session) {
      try {
        session.getBasicRemote().sendText(ChatResponse.ack(messageId).toJson());
      } catch (IOException e) {
        logger.warning("Failed to send ACK for messageId=" + messageId + ": " + e.getMessage());
      }
    }
  }

  private void sendError(Session session, String messageId, String errorMsg) {
    synchronized (session) {
      try {
        ChatResponse errResp = messageId != null ? ChatResponse.error(messageId, errorMsg)
            : ChatResponse.error(errorMsg);
        session.getBasicRemote().sendText(errResp.toJson());
      } catch (IOException e) {
        logger.warning("Failed to send error response: " + e.getMessage());
      }
    }
  }

  private static void startRedisSubscriberOnce() {
    if (!redisStarted) {
      synchronized (ServerEndpoint.class) {
        if (!redisStarted) {
          redisSubscriber.start();
          redisStarted = true;
        }
      }
    }
  }

  /** Called by AppLifecycle on WAR undeploy to release RabbitMQ + Redis resources. */
  public static void shutdown() {
    redisSubscriber.stop();
    try {
      ChannelPool.getInstance().shutdown();
    } catch (IOException | TimeoutException e) {
      logger.warning("Error shutting down ChannelPool: " + e.getMessage());
    }
  }

  private MessagePublisher getPublisher() {
    if (publisher == null) {
      synchronized (ServerEndpoint.class) {
        if (publisher == null) {
          try {
            publisher = new MessagePublisher(ChannelPool.getInstance());
            logger.info("MessagePublisher initialized.");
          } catch (Exception e) {
            throw new RuntimeException("Failed to initialize RabbitMQ publisher", e);
          }
        }
      }
    }
    return publisher;
  }
}

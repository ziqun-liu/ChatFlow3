package assign2.server.v2.websocket;

import assign2.server.v2.model.ChatMessageDto;
import assign2.server.v2.model.ChatResponse;
import java.util.List;
import java.util.logging.Logger;
import javax.websocket.*;
import javax.websocket.server.PathParam;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;


/**
 * rooms is a map, whose key is {roomId} and value is a set of sessions. `Session session` is
 * injected by the websocket container (Tomcat) when a connection is established `String message` is
 * the content of `session.getBasicRemote().sendText` in the client, and is injected by the
 * container
 */
@javax.websocket.server.ServerEndpoint("/chat/{roomId}")
public class ServerEndpoint {

  private static final Logger logger = Logger.getLogger(ServerEndpoint.class.getName());
  private static final ConcurrentHashMap<String, Set<Session>> rooms = new ConcurrentHashMap<>();

  @OnOpen
  public void onOpen(Session session, @PathParam("roomId") String roomId) {

    logger.info("\nnumber of rooms=" + rooms.size());

    // Use `roomId` as key and get the value, a Set. Add the current session to the map.
    // If `roomId` is not present in map, create a new Set and add the current session
    this.rooms.computeIfAbsent(roomId, k -> ConcurrentHashMap.newKeySet()).add(session);
    logger.info("Session opened: session=" + session.getId() + ", room=" + roomId);

  }

  /**
   * Server onMessage is invoked when the client `session.getBasicRemote().sendText`
   */
  @OnMessage
  public void onMessage(String message, Session session, @PathParam("roomId") String roomId) {

    logger.info("message received: session=" + session.getId() + ", room=" + roomId);

    ChatMessageDto chatMsg;

    // Serialize
    try {
      chatMsg = ChatMessageDto.fromJson(message);
    } catch (Exception e) {
      try {
        session.getBasicRemote()
            .sendText(new ChatResponse(List.of("Invalid JSON format: " + e.getMessage())).toJson());
      } catch (IOException ioe) {
        logger.warning("Failed to send error response: " + ioe.getMessage());
      }
      return;
    }

    // Validate
    List<String> errors = chatMsg.validate();
    if (!errors.isEmpty()) {
      try {
        session.getBasicRemote().sendText(new ChatResponse(errors).toJson());
      } catch (IOException ioe) {
        logger.warning("Failed to send validation error response: " + ioe.getMessage());
      }
      return;
    }

    // One session receives a message and broadcasts it to all sessions in the same room.
    // Note that this is to broadcast back to the client.
    // isOpen() is checked inside synchronized to avoid check-then-act race.
    for (Session s : rooms.get(roomId)) {
      synchronized (s) {
        if (s.isOpen()) {
          try {
            s.getBasicRemote().sendText(new ChatResponse(chatMsg).toJson());
          } catch (IOException e) {
            logger.warning("Failed to send to session " + s.getId() + ": " + e.getMessage());
          }
        }
      }
    }

  }

  @OnClose
  public void onClose(Session session, @PathParam("roomId") String roomId, CloseReason reason) {

    rooms.getOrDefault(roomId, Set.of()).remove(session);
    logger.info("Session closed: session=" + session.getId() + ", room=" + roomId + ", " + reason);

  }

  @OnError
  public void onError(Session session, Throwable exception) {

    logger.severe(
        "Transport error: session=" + session.getId() + ", error=" + exception.getMessage());
  }
}

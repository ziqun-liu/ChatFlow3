package assign2.server.v2.service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.websocket.Session;

/**
 * Manages the mapping of roomId to active WebSocket sessions.
 * Used by ServerEndpoint (onOpen/onClose) and RedisSubscriber (broadcast).
 */
public class RoomManager {

  private static final ConcurrentHashMap<String, Set<Session>> rooms = new ConcurrentHashMap<>();

  public static void addSession(String roomId, Session session) {
    rooms.computeIfAbsent(roomId, k -> ConcurrentHashMap.newKeySet()).add(session);
  }

  public static void removeSession(String roomId, Session session) {
    rooms.computeIfPresent(roomId, (k, sessions) -> {
      sessions.remove(session);
      return sessions.isEmpty() ? null : sessions;  // null removes the map entry
    });
  }

  public static Set<Session> getSessions(String roomId) {
    return rooms.get(roomId);
  }

  private RoomManager() {}
}

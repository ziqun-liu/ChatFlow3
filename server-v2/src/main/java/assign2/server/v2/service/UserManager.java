package assign2.server.v2.service;

import assign2.server.v2.model.UserInfo;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Maintains the userId → UserInfo mapping for all currently connected users.
 *
 * Lifecycle:
 *   register() — called in ServerEndpoint.onMessage() on first message per session
 *   remove()   — called in ServerEndpoint.onClose()
 *
 * Note: userId is not available at WebSocket onOpen (it arrives in the first ChatMessageDto),
 * so registration is deferred to the first message. Sessions that connect but never send
 * a message will not appear in this map.
 */
public class UserManager {

  private static final Logger logger = Logger.getLogger(UserManager.class.getName());

  // userId → UserInfo
  private static final ConcurrentHashMap<String, UserInfo> activeUsers = new ConcurrentHashMap<>();

  private UserManager() {}

  /** Registers or updates the UserInfo for a userId. */
  public static void register(String userId, UserInfo info) {
    activeUsers.put(userId, info);
    logger.fine("User registered: userId=" + userId
        + ", username=" + info.getUsername() + ", room=" + info.getRoomId());
  }

  /** Removes the UserInfo for a userId on disconnect. */
  public static void remove(String userId) {
    UserInfo removed = activeUsers.remove(userId);
    if (removed != null) {
      logger.fine("User removed: userId=" + userId + ", room=" + removed.getRoomId());
    }
  }

  /** Returns the UserInfo for a userId, or null if not online. */
  public static UserInfo get(String userId) {
    return activeUsers.get(userId);
  }

  /** Returns the set of userIds currently in a given room. */
  public static Set<String> getUserIdsInRoom(String roomId) {
    Set<String> result = new HashSet<>();
    for (UserInfo info : activeUsers.values()) {
      if (roomId.equals(info.getRoomId())) {
        result.add(info.getUserId());
      }
    }
    return Collections.unmodifiableSet(result);
  }

  /** Returns the total number of online users across all rooms. */
  public static int getOnlineCount() {
    return activeUsers.size();
  }
}

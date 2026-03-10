package assign2.server.v2.model;

import javax.websocket.Session;

/**
 * Immutable snapshot of a connected user's identity and session context.
 * Registered in UserManager on the user's first message (userId is not
 * available at WebSocket onOpen — it arrives in the first ChatMessageDto).
 */
public final class UserInfo {

  private final String userId;
  private final String username;
  private final String roomId;
  private final Session session;
  private final long connectedAt; // System.currentTimeMillis() at registration

  public UserInfo(String userId, String username, String roomId,
      Session session, long connectedAt) {
    this.userId = userId;
    this.username = username;
    this.roomId = roomId;
    this.session = session;
    this.connectedAt = connectedAt;
  }

  public String getUserId()    { return userId; }
  public String getUsername()  { return username; }
  public String getRoomId()    { return roomId; }
  public Session getSession()  { return session; }
  public long getConnectedAt() { return connectedAt; }
}

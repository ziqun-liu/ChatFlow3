package assign2.client.connection;

import assign2.client.ClientEndpoint;
import assign2.client.metrics.Metrics;
import java.net.URI;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class ConnectionManager {

  private static final int CONCURRENCY = 50;

  private final String serverBaseUrl;
  private final Metrics metrics;
  private final ConcurrentHashMap<Integer, ClientEndpoint> connections = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<Integer, Semaphore> semaphores = new ConcurrentHashMap<>();

  public ConnectionManager(String serverBaseUrl, Metrics metrics) {
    this.serverBaseUrl = serverBaseUrl;
    this.metrics = metrics;
  }

  public ClientEndpoint conn(int roomId) throws Exception {
    ClientEndpoint endpoint = connections.get(roomId);
    if (endpoint != null) {
      return endpoint;
    }
    synchronized (this) {
      endpoint = connections.get(roomId);
      if (endpoint != null) {
        return endpoint;
      }
      endpoint = createConn(roomId);
      connections.put(roomId, endpoint);
      return endpoint;
    }
  }

  public void acquire(int roomId) throws InterruptedException {
    semaphore(roomId).acquire();
  }

  public void release(int roomId) {
    semaphore(roomId).release();
  }

  private Semaphore semaphore(int roomId) {
    return semaphores.computeIfAbsent(roomId, k -> new Semaphore(CONCURRENCY));
  }

  public synchronized void reconnect(int roomId) throws Exception {
    ClientEndpoint existing = connections.get(roomId);
    if (existing != null && existing.isOpen()) {
      return;
    }
    try {
      if (existing != null) {
        existing.close();
      }
    } catch (Exception ignored) {
    }
    metrics.recordReconnection();
    connections.put(roomId, createConn(roomId));
  }

  public void closeAll() {
    connections.values().forEach(ep -> {
      try {
        ep.closeBlocking();
      } catch (Exception ignored) {
      }
    });
    connections.clear();
  }

  private ClientEndpoint createConn(int roomId) throws Exception {
    URI uri = new URI(serverBaseUrl + roomId);
    ClientEndpoint endpoint = new ClientEndpoint(uri);
    boolean connected = endpoint.connectBlocking(10, TimeUnit.SECONDS);
    if (!connected) {
      metrics.recordConnectionFailure();
      throw new RuntimeException("Failed to connect to " + serverBaseUrl + roomId);
    }
    metrics.recordConnection();
    return endpoint;
  }
}

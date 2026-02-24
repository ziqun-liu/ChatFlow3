package assign2.client;

import java.net.URI;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

public class ClientEndpoint extends WebSocketClient {

  private final ArrayBlockingQueue<String> ackQueue = new ArrayBlockingQueue<>(50);

  public ClientEndpoint(URI serverUri) {
    super(serverUri);
  }

  @Override
  public void onOpen(ServerHandshake handshakedata) {
  }

  @Override
  public void onMessage(String message) {
    ackQueue.offer(message);
  }

  @Override
  public void onClose(int code, String reason, boolean remote) {
    ackQueue.clear();
  }

  @Override
  public void onError(Exception ex) {
    System.err.println("[WS Error] " + ex.getMessage());
    ackQueue.clear();
  }

  public String sendAndAwaitAck(String json, long timeoutMs) throws InterruptedException {
    send(json);
    return ackQueue.poll(timeoutMs, TimeUnit.MILLISECONDS);
  }
}

package assign2.consumer.v3.service.db;

import assign2.consumer.v3.model.QueueMessage;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Singleton bounded queue between RabbitMQ consumer threads (producers)
 * and DbBatchWriter threads (consumers).
 * Capacity 100K prevents OOM under sustained backpressure.
 */
public class MessageBatchQueue {

  private static final int CAPACITY = 100_000;

  private static final MessageBatchQueue INSTANCE = new MessageBatchQueue();
  public static MessageBatchQueue getInstance() { return INSTANCE; }

  private final LinkedBlockingQueue<QueueMessage> queue = new LinkedBlockingQueue<>(CAPACITY);

  private MessageBatchQueue() {}

  /** Non-blocking — returns false if queue is full (message is dropped, not blocking consumers). */
  public boolean offer(QueueMessage msg) {
    return queue.offer(msg);
  }

  /** Drains up to maxElements into the provided list. Returns number of elements drained. */
  public int drainTo(java.util.List<QueueMessage> buffer, int maxElements) {
    return queue.drainTo(buffer, maxElements);
  }

  public int size() {
    return queue.size();
  }
}
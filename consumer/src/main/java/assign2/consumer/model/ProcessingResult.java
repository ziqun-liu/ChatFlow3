package assign2.consumer.model;

/**
 * Result of Layer 2 (MessageProcessor) passed to Layer 3 (DeliveryHandler).
 * Carries either a successfully parsed QueueMessage or a MALFORMED signal,
 * plus the receipt timestamp for end-to-end latency tracking.
 */
public final class ProcessingResult {

  public enum Status { SUCCESS, MALFORMED }

  private final Status status;
  private final QueueMessage message; // null when MALFORMED
  private final long receivedAt;      // System.nanoTime() at Layer 1 receipt

  private ProcessingResult(Status status, QueueMessage message, long receivedAt) {
    this.status = status;
    this.message = message;
    this.receivedAt = receivedAt;
  }

  public static ProcessingResult success(QueueMessage msg, long receivedAt) {
    return new ProcessingResult(Status.SUCCESS, msg, receivedAt);
  }

  public static ProcessingResult malformed(long receivedAt) {
    return new ProcessingResult(Status.MALFORMED, null, receivedAt);
  }

  public Status getStatus() {
    return status;
  }

  /** Returns the parsed message. Only valid when getStatus() == SUCCESS. */
  public QueueMessage getMessage() {
    return message;
  }

  /** Returns the System.nanoTime() timestamp when the delivery was received in Layer 1. */
  public long getReceivedAt() {
    return receivedAt;
  }
}

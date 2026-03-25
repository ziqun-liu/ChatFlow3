package assign2.consumer.v3.model;

public final class ProcessingResult {

  public enum Status { SUCCESS, MALFORMED }

  private final Status status;
  private final QueueMessage message;
  private final long receivedAt;

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

  public Status getStatus()       { return status; }
  public QueueMessage getMessage(){ return message; }
  public long getReceivedAt()     { return receivedAt; }
}
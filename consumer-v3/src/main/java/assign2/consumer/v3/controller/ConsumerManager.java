package assign2.consumer.v3.controller;

import assign2.consumer.v3.config.RabbitMQConfig;
import assign2.consumer.v3.service.RedisPublisher;
import assign2.consumer.v3.service.db.DbBatchWriter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Creates and manages RoomConsumer threads and the DbBatchWriter pool.
 * DbBatchWriter is started before consumers and stopped after consumers shut down.
 */
public class ConsumerManager {

  private static final Logger logger = Logger.getLogger(ConsumerManager.class.getName());

  private final int numThreads;
  private final RedisPublisher redisPublisher;
  private final ExecutorService executor;
  private final List<RoomConsumer> consumers = new ArrayList<>();
  private final DbBatchWriter dbBatchWriter;

  public ConsumerManager(int numThreads, RedisPublisher redisPublisher) {
    this.numThreads = numThreads;
    this.redisPublisher = redisPublisher;
    this.executor = Executors.newFixedThreadPool(numThreads);
    this.dbBatchWriter = new DbBatchWriter();
  }

  public void start() {
    // Start DB writers first so the queue is ready before consumers begin producing
    dbBatchWriter.start();

    List<List<String>> roomsPerThread = new ArrayList<>();
    for (int i = 0; i < numThreads; i++) {
      roomsPerThread.add(new ArrayList<>());
    }
    for (int roomNum = 1; roomNum <= RabbitMQConfig.ROOM_COUNT; roomNum++) {
      int threadIndex = (roomNum - 1) % numThreads;
      roomsPerThread.get(threadIndex).add(String.valueOf(roomNum));
    }

    for (int i = 0; i < numThreads; i++) {
      List<String> assignedRooms = roomsPerThread.get(i);
      RoomConsumer consumer = new RoomConsumer(assignedRooms, redisPublisher);
      consumers.add(consumer);
      executor.submit(consumer);
      logger.info("Started consumer thread-" + i + " for rooms=" + assignedRooms);
    }

    logger.info("ConsumerManager started: " + numThreads + " threads, "
        + RabbitMQConfig.ROOM_COUNT + " rooms total.");
  }

  public void shutdown() {
    logger.info("Shutting down ConsumerManager...");
    consumers.forEach(RoomConsumer::stop);
    executor.shutdown();
    try {
      if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
        executor.shutdownNow();
        logger.warning("ConsumerManager forced shutdown after timeout.");
      } else {
        logger.info("ConsumerManager consumers shut down cleanly.");
      }
    } catch (InterruptedException e) {
      executor.shutdownNow();
      Thread.currentThread().interrupt();
    }
    // Stop DB writers after consumers so any remaining queued messages get flushed
    dbBatchWriter.shutdown();
  }
}

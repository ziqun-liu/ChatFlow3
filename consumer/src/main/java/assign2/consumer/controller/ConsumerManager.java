package assign2.consumer.controller;

import assign2.consumer.config.RabbitMQConfig;
import assign2.consumer.service.RedisPublisher;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Creates and manages a pool of RoomConsumer threads. Distributes the 20 room queues evenly across
 * all consumer threads.
 * <p>
 * Example: 4 threads, 20 rooms thread-0 → room.1,  room.5,  room.9,  room.13, room.17 thread-1 →
 * room.2,  room.6,  room.10, room.14, room.18 thread-2 → room.3,  room.7,  room.11, room.15,
 * room.19 thread-3 → room.4,  room.8,  room.12, room.16, room.20
 * <p>
 * Round-robin distribution ensures even load even if some rooms are hotter than others.
 */
public class ConsumerManager {

  private static final Logger logger = Logger.getLogger(ConsumerManager.class.getName());

  private final int numThreads;
  private final RedisPublisher redisPublisher;
  private final ExecutorService executor;
  private final List<RoomConsumer> consumers = new ArrayList<>();

  public ConsumerManager(int numThreads, RedisPublisher redisPublisher) {
    this.numThreads = numThreads;
    this.redisPublisher = redisPublisher;
    this.executor = Executors.newFixedThreadPool(numThreads);
  }

  /**
   * Distributes rooms across threads (round-robin) and starts all RoomConsumer threads.
   */
  public void start() {
    // Build per-thread room lists via round-robin
    List<List<String>> roomsPerThread = new ArrayList<>();
    for (int i = 0; i < this.numThreads; i++) {
      roomsPerThread.add(new ArrayList<>());
      // ex. numThread = 4
      //    roomsPerThread =
      //    [
      //      [], // thread 0
      //      [], // thread 1
      //      [], // thread 2
      //      []  // thread 3
      //    ]
    }

    for (int roomNum = 1; roomNum <= RabbitMQConfig.ROOM_COUNT; roomNum++) {
      String roomId = String.valueOf(roomNum);
      int threadIndex = (roomNum - 1) % this.numThreads;
      roomsPerThread.get(threadIndex).add(roomId);
      // Suppose there are 4 threads and 20 rooms:
      //    thread 0: 1, 5, 9, 13, 17
      //    thread 1: 2, 6, 10, 14, 18
      //    thread 2: 3, 7, 11, 15, 19
      //    thread 3: 4, 8, 12, 16, 20
    }

    // Start one RoomConsumer per thread
    for (int i = 0; i < numThreads; i++) {
      List<String> assignedRooms = roomsPerThread.get(i);
      RoomConsumer consumer = new RoomConsumer(assignedRooms, this.redisPublisher);
      this.consumers.add(consumer);
      this.executor.submit(consumer);
      logger.info("Started consumer thread-" + i + " for rooms=" + assignedRooms);
    }

    logger.info(
        "ConsumerManager started: " + this.numThreads + " threads, " + RabbitMQConfig.ROOM_COUNT
            + " rooms total.");
  }

  /**
   * Signals all consumers to stop and waits for clean shutdown.
   */
  public void shutdown() {
    logger.info("Shutting down ConsumerManager...");
    this.consumers.forEach(RoomConsumer::stop);  // gracefully stop
    this.executor.shutdown();
    try {
      if (!this.executor.awaitTermination(10, TimeUnit.SECONDS)) {
        this.executor.shutdownNow();
        logger.warning("ConsumerManager forced shutdown after timeout.");
      } else {
        logger.info("ConsumerManager shut down cleanly.");
      }
    } catch (InterruptedException e) {
      this.executor.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }
}

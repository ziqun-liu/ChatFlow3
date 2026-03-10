package assign2.consumer.controller;

import assign2.consumer.config.RabbitMQConfig;
import assign2.consumer.model.ProcessingResult;
import assign2.consumer.service.DeliveryHandler;
import assign2.consumer.service.MessageProcessor;
import assign2.consumer.service.RedisPublisher;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;
import java.io.IOException;
import java.util.List;
import java.util.logging.Logger;

/**
 * Layer 1 — 投递适配 (Delivery Adapter).
 * A Runnable that owns the RabbitMQ connection lifecycle for a set of rooms.
 * On each delivery it delegates to MessageProcessor (Layer 2) and DeliveryHandler (Layer 3).
 *
 * Delivery guarantee:
 *   - basicAck after successful Redis publish
 *   - basicNack (requeue=true) on first Redis failure, discard on redeliver
 */
public class RoomConsumer implements Runnable {

  private static final Logger logger = Logger.getLogger(RoomConsumer.class.getName());
  private static final int PREFETCH_COUNT = 10;

  private final List<String> roomIds;
  private final MessageProcessor messageProcessor;
  private final DeliveryHandler deliveryHandler;
  private volatile boolean running = true;

  public RoomConsumer(List<String> roomIds, RedisPublisher redisPublisher) {
    this.roomIds = roomIds;
    this.messageProcessor = new MessageProcessor();
    this.deliveryHandler = new DeliveryHandler(redisPublisher);
  }

  @Override
  public void run() {
    while (running) {
      try {
        runOnce();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      } catch (Exception e) {
        logger.log(java.util.logging.Level.SEVERE,
            "RoomConsumer crashed, restarting in 5s: rooms=" + roomIds, e);
        try { Thread.sleep(5000); } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          break;
        }
      }
    }
  }

  private void runOnce() throws Exception {
    ConnectionFactory factory = new ConnectionFactory();
    factory.setHost(RabbitMQConfig.HOST);
    factory.setPort(RabbitMQConfig.PORT);
    factory.setUsername(RabbitMQConfig.USERNAME);
    factory.setPassword(RabbitMQConfig.PASSWORD);
    factory.setAutomaticRecoveryEnabled(true);  // auto-reconnect on network failure

    try (Connection connection = factory.newConnection(
        "consumer-" + this.roomIds); Channel channel = connection.createChannel()) {

      // Limit unacknowledged messages in-flight per consumer thread
      channel.basicQos(PREFETCH_COUNT);

      // Declare queues and register push-based consumer for each assigned room
      for (String roomId : this.roomIds) {
        String queueName = RabbitMQConfig.queueName(roomId);

        // Queues and bindings are pre-configured by rabbitmq-setup.sh.
        // Use passive declare to verify existence without modifying parameters.
        channel.queueDeclarePassive(queueName);

        // Layer 1: extract raw delivery and delegate to Layer 2 + Layer 3
        channel.basicConsume(queueName, false, new DefaultConsumer(channel) {
          @Override
          public void handleDelivery(String consumerTag, Envelope envelope,
              AMQP.BasicProperties properties, byte[] body) throws IOException {
            long receivedAt = System.nanoTime();
            ProcessingResult result = messageProcessor.process(body, receivedAt);
            deliveryHandler.handle(channel, envelope, result);
          }
        });

        logger.info("Consuming queue=" + queueName + " (room=" + roomId + ")");
      }

      // Block until shutdown signal
      while (running && connection.isOpen()) {
        Thread.sleep(500);
      }
    }
  }

  public void stop() {
    running = false;
  }
}

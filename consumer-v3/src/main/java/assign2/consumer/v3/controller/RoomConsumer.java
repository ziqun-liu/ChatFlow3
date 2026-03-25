package assign2.consumer.v3.controller;

import assign2.consumer.v3.config.RabbitMQConfig;
import assign2.consumer.v3.model.ProcessingResult;
import assign2.consumer.v3.service.DeliveryHandler;
import assign2.consumer.v3.service.MessageProcessor;
import assign2.consumer.v3.service.RedisPublisher;
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
 * Layer 1 — Delivery Adapter.
 * Owns RabbitMQ connection lifecycle for a set of rooms.
 * On each delivery, delegates to MessageProcessor (Layer 2) and DeliveryHandler (Layer 3).
 */
public class RoomConsumer implements Runnable {

  private static final Logger logger = Logger.getLogger(RoomConsumer.class.getName());

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
        logger.severe("RoomConsumer crashed, restarting in 5s: rooms=" + roomIds + " error=" + e.getMessage());
        try { Thread.sleep(5000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
      }
    }
  }

  private void runOnce() throws Exception {
    ConnectionFactory factory = new ConnectionFactory();
    factory.setHost(RabbitMQConfig.HOST);
    factory.setPort(RabbitMQConfig.PORT);
    factory.setUsername(RabbitMQConfig.USERNAME);
    factory.setPassword(RabbitMQConfig.PASSWORD);
    factory.setAutomaticRecoveryEnabled(true);

    try (Connection connection = factory.newConnection("consumer-v3-" + roomIds);
         Channel channel = connection.createChannel()) {

      channel.basicQos(RabbitMQConfig.PREFETCH_COUNT);

      for (String roomId : roomIds) {
        String queueName = RabbitMQConfig.queueName(roomId);
        channel.queueDeclarePassive(queueName);
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

      while (running && connection.isOpen()) {
        Thread.sleep(500);
      }
    }
  }

  public void stop() { running = false; }
}
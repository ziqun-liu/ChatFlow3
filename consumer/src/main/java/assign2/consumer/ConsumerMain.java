package assign2.consumer;

import assign2.consumer.config.RabbitMQConfig;
import assign2.consumer.config.RedisConfig;
import assign2.consumer.controller.ConsumerManager;
import assign2.consumer.service.ConsumerMetrics;
import assign2.consumer.service.RedisPublisher;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Entry point for the Consumer service.
 * <p>
 * Reads config.properties, constructs RedisPublisher and ConsumerManager, starts consuming, and
 * registers a JVM shutdown hook for graceful exit.
 * <p>
 * Run: java -jar consumer.jar Override config via env vars: RABBITMQ_HOST, REDIS_HOST, REDIS_PORT,
 * CONSUMER_THREADS
 */
public class ConsumerMain {

  private static final Logger logger = Logger.getLogger(ConsumerMain.class.getName());
  private static final String PROPERTIES_FILE = "config.properties";

  public static void main(String[] args) throws Exception {
    Properties props = loadProperties();

    // Number of consumer threads
    String threadsRaw = resolve("CONSUMER_THREADS", props, "consumer.threads", "10");
    int numThreads = Integer.parseInt(threadsRaw);

    logger.info("ConsumerMain starting: threads=" + numThreads + ", redis=" + RedisConfig.HOST + ":"
        + RedisConfig.PORT + ", rabbitmq=" + RabbitMQConfig.HOST + ":" + RabbitMQConfig.PORT);

    RedisPublisher redisPublisher = new RedisPublisher();
    ConsumerManager conMgr = new ConsumerManager(numThreads, redisPublisher);

    // Graceful shutdown on SIGTERM or Ctrl+C
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      logger.info("Shutdown signal received, stopping ConsumerManager...");
      conMgr.shutdown();
      redisPublisher.close();
      ConsumerMetrics.getInstance().stopReporting();
    }, "shutdown-hook"));

    ConsumerMetrics.getInstance().startReporting();
    conMgr.start();  // ======== START =========

    // Keep main thread alive — ConsumerManager threads do the work
    Thread.currentThread().join();
  }

  // ── Helpers ──────────────────────────────────────────────────────────────

  private static String resolve(String envKey, Properties props, String propKey,
      String defaultValue) {
    String envVal = System.getenv(envKey);
    if (envVal != null && !envVal.isEmpty()) {
      return envVal;
    }
    String propVal = props.getProperty(propKey);
    if (propVal != null && !propVal.isEmpty()) {
      return propVal;
    }
    return defaultValue;
  }

  private static Properties loadProperties() {
    Properties props = new Properties();
    try (InputStream is = ConsumerMain.class.getClassLoader()
        .getResourceAsStream(PROPERTIES_FILE)) {
      if (is != null) {
        props.load(is);
      } else {
        logger.warning(PROPERTIES_FILE + " not found, using env vars or defaults.");
      }
    } catch (IOException e) {
      logger.warning("Failed to load " + PROPERTIES_FILE + ": " + e.getMessage());
    }
    return props;
  }
}

package assign2.consumer.v3;

import assign2.consumer.v3.config.RabbitMQConfig;
import assign2.consumer.v3.config.RedisConfig;
import assign2.consumer.v3.controller.ConsumerManager;
import assign2.consumer.v3.service.ConsumerMetrics;
import assign2.consumer.v3.service.RedisPublisher;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Entry point for consumer-v3.
 * Adds MySQL persistence via DbBatchWriter (managed inside ConsumerManager).
 * Run: java -jar consumer-v3.jar
 * DB config via env vars: DB_HOST, DB_PORT, DB_NAME, DB_USER, DB_PASS
 */
public class ConsumerMain {

  private static final Logger logger = Logger.getLogger(ConsumerMain.class.getName());
  private static final String PROPERTIES_FILE = "config.properties";

  public static void main(String[] args) throws Exception {
    Properties props = loadProperties();

    String threadsRaw = resolve("CONSUMER_THREADS", props, "consumer.threads", "10");
    int numThreads = Integer.parseInt(threadsRaw);

    logger.info("ConsumerMain (v3) starting: threads=" + numThreads
        + ", redis=" + RedisConfig.HOST + ":" + RedisConfig.PORT
        + ", rabbitmq=" + RabbitMQConfig.HOST + ":" + RabbitMQConfig.PORT);

    RedisPublisher redisPublisher = new RedisPublisher();
    ConsumerManager conMgr = new ConsumerManager(numThreads, redisPublisher);

    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      logger.info("Shutdown signal received, stopping ConsumerManager...");
      conMgr.shutdown();
      redisPublisher.close();
      ConsumerMetrics.getInstance().stopReporting();
    }, "shutdown-hook"));

    ConsumerMetrics.getInstance().startReporting();
    conMgr.start();

    Thread.currentThread().join();
  }

  private static String resolve(String envKey, Properties props, String propKey, String defaultValue) {
    String envVal = System.getenv(envKey);
    if (envVal != null && !envVal.isEmpty()) return envVal;
    String propVal = props.getProperty(propKey);
    if (propVal != null && !propVal.isEmpty()) return propVal;
    return defaultValue;
  }

  private static Properties loadProperties() {
    Properties props = new Properties();
    try (InputStream is = ConsumerMain.class.getClassLoader().getResourceAsStream(PROPERTIES_FILE)) {
      if (is != null) props.load(is);
      else logger.warning(PROPERTIES_FILE + " not found, using env vars or defaults.");
    } catch (IOException e) {
      logger.warning("Failed to load " + PROPERTIES_FILE + ": " + e.getMessage());
    }
    return props;
  }
}

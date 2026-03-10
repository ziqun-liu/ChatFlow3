package assign2.server.v2.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * RabbitMQ configuration loader.
 * Priority: env var > JVM system property (-D) > config.properties > hardcoded defaults.
 * Environment variables: RABBITMQ_HOST, RABBITMQ_PORT, RABBITMQ_USER, RABBITMQ_PASS
 * JVM properties:        RABBITMQ_HOST, RABBITMQ_PORT, RABBITMQ_USER, RABBITMQ_PASS
 *                        (set via CATALINA_OPTS=-DRABBITMQ_HOST=... for Tomcat deployments)
 */
public class RabbitMQConfig {

  private static final Logger logger = Logger.getLogger(RabbitMQConfig.class.getName());
  private static final String PROPERTIES_FILE = "config.properties";

  public static final String HOST;
  public static final int PORT;
  public static final String USERNAME;
  public static final String PASSWORD;
  public static final String EXCHANGE;
  public static final String EXCHANGE_TYPE = "topic";
  public static final int ROOM_COUNT = 20;

  static {
    Properties props = loadProperties();

    HOST     = resolve("RABBITMQ_HOST", props, "rabbitmq.host", "localhost");
    PORT     = Integer.parseInt(resolve("RABBITMQ_PORT", props, "rabbitmq.port", "5672"));
    USERNAME = resolve("RABBITMQ_USER", props, "rabbitmq.user", "guest");
    PASSWORD = resolve("RABBITMQ_PASS", props, "rabbitmq.pass", "guest");
    EXCHANGE = resolve("RABBITMQ_EXCHANGE", props, "rabbitmq.exchange", "chat.exchange");

    logger.info("RabbitMQConfig loaded: host=" + HOST + ", port=" + PORT
        + ", exchange=" + EXCHANGE + ", user=" + USERNAME);
  }

  /**
   * Returns routing key for a given roomId, e.g. "room.3"
   */
  public static String routingKey(String roomId) {
    return "room." + roomId;
  }

  /**
   * Returns queue name for a given roomId, e.g. "room.3"
   */
  public static String queueName(String roomId) {
    return "room." + roomId;
  }

  // ── Helpers ────────────────────────────────────────────────────────────────

  private static String resolve(String envKey, Properties props,
      String propKey, String defaultValue) {
    String envVal = System.getenv(envKey);
    if (envVal != null && !envVal.isEmpty()) return envVal;
    String sysProp = System.getProperty(envKey);  // -DRABBITMQ_HOST=... via CATALINA_OPTS
    if (sysProp != null && !sysProp.isEmpty()) return sysProp;
    String propVal = props.getProperty(propKey);
    if (propVal != null && !propVal.isEmpty()) return propVal;
    return defaultValue;
  }

  private static Properties loadProperties() {
    Properties props = new Properties();
    try (InputStream is = RabbitMQConfig.class
        .getClassLoader().getResourceAsStream(PROPERTIES_FILE)) {
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

  private RabbitMQConfig() {}
}

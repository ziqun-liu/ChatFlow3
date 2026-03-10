package assign2.consumer.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Redis configuration loader.
 * Priority: environment variables > config.properties > hardcoded defaults.
 * Environment variables: REDIS_HOST, REDIS_PORT
 */
public class RedisConfig {

  private static final Logger logger = Logger.getLogger(RedisConfig.class.getName());
  private static final String PROPERTIES_FILE = "config.properties";

  public static final String HOST;
  public static final int PORT;

  static {
    Properties props = loadProperties();

    HOST = resolve("REDIS_HOST", props, "redis.host", "localhost");
    PORT = Integer.parseInt(resolve("REDIS_PORT", props, "redis.port", "6379"));

    logger.info("RedisConfig loaded: host=" + HOST + ", port=" + PORT);
  }

  // ── Helpers ────────────────────────────────────────────────────────────────

  private static String resolve(String envKey, Properties props,
      String propKey, String defaultValue) {
    String envVal = System.getenv(envKey);
    if (envVal != null && !envVal.isEmpty()) return envVal;
    String propVal = props.getProperty(propKey);
    if (propVal != null && !propVal.isEmpty()) return propVal;
    return defaultValue;
  }

  private static Properties loadProperties() {
    Properties props = new Properties();
    try (InputStream is = RedisConfig.class
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

  private RedisConfig() {}
}

package assign2.consumer.v3.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * MySQL configuration loader for consumer-v3.
 * Priority: environment variables > config.properties > hardcoded defaults.
 */
public class DbConfig {

  private static final Logger logger = Logger.getLogger(DbConfig.class.getName());
  private static final String PROPERTIES_FILE = "config.properties";

  public static final String HOST;
  public static final int PORT;
  public static final String NAME;
  public static final String USER;
  public static final String PASS;
  public static final int BATCH_SIZE;
  public static final int FLUSH_INTERVAL_MS;
  public static final int POOL_SIZE;
  public static final int WRITER_THREADS;

  static {
    Properties props = loadProperties();

    HOST             = resolve("DB_HOST",                props, "db.host",                "localhost");
    PORT             = Integer.parseInt(resolve("DB_PORT",     props, "db.port",                "3306"));
    NAME             = resolve("DB_NAME",                props, "db.name",                "chatflow");
    USER             = resolve("DB_USER",                props, "db.user",                "chatflow");
    PASS             = resolve("DB_PASS",                props, "db.pass",                "chatflow");
    BATCH_SIZE       = Integer.parseInt(resolve("DB_BATCH_SIZE",       props, "db.batch.size",        "500"));
    FLUSH_INTERVAL_MS= Integer.parseInt(resolve("DB_FLUSH_INTERVAL_MS",props, "db.flush.intervalMs",  "500"));
    POOL_SIZE        = Integer.parseInt(resolve("DB_POOL_SIZE",        props, "db.pool.size",          "10"));
    WRITER_THREADS   = Integer.parseInt(resolve("DB_WRITER_THREADS",   props, "db.writer.threads",     "3"));

    logger.info("DbConfig loaded: host=" + HOST + ":" + PORT + "/" + NAME
        + ", batchSize=" + BATCH_SIZE + ", flushIntervalMs=" + FLUSH_INTERVAL_MS
        + ", poolSize=" + POOL_SIZE + ", writerThreads=" + WRITER_THREADS);
  }

  public static String jdbcUrl() {
    return "jdbc:mysql://" + HOST + ":" + PORT + "/" + NAME
        + "?useSSL=false&allowPublicKeyRetrieval=true&rewriteBatchedStatements=true"
        + "&serverTimezone=UTC";
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
    try (InputStream is = DbConfig.class.getClassLoader().getResourceAsStream(PROPERTIES_FILE)) {
      if (is != null) props.load(is);
      else logger.warning(PROPERTIES_FILE + " not found, using env vars or defaults.");
    } catch (IOException e) {
      logger.warning("Failed to load " + PROPERTIES_FILE + ": " + e.getMessage());
    }
    return props;
  }

  private DbConfig() {}
}

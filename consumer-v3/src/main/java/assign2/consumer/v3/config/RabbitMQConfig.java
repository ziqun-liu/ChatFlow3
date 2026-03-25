package assign2.consumer.v3.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.logging.Logger;

public class RabbitMQConfig {

    private static final Logger logger = Logger.getLogger(RabbitMQConfig.class.getName());
    private static final String PROPERTIES_FILE = "config.properties";

    public static final String HOST;
    public static final int PORT;
    public static final String USERNAME;
    public static final String PASSWORD;
    public static final String EXCHANGE;
    public static final int PREFETCH_COUNT;
    public static final String EXCHANGE_TYPE = "topic";
    public static final int ROOM_COUNT = 20;
    public static final String DLX_EXCHANGE = "chat.dlx";
    public static final String DLQ_NAME = "room.dlq";

    static {
        Properties props = loadProperties();

        HOST = resolve("RABBITMQ_HOST", props, "rabbitmq.host", "localhost");
        PORT = Integer.parseInt(resolve("RABBITMQ_PORT", props, "rabbitmq.port", "5672"));
        USERNAME = resolve("RABBITMQ_USER", props, "rabbitmq.user", "guest");
        PASSWORD = resolve("RABBITMQ_PASS", props, "rabbitmq.pass", "guest");
        EXCHANGE = resolve("RABBITMQ_EXCHANGE", props, "rabbitmq.exchange", "chat.exchange");
        PREFETCH_COUNT = Integer.parseInt(resolve("RABBITMQ_PREFETCH", props, "rabbitmq.prefetch", "50"));

        logger.info("RabbitMQConfig loaded: host=" + HOST + ", port=" + PORT
                + ", exchange=" + EXCHANGE + ", user=" + USERNAME + ", prefetch=" + PREFETCH_COUNT);
    }

    public static String routingKey(String roomId) {
        return "room." + roomId;
    }

    public static String queueName(String roomId) {
        return "room." + roomId;
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
        try (InputStream is = RabbitMQConfig.class.getClassLoader().getResourceAsStream(PROPERTIES_FILE)) {
            if (is != null) props.load(is);
            else logger.warning(PROPERTIES_FILE + " not found, using env vars or defaults.");
        } catch (IOException e) {
            logger.warning("Failed to load " + PROPERTIES_FILE + ": " + e.getMessage());
        }
        return props;
    }

    private RabbitMQConfig() {
    }
}

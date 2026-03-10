package assign2.server.v2.controller;

import java.util.logging.Logger;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;

/**
 * Releases RabbitMQ and Redis resources when Tomcat undeploys the WAR.
 * Without this, each redeploy leaks 20 RabbitMQ channels + 1 Redis connection.
 */
@WebListener
public class AppLifecycle implements ServletContextListener {

  private static final Logger logger = Logger.getLogger(AppLifecycle.class.getName());

  @Override
  public void contextInitialized(ServletContextEvent sce) {
    logger.info("AppLifecycle: context initialized.");
  }

  @Override
  public void contextDestroyed(ServletContextEvent sce) {
    logger.info("AppLifecycle: context destroyed, shutting down resources...");
    ServerEndpoint.shutdown();
    logger.info("AppLifecycle: shutdown complete.");
  }
}

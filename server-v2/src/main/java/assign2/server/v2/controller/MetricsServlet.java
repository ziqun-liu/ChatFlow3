package assign2.server.v2.controller;

import assign2.server.v2.service.MetricsService;
import com.google.gson.Gson;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * GET /metrics?start=<ISO>&end=<ISO>&userId=<id>&roomId=<id>&topN=<n>
 * <p>
 * Returns JSON with results of all core queries and analytics queries.
 * Called by the client after the test ends.
 * <p>
 * All parameters except start/end are optional.
 * Defaults: topN=10, userId="1", roomId="1", start/end default to last 1 hour.
 */
@WebServlet("/metrics")
public class MetricsServlet extends HttpServlet {

    private static final Logger logger = Logger.getLogger(MetricsServlet.class.getName());
    private static final Gson GSON = new Gson();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json;charset=UTF-8");

        String start = req.getParameter("start");
        String end = req.getParameter("end");
        String userId = req.getParameter("userId");
        String roomId = req.getParameter("roomId");
        int topN;
        try {
            topN = Integer.parseInt(req.getParameter("topN"));
        } catch (Exception e) {
            topN = 10;
        }

        // Defaults: last 1 hour
        if (start == null)
            start = java.time.Instant.now().minusSeconds(3600).toString().replace("T", " ").replace("Z", "");
        if (end == null) end = java.time.Instant.now().toString().replace("T", " ").replace("Z", "");
        if (userId == null) userId = "1";
        if (roomId == null) roomId = "1";

        Map<String, Object> result = new LinkedHashMap<>();
        MetricsService svc = MetricsService.getInstance();

        try {
            // Core queries
            result.put("roomMessages", svc.getRoomMessages(roomId, start, end));
            result.put("userHistory", svc.getUserHistory(userId, start, end));
            result.put("activeUsers", svc.countActiveUsers(start, end));
            result.put("userRooms", svc.getUserRooms(userId));

            // Analytics
            result.put("messagesPerSecond", svc.messagesPerSecond());
            result.put("topActiveUsers", svc.topActiveUsers(topN));
            result.put("topActiveRooms", svc.topActiveRooms(topN));
            result.put("totalMessages", svc.totalMessages());

            result.put("queryWindow", Map.of("start", start, "end", end));

            resp.setStatus(HttpServletResponse.SC_OK);
            resp.getWriter().write(GSON.toJson(result));

        } catch (Exception e) {
            logger.severe("MetricsServlet error: " + e.getMessage());
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            resp.getWriter().write("{\"error\":\"" + e.getMessage() + "\"}");
        }
    }
}
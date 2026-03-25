package assign2.server.v2.service;

import assign2.server.v2.config.DbConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Executes all 4 core queries and 4 analytics queries for the Metrics API.
 * Uses a small HikariCP pool (default 5) — metrics endpoint is low-frequency.
 */
public class MetricsService {

    private static final Logger logger = Logger.getLogger(MetricsService.class.getName());

    private static final MetricsService INSTANCE = new MetricsService();

    public static MetricsService getInstance() {
        return INSTANCE;
    }

    private final HikariDataSource dataSource;

    private MetricsService() {
        HikariConfig config = new HikariConfig();
        config.setDriverClassName("com.mysql.cj.jdbc.Driver");
        config.setJdbcUrl(DbConfig.jdbcUrl());
        config.setUsername(DbConfig.USER);
        config.setPassword(DbConfig.PASS);
        config.setMaximumPoolSize(DbConfig.POOL_SIZE);
        config.setConnectionTimeout(5_000);
        config.setPoolName("metrics-pool");
        this.dataSource = new HikariDataSource(config);
        logger.info("MetricsService initialized.");
    }

    // ── Core Query 1: Room messages in time range ────────────────────────────────

    public List<Map<String, Object>> getRoomMessages(String roomId, String startTime, String endTime) throws SQLException {
        String sql = "SELECT message_id, room_id, user_id, username, message, message_type, " + "client_timestamp, server_id " + "FROM messages WHERE room_id=? AND client_timestamp BETWEEN ? AND ? " + "ORDER BY client_timestamp LIMIT 100";
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, roomId);
            ps.setString(2, startTime);
            ps.setString(3, endTime);
            return toList(ps.executeQuery());
        }
    }

    // ── Core Query 2: User message history (optional date range) ─────────────────

    public List<Map<String, Object>> getUserHistory(String userId, String startTime, String endTime) throws SQLException {
        String sql = "SELECT message_id, room_id, user_id, username, message, message_type, " + "client_timestamp, server_id FROM messages WHERE user_id=?";
        if (startTime != null && endTime != null) {
            sql += " AND client_timestamp BETWEEN ? AND ?";
        }
        sql += " ORDER BY client_timestamp DESC LIMIT 100";

        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId);
            if (startTime != null && endTime != null) {
                ps.setString(2, startTime);
                ps.setString(3, endTime);
            }
            return toList(ps.executeQuery());
        }
    }

    // ── Core Query 3: Count active users in time window ──────────────────────────

    public long countActiveUsers(String startTime, String endTime) throws SQLException {
        String sql = "SELECT COUNT(DISTINCT user_id) FROM messages " + "WHERE client_timestamp BETWEEN ? AND ?";
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, startTime);
            ps.setString(2, endTime);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    // ── Core Query 4: Rooms user participated in ─────────────────────────────────

    public List<Map<String, Object>> getUserRooms(String userId) throws SQLException {
        String sql = "SELECT room_id, MAX(client_timestamp) as last_activity " + "FROM messages WHERE user_id=? " + "GROUP BY room_id ORDER BY last_activity DESC";
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId);
            return toList(ps.executeQuery());
        }
    }

    // ── Analytics: Messages per second ───────────────────────────────────────────

    public List<Map<String, Object>> messagesPerSecond() throws SQLException {
        String sql = "SELECT DATE_FORMAT(client_timestamp,'%Y-%m-%d %H:%i:%s') as sec, " + "COUNT(*) as count FROM messages GROUP BY sec ORDER BY sec";
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            return toList(ps.executeQuery());
        }
    }

    // ── Analytics: Top N active users ────────────────────────────────────────────

    public List<Map<String, Object>> topActiveUsers(int n) throws SQLException {
        String sql = "SELECT user_id, username, COUNT(*) as msg_count " + "FROM messages GROUP BY user_id, username ORDER BY msg_count DESC LIMIT ?";
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, n);
            return toList(ps.executeQuery());
        }
    }

    // ── Analytics: Top N active rooms ────────────────────────────────────────────

    public List<Map<String, Object>> topActiveRooms(int n) throws SQLException {
        String sql = "SELECT room_id, COUNT(*) as msg_count " + "FROM messages GROUP BY room_id ORDER BY msg_count DESC LIMIT ?";
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, n);
            return toList(ps.executeQuery());
        }
    }

    // ── Analytics: Total message count ───────────────────────────────────────────

    public long totalMessages() throws SQLException {
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM messages")) {
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────

    private List<Map<String, Object>> toList(ResultSet rs) throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        ResultSetMetaData meta = rs.getMetaData();
        int cols = meta.getColumnCount();
        while (rs.next()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= cols; i++) {
                row.put(meta.getColumnLabel(i), rs.getObject(i));
            }
            rows.add(row);
        }
        return rows;
    }
}
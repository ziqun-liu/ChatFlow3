#!/bin/bash
# Queries key MySQL metrics for ChatFlow3 performance monitoring.
# Usage: MYSQL_HOST=... MYSQL_USER=chatflow MYSQL_PASS=... ./mysql-stats.sh

MYSQL_HOST="${MYSQL_HOST:-localhost}"
MYSQL_PORT="${MYSQL_PORT:-3306}"
MYSQL_USER="${MYSQL_USER:-chatflow}"
MYSQL_PASS="${MYSQL_PASS:-chatflow}"
DB_NAME="${DB_NAME:-chatflow}"

mysql -h"$MYSQL_HOST" -P"$MYSQL_PORT" -u"$MYSQL_USER" -p"$MYSQL_PASS" --batch <<EOF
-- Total messages persisted
SELECT COUNT(*) AS total_messages FROM ${DB_NAME}.messages;

-- Messages per room
SELECT room_id, COUNT(*) AS msg_count FROM ${DB_NAME}.messages GROUP BY room_id ORDER BY room_id;

-- Server status: queries/connections/locks
SHOW GLOBAL STATUS WHERE Variable_name IN (
  'Queries', 'Threads_connected', 'Threads_running',
  'Innodb_row_lock_waits', 'Innodb_row_lock_time_avg'
);

-- InnoDB buffer pool hit ratio (higher is better; target >99%)
SELECT
  FORMAT(
    (1 - Innodb_buffer_pool_reads / Innodb_buffer_pool_read_requests) * 100, 2
  ) AS buffer_pool_hit_pct
FROM (
  SELECT
    (SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Innodb_buffer_pool_reads') + 0      AS Innodb_buffer_pool_reads,
    (SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Innodb_buffer_pool_read_requests') + 0 AS Innodb_buffer_pool_read_requests
) t;

-- Active connections by user
SELECT user, db, COUNT(*) AS connections FROM information_schema.processlist
WHERE db='${DB_NAME}' GROUP BY user, db;
EOF
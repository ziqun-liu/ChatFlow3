# Database Design Document — ChatFlow3

## 1. Database Choice: MySQL 8 (InnoDB)

MySQL with the InnoDB storage engine was chosen for the following reasons:

- **ACID compliance** — message persistence requires durable, crash-safe writes; InnoDB's write-ahead log (redo log) guarantees durability without sacrificing throughput
- **Composite index support** — the two primary access patterns (room+time range, user+time range) map directly to InnoDB B-tree composite indexes, enabling index-only scans with no filesort
- **`INSERT IGNORE` idempotency** — InnoDB enforces unique constraints at the storage level, making duplicate suppression a zero-overhead operation at the application layer
- **HikariCP compatibility** — the most performant JDBC connection pool integrates natively with MySQL Connector/J
- **Operational familiarity** — well-understood tooling for monitoring (`SHOW STATUS`, `performance_schema`), backup (`mysqldump`, RDS snapshots), and cloud deployment (AWS RDS)

Alternatives considered: PostgreSQL (comparable, but MySQL's `INSERT IGNORE` syntax is simpler than `ON CONFLICT DO NOTHING` for this use case); Cassandra (better write scalability but no ACID, complex query model for analytics).

---

## 2. Schema Design

```sql
CREATE TABLE messages (
  id               BIGINT        AUTO_INCREMENT PRIMARY KEY,
  message_id       VARCHAR(36)   NOT NULL,          -- UUID, dedup key
  room_id          VARCHAR(10)   NOT NULL,
  user_id          VARCHAR(10)   NOT NULL,
  username         VARCHAR(20)   NOT NULL,
  message          TEXT          NOT NULL,
  message_type     VARCHAR(10)   NOT NULL,           -- JOIN / LEAVE / TEXT
  client_timestamp DATETIME(3)   NOT NULL,           -- millisecond precision
  server_timestamp DATETIME(3)   NOT NULL,
  server_id        VARCHAR(20)   NOT NULL,

  UNIQUE KEY uq_message_id (message_id),
  INDEX idx_room_time (room_id, client_timestamp),
  INDEX idx_user_time (user_id, client_timestamp)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

**Design decisions:**

- `id` (auto-increment BIGINT) is the clustered primary key — monotonically increasing inserts avoid InnoDB page splits and keep insert performance high
- `message_id` (UUID) carries the application-level identity and is enforced as a unique secondary key for idempotent `INSERT IGNORE` deduplication
- `client_timestamp DATETIME(3)` stores millisecond-precision event time; all range queries filter on this column
- `TEXT` for `message` content — variable length, not included in any index, stored off-page for long values without affecting index efficiency

---

## 3. Indexing Strategy

| Index | Columns | Supports |
|-------|---------|---------|
| PRIMARY | `id` | Clustered insert order; table scan fallback |
| `uq_message_id` | `message_id` | Idempotent `INSERT IGNORE`; dedup lookup |
| `idx_room_time` | `(room_id, client_timestamp)` | Query 1: room messages in time range |
| `idx_user_time` | `(user_id, client_timestamp)` | Query 2: user history; Query 4: rooms by user |

**Composite index rationale:**

- `(room_id, client_timestamp)` covers `WHERE room_id=? AND client_timestamp BETWEEN ? AND ? ORDER BY client_timestamp` in a single index range scan — no filesort, no table access for the filter columns
- `(user_id, client_timestamp)` similarly covers user history queries with optional date range; the leading `user_id` column eliminates non-matching rows before the time filter is applied
- Query 3 (`COUNT(DISTINCT user_id)`) and analytics queries (`GROUP BY room_id`, `GROUP BY user_id`) perform full scans — acceptable given they are low-frequency, offline analytics calls

**Write performance trade-off:** Three secondary indexes (unique + two composites) add ~15–20% overhead per `INSERT` compared to a heap-only table. This is acceptable because DB writes are fully decoupled from the RabbitMQ pipeline via `MessageBatchQueue`, so index maintenance never stalls message consumption.

---

## 4. Scaling Considerations

- **Horizontal read scaling**: Add MySQL read replicas; route `GET /metrics` queries to replica, writes to primary
- **Partitioning**: Partition `messages` by `client_timestamp` (RANGE partitioning by month) when the table exceeds ~100M rows to keep per-partition index sizes manageable
- **Archival**: Move messages older than N days to cold storage (S3 + Athena) via a nightly batch job; keep only recent data in the hot MySQL table
- **Connection pooling**: HikariCP with pool size = 2× CPU cores per service instance; separate pools for the write path (consumer-v3) and read path (server-v2 MetricsService)

---

## 5. Backup and Recovery

- **AWS RDS**: Enable automated daily snapshots with 7-day retention; point-in-time recovery via binlog replay
- **Local/Docker**: `mysqldump --single-transaction chatflow messages` for consistent logical backups without locking InnoDB tables
- **Idempotent replay**: Because all writes use `INSERT IGNORE` on `message_id`, replaying a backup or re-processing RabbitMQ messages is safe — duplicates are silently discarded
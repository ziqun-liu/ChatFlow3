# ChatFlow3 — Architecture Document

## 1. System Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│  AWS us-west-2                                                              │
│                                                                             │
│  ┌──────────┐  WebSocket   ┌──────────────────────────────────────┐         │
│  │  Client  │─────────────►│  ALB (sticky sessions, HTTP/WS)      │         │
│  │ local    │              │  source-IP hash                      │         │
│  └──────────┘              └──────────────┬───────────────────────┘         │
│                                           │ round-robin (4 targets)         │
│                        ┌──────────────────┼──────────────┐                  │
│                        ▼                  ▼              ▼                  │
│                 ┌──────────┐       ┌──────────┐   ┌──────────┐              │
│                 │server-v2 │  ×4   │server-v2 │   │server-v2 │              │
│                 │t3.micro  │       │t3.micro  │   │t3.micro  │              │
│                 │:8080/chat│       │:8080/chat│   │:8080/chat│              │
│                 └────┬─────┘       └────┬─────┘   └────┬─────┘              │
│                      └─────────────┬────┘──────────────┘                    │
│                                    │ AMQP publish (chat.exchange)           │
│                                    ▼                                        │
│                       ┌────────────────────────┐                            │
│                       │   RabbitMQ  t3.small   │                            │
│                       │  exchange: chat.exchange│                           │
│                       │  queues: room.1~room.20│                            │
│                       └────────────┬───────────┘                            │
│                                    │ basicConsume (push)                    │
│                                    ▼                                        │
│                       ┌────────────────────────┐                            │
│                       │  consumer-v3  t3.micro  │                           │
│                       │  10 RoomConsumer threads│                           │
│                       │  (2 rooms per thread)   │                           │
│                       └──────┬─────────┬────────┘                           │
│                              │         │                                    │
│                    Redis PUBLISH    MessageBatchQueue                       │
│                              │         │ DbBatchWriter × 3                  │
│                              ▼         ▼                                    │
│                 ┌──────────────┐  ┌──────────────────┐                      │
│                 │  ElastiCache │  │   RDS MySQL 8    │                      │
│                 │  Redis 7.x   │  │ chatflow.messages│                      │
│                 └──────┬───────┘  └──────────────────┘                      │
│                        │ psubscribe("room.*") — all 4 servers               │
│                 ┌──────┴──────┐                                             │
│                 ▼             ▼                                             │
│          ┌──────────┐  ┌──────────┐  (all 4 instances)                      │
│          │server-v2 │  │server-v2 │  each broadcasts only                   │
│          │broadcast │  │broadcast │  to its own WS sessions                 │
│          └──────────┘  └──────────┘                                         │
└─────────────────────────────────────────────────────────────────────────────┘
```

**Key design**: Each server-v2 subscribes to Redis `room.*`. On message received, it broadcasts only to its own locally-held WebSocket sessions — no cross-server HTTP calls. MySQL writes are fully decoupled from the RabbitMQ pipeline via a non-blocking `MessageBatchQueue`.

---

## 2. Message Flow Sequence

```
Client    ALB      server-v2        RabbitMQ      Consumer      Redis     server-v2(×4)   MySQL
  │        │           │                │              │           │            │             │
  │─WS────►│           │               │              │           │            │             │
  │        │─route────►│               │              │           │            │             │
  │        │           │─validate      │              │           │            │             │
  │        │           │─borrow Channel│              │           │            │             │
  │        │           │─basicPublish─►│              │           │            │             │
  │        │           │─waitForConfirms(3s)          │           │            │             │
  │        │           │◄──confirm─────│              │           │            │             │
  │        │           │─return Channel│              │           │            │             │
  │◄───────────────────ACK             │              │           │            │             │
  │        │           │               │─basicDeliver►│           │            │             │
  │        │           │               │              │─PUBLISH──►│            │             │
  │        │           │               │              │◄──OK──────│            │             │
  │        │           │               │◄─basicAck────│           │            │             │
  │        │           │               │              │─offer()──────────────────────────►  │
  │        │           │               │              │  (non-blocking)        │         batch│
  │        │           │               │              │           │─onPMessage►│         INSERT│
  │◄──────────────────────────────────────────────────────────────────broadcast│             │
```

**DB write path**: `offer()` to `MessageBatchQueue` is non-blocking — a slow or down MySQL never stalls RabbitMQ consumption or Redis publishing. `DbBatchWriter` drains the queue in batches and does `INSERT IGNORE` (idempotent via `UNIQUE KEY uq_message_id`).

**Failure path**: If `waitForConfirms` times out (3s) or CircuitBreaker is OPEN, server-v2 returns an error ACK to the client. If Consumer fails to publish to Redis, it nacks to RabbitMQ; the message is requeued once, then dead-lettered on second failure.

---

## 3. Queue Topology

```
chat.exchange  (type=topic, durable=true)
│
├── routing key "room.1"  ──►  queue: room.1
├── routing key "room.2"  ──►  queue: room.2
│     ...
└── routing key "room.20" ──►  queue: room.20

Each room queue:
  durable=true | x-message-ttl=60,000 ms | x-max-length=10,000
  x-dead-letter-exchange=chat.dlx

chat.dlx  (type=fanout, durable=true)
└──► room.dlq  (dead-letter sink for overflow / expired / undeliverable)
```

| Property | Value |
|---|---|
| Exchange type | topic |
| Room queues | 20 (room.1 – room.20) |
| Message TTL | 60 s |
| Max queue depth | 10,000 messages |
| Overflow / expired | Routed to `room.dlq` via `chat.dlx` |
| Delivery mode | 1 (transient — in-memory only, no disk write) |
| Publisher confirms | `confirmSelect()` + `waitForConfirms(3 s)` |

---

## 4. Consumer Threading Model

```
ConsumerMain
└── ConsumerManager  (ExecutorService, N=10 threads)
    │
    │  Round-robin room assignment (20 rooms ÷ 10 threads = 2 rooms/thread):
    │    thread-0  → room.1,  room.11
    │    thread-1  → room.2,  room.12
    │       ...
    │    thread-9  → room.10, room.20
    │
    └── RoomConsumer  (one per thread)
        ├── owns: 1 RabbitMQ Connection + N Channels  (no lock contention)
        ├── basicQos(prefetch=50)    ← back-pressure / flow control
        ├── basicConsume(autoAck=false)
        └── handleDelivery:
            ├── MessageProcessor  →  QueueMessage | MALFORMED
            └── DeliveryHandler   →  ACK/NACK policy
                                  →  RedisPublisher.publish()
                                  →  MessageBatchQueue.offer()  [non-blocking]
```

**DB write pool** (runs in parallel with RoomConsumer threads):
```
DbBatchWriter × 3 threads
└── drain MessageBatchQueue (capacity 100K)
    └── JDBC batch INSERT IGNORE (batch size configurable, default 500)
        └── HikariCP pool (default 10 connections)
            └── DB CircuitBreaker (5 failures → 30s cooldown)
```

---

## 5. DB Persistence Layer

### Schema

```sql
CREATE TABLE messages (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  message_id       VARCHAR(36)  NOT NULL,  -- UUID, unique key for idempotency
  room_id          VARCHAR(10)  NOT NULL,
  user_id          VARCHAR(10)  NOT NULL,
  username         VARCHAR(20)  NOT NULL,
  message          TEXT         NOT NULL,
  message_type     VARCHAR(10)  NOT NULL,
  client_timestamp DATETIME(3)  NOT NULL,
  server_timestamp DATETIME(3)  NOT NULL,
  server_id        VARCHAR(20)  NOT NULL,

  UNIQUE KEY uq_message_id (message_id),
  INDEX idx_room_time (room_id, client_timestamp),  -- Query 1
  INDEX idx_user_time (user_id, client_timestamp)   -- Query 2, 4
);
```

### Index Strategy

| Index | Covers | Rationale |
|---|---|---|
| `uq_message_id` | `INSERT IGNORE` dedup | Silently drops duplicate messages |
| `idx_room_time` | `WHERE room_id=? AND client_timestamp BETWEEN ? AND ?` | Query 1: no filesort |
| `idx_user_time` | `WHERE user_id=? [AND client_timestamp ...]` | Query 2 + 4 |

---

## 6. Metrics API

`GET /server/metrics?start=&end=&userId=&roomId=&topN=`

Runs 8 queries against MySQL and returns JSON:

| Query | Type | SQL Pattern |
|---|---|---|
| `roomMessages` | Core | `WHERE room_id=? AND client_timestamp BETWEEN ? AND ? LIMIT 100` |
| `userHistory` | Core | `WHERE user_id=? [AND client_timestamp ...] LIMIT 100` |
| `activeUsers` | Core | `COUNT(DISTINCT user_id) WHERE client_timestamp BETWEEN ? AND ?` |
| `userRooms` | Core | `SELECT DISTINCT room_id, MAX(client_timestamp) WHERE user_id=?` |
| `messagesPerSecond` | Analytics | `GROUP BY DATE_FORMAT(client_timestamp, '%Y-%m-%d %H:%i:%s')` |
| `topActiveUsers` | Analytics | `GROUP BY user_id ORDER BY COUNT(*) DESC LIMIT N` |
| `topActiveRooms` | Analytics | `GROUP BY room_id ORDER BY COUNT(*) DESC LIMIT N` |
| `totalMessages` | Analytics | `SELECT COUNT(*)` |

Uses a dedicated HikariCP pool (`metrics-pool`, size 5). Called automatically by the client after each load test; results written to `metrics-result.json`.

---

## 7. Load Balancing Configuration

**ALB (Application Load Balancer)**
- **Sticky sessions**: source-IP hash. A WebSocket client always routes to the same server-v2 instance, so its session remains in `RoomManager` on every message.
- **Health check**: `GET /server/health` every 30 s; 2 consecutive failures remove the instance from the target group.
- **Protocol**: HTTP — ALB transparently handles the WebSocket upgrade.
- **Target group**: 4× server-v2 instances on port 8080.

**Publisher-side distribution**
- Each of 4 server instances holds a `ChannelPool` of 20 pre-created channels (80 channels across the fleet).
- Routing key `room.{1-20}` spreads messages across 20 independent queues — no single-queue hotspot.
- Consumer assigns 2 queues per thread, so per-thread load is proportional to room traffic.

---

## 8. Failure Handling Strategies

### Circuit Breaker — RabbitMQ (server-v2)

```
CLOSED ──(5 consecutive failures)──► OPEN ──(30 s cooldown)──► HALF_OPEN
  ▲                                                                  │
  └────────────────(probe success)──────────────────────────────────┘
                          │
                   (probe failure) ──► OPEN  (restart 30 s cooldown)
```

- **OPEN**: `publish()` fast-fails immediately, avoiding a 3 s `waitForConfirms` timeout per thread.
- **HALF_OPEN**: Only one probe thread is admitted; others are rejected until recovery is confirmed.

### Circuit Breaker — MySQL (consumer-v3)

Same state machine (5 failures → 30s cooldown). When OPEN, `DbBatchWriter` drops the batch and logs a warning — messages remain in `MessageBatchQueue` for the next drain cycle. Does not affect RabbitMQ ack/nack or Redis publishing.

### Consumer Nack / Dead-Letter Policy

| Condition | Action | Outcome |
|---|---|---|
| Malformed JSON / missing fields | `basicAck` | Discarded immediately |
| Redis publish fail — first delivery | `basicNack(requeue=true)` | Re-queued once |
| Redis publish fail — redelivered | `basicNack(requeue=false)` | Routed to `room.dlq` |

### Redis Subscriber Reconnect

`RedisSubscriber` (daemon thread) wraps `psubscribe("room.*")` in a retry loop: on disconnect it waits 3 s and reconnects, automatically restoring fan-out to all server instances.

### Queue Overflow Protection

`x-max-length=10,000` per queue: oldest messages overflow to `chat.dlx` when the queue is full. `x-message-ttl=60 s` expires messages undelivered within 1 minute.

### WebSocket Session Cleanup

`RoomManager.removeSession()` is called on every `@OnClose` and `@OnError`, preventing broadcast attempts to stale sessions.

---

## 9. Delivery Guarantees

| Segment | Mechanism | Guarantee |
|---|---|---|
| Client → RabbitMQ | publisher confirms + CircuitBreaker | at-least-once |
| RabbitMQ → consumer-v3 | basicAck / basicNack, max 1 requeue | at-least-once |
| consumer-v3 → Redis | Pub/Sub | fire-and-forget (best-effort) |
| consumer-v3 → MySQL | INSERT IGNORE + retry + DB CircuitBreaker | at-least-once, idempotent |
| server-v2 → client | WebSocket sendText | best-effort |
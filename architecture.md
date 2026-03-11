# ChatFlow2 — Architecture Document

## 1. System Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│  AWS us-west-2                                                               │
│                                                                              │
│  ┌──────────┐  WebSocket   ┌──────────────────────────────────────┐         │
│  │  Client  │─────────────►│  ALB (sticky sessions, HTTP/WS)      │         │
│  │ t3.small │              │  cs6650-assignment2-lb               │         │
│  └──────────┘              └──────────────┬───────────────────────┘         │
│                                           │ round-robin (4 targets)         │
│                        ┌──────────────────┼──────────────┐                  │
│                        ▼                  ▼              ▼                  │
│                 ┌──────────┐       ┌──────────┐   ┌──────────┐             │
│                 │server-v2 │  ×4   │server-v2 │   │server-v2 │             │
│                 │t3.micro  │       │t3.micro  │   │t3.micro  │             │
│                 │:8080/chat│       │:8080/chat│   │:8080/chat│             │
│                 └────┬─────┘       └────┬─────┘   └────┬─────┘             │
│                      └─────────────┬────┘──────────────┘                    │
│                                    │ AMQP publish (chat.exchange)           │
│                                    ▼                                         │
│                       ┌────────────────────────┐                            │
│                       │   RabbitMQ  t3.small   │                            │
│                       │  exchange: chat.exchange│                            │
│                       │  queues: room.1~room.20│                            │
│                       └────────────┬───────────┘                            │
│                                    │ basicConsume (push)                    │
│                                    ▼                                         │
│                       ┌────────────────────────┐                            │
│                       │   Consumer  t3.micro   │                            │
│                       │   20 RoomConsumer      │                            │
│                       │   threads              │                            │
│                       └────────────┬───────────┘                            │
│                                    │ Redis PUBLISH room.{id}                │
│                                    ▼                                         │
│                       ┌────────────────────────┐                            │
│                       │  ElastiCache Redis 7.x │                            │
│                       └────────────┬───────────┘                            │
│                                    │ psubscribe("room.*") — all 4 servers   │
│                        ┌───────────┴──────────┐                             │
│                        ▼                      ▼                             │
│                 ┌──────────┐          ┌──────────┐                          │
│                 │server-v2 │          │server-v2 │  (all 4 instances)       │
│                 │broadcast │          │broadcast │  each broadcasts only    │
│                 │local only│          │local only│  to its own WS sessions  │
│                 └──────────┘          └──────────┘                          │
└─────────────────────────────────────────────────────────────────────────────┘
```

**Key design**: Each server-v2 subscribes to Redis `room.*`. On message received, it broadcasts only to its own locally-held WebSocket sessions — no cross-server HTTP calls.

---

## 2. Message Flow Sequence

```
Client    ALB      server-v2        RabbitMQ      Consumer      Redis     server-v2(×4)
  │        │           │                │              │           │            │
  │─WS────►│           │               │              │           │            │
  │        │─route────►│               │              │           │            │
  │        │           │─validate      │              │           │            │
  │        │           │─borrow Channel│              │           │            │
  │        │           │─basicPublish─►│              │           │            │
  │        │           │─waitForConfirms(3s timeout)  │           │            │
  │        │           │◄──confirm─────│              │           │            │
  │        │           │─return Channel│              │           │            │
  │◄───────────────────ACK             │              │           │            │
  │        │           │               │─basicDeliver►│           │            │
  │        │           │               │              │─PUBLISH──►│            │
  │        │           │               │              │◄──OK───────│            │
  │        │           │               │◄─basicAck────│           │            │
  │        │           │               │              │           │─onPMessage►│
  │◄──────────────────────────────────────────────────────────────────broadcast│
```

**Failure path**: If `waitForConfirms` times out (3 s) or CircuitBreaker is OPEN, server-v2 returns an error ACK to the client. If Consumer fails to publish to Redis, it nacks to RabbitMQ; the message is requeued once, then dead-lettered on second failure.

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
└── ConsumerManager  (ExecutorService, N=20 threads)
    │
    │  Round-robin room assignment (20 rooms ÷ 20 threads = 1 room/thread):
    │    thread-0  → room.1     thread-5  → room.6     thread-10 → room.11
    │    thread-1  → room.2     thread-6  → room.7     thread-11 → room.12
    │       ...                    ...                    ...
    │    thread-19 → room.20
    │
    └── RoomConsumer  (one per thread)
        ├── owns: 1 RabbitMQ Connection + 1 Channel  (no lock contention)
        ├── basicQos(prefetch=100)    ← back-pressure / flow control
        ├── basicConsume(autoAck=false)
        └── handleDelivery:
            ├── MessageProcessor  →  QueueMessage | MALFORMED
            └── DeliveryHandler   →  ACK/NACK policy + ConsumerMetrics
```

**Resource budget (t3.micro, 1 GB RAM)**:
20 RabbitMQ connections + JedisPool (maxTotal=40) = ~60 total connections.
`ConsumerMetrics` daemon thread logs throughput every 10 s.

---

## 5. Load Balancing Configuration

**ALB (Application Load Balancer)**
- **Sticky sessions**: source-IP hash. A WebSocket client always routes to the same server-v2 instance, so its session remains in `RoomManager` on every message.
- **Health check**: `GET /server/health` every 30 s; 2 consecutive failures remove the instance from the target group.
- **Protocol**: HTTP — ALB transparently handles the WebSocket upgrade.
- **Target group**: 4× server-v2 instances on port 8080.

**Publisher-side distribution**
- Each of 4 server instances holds a `ChannelPool` of 100 pre-created channels (400 channels across the fleet).
- Routing key `room.{1-20}` spreads messages across 20 independent queues — no single-queue hotspot.
- Consumer assigns queues 1:1 to threads, so per-thread load is proportional to room traffic.

---

## 6. Failure Handling Strategies

### Circuit Breaker (server-v2 → RabbitMQ)

```
CLOSED ──(5 consecutive failures)──► OPEN ──(30 s cooldown)──► HALF_OPEN
  ▲                                                                  │
  └────────────────(probe success)──────────────────────────────────┘
                          │
                   (probe failure) ──► OPEN  (restart 30 s cooldown)
```

- **OPEN**: `publish()` fast-fails immediately, avoiding a 3 s `waitForConfirms` timeout per thread and preventing thread pile-up.
- **HALF_OPEN**: Only one probe thread is admitted; others are rejected until recovery is confirmed.

### Consumer Nack / Dead-Letter Policy

| Condition | Action | Outcome |
|---|---|---|
| Malformed JSON / missing fields | `basicAck` | Discarded immediately |
| Duplicate `messageId` (Redis SET NX, 30 s TTL) | `basicAck` | Discarded (idempotency) |
| Redis publish fail — first delivery | `basicNack(requeue=true)` | Re-queued once |
| Redis publish fail — redelivered | `basicNack(requeue=false)` | Routed to `room.dlq` |

### Redis Subscriber Reconnect

`RedisSubscriber` (daemon thread) wraps `psubscribe("room.*")` in a retry loop: on disconnect it waits 3 s and reconnects, automatically restoring fan-out to all server instances.

### Queue Overflow Protection

`x-max-length=10,000` per queue: oldest messages overflow to `chat.dlx` when the queue is full, preventing unbounded memory growth on the broker. `x-message-ttl=60 s` expires messages undelivered within 1 minute.

### WebSocket Session Cleanup

`RoomManager.removeSession()` is called on every `@OnClose` and `@OnError`, preventing broadcast attempts to stale sessions. ALB health checks evict unhealthy server instances from the target group within ~60 s.

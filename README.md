# ChatFlow3 — CS6650 Assignment 3

A distributed real-time chat system built on WebSocket, RabbitMQ, Redis Pub/Sub, and MySQL.

Messages flow from WebSocket clients through an ALB (sticky sessions) to server-v2 instances, which publish to RabbitMQ. consumer-v3 consumes from RabbitMQ, publishes to Redis Pub/Sub for real-time broadcast, and persists messages to MySQL via a write-behind batch queue. server-v2 subscribes to Redis Pub/Sub and broadcasts messages to local WebSocket sessions. A Metrics API (`GET /metrics`) exposes 4 core queries and 4 analytics queries against MySQL, called automatically by the client after each load test.

---

## Architecture Overview

```text
┌─────────────────────────────────────────────────────────────────────────┐
│                           Load Test Client                              │
│                                                                         │
│   MessageGenerator → LinkedBlockingQueue → SenderWorker × N             │
│                                                │                        │
│                                      WebSocket (ws://)                  │
└────────────────────────────────────────────────│────────────────────────┘
                                                 │
                                                 ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                  AWS Application Load Balancer (sticky session)         │
└────────┬─────────────────┬─────────────────┬─────────────────┬──────────┘
         │                 │                 │                 │
         ▼                 ▼                 ▼                 ▼
  ┌─────────────┐   ┌─────────────┐   ┌─────────────┐   ┌─────────────┐
  │  server-v2  │   │  server-v2  │   │  server-v2  │   │  server-v2  │
  │  (EC2-A1)   │   │  (EC2-A2)   │   │  (EC2-A3)   │   │  (EC2-A4)   │
  │             │   │             │   │             │   │             │
  │ ServerEndpt │   │ ServerEndpt │   │ ServerEndpt │   │ ServerEndpt │
  │ ChannelPool │   │ ChannelPool │   │ ChannelPool │   │ ChannelPool │
  │ MsgPublshr  │   │ MsgPublshr  │   │ MsgPublshr  │   │ MsgPublshr  │
  │ CircuitBrkr │   │ CircuitBrkr │   │ CircuitBrkr │   │ CircuitBrkr │
  │ RedisSubs   │   │ RedisSubs   │   │ RedisSubs   │   │ RedisSubs   │
  │ MetricsSvlt │   │ MetricsSvlt │   │ MetricsSvlt │   │ MetricsSvlt │
  └──────┬──────┘   └──────┬──────┘   └──────┬──────┘   └──────┬──────┘
         │                 │                 │                 │
         └─────────────────┴────────┬────────┴─────────────────┘
                                    │ publish
                                    │ exchange: chat.exchange (topic)
                                    │ routing key: room.{roomId}
                                    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                          RabbitMQ (EC2-B)                               │
│                                                                         │
│   Exchange: chat.exchange (topic, durable)                              │
│   room.1  room.2  room.3  ...  room.20  ← durable queues                │
│   DLX: chat.dlx  →  DLQ: room.dlq                                       │
└─────────────────────────────────┬───────────────────────────────────────┘
                                  │ consume (push-based, basicAck/basicNack)
                                  ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                        consumer-v3 (EC2-C)                              │
│                                                                         │
│   ConsumerMain                                                          │
│       └── ConsumerManager                                               │
│               ├── RoomConsumer thread-0  (room.1, room.5, ...)          │
│               ├── RoomConsumer thread-1  (room.2, room.6, ...)          │
│               └── RoomConsumer thread-N  (room.4, room.8, ...)          │
│                         │                                               │
│                 ┌────────┴────────┐                                     │
│                 │                 │                                     │
│                 ▼                 ▼                                     │
│         RedisPublisher    MessageBatchQueue (100K cap)                  │
│         publish(msg)          └── DbBatchWriter × 3                     │
│                                   INSERT IGNORE (batch 500)             │
└──────────┬──────────────────────────────────┬───────────────────────────┘
           │ Redis Pub/Sub                    │ JDBC batch insert
           │ channel: room.{roomId}           ▼
           ▼                     ┌─────────────────────────┐
┌──────────────────────┐         │   MySQL / RDS (EC2-D)   │
│  AWS ElastiCache     │         │   chatflow.messages     │
│  for Redis           │         └────────────┬────────────┘
└──────┬───────────────┘                      │ SQL queries
       │ push to all subscribers              ▼
       ▼                         ┌─────────────────────────┐
  server-v2 instances            │  MetricsService         │
  RedisSubscriber                │  GET /server/metrics    │
  → RoomManager broadcast        └─────────────────────────┘
  → WebSocket clients
```

> For detailed message flow, queue topology, DB persistence layer, Metrics API, delivery guarantees, and failure handling strategies, see [architecture.md](architecture.md).

---

## Module Structure

```
ChatFlow3/
├── client/                   Load test harness
│   └── src/main/java/assign2/client/
│       ├── ClientMain.java        warmup + main phase + metrics API call
│       ├── ClientEndpoint.java
│       ├── connection/ConnectionManager.java
│       ├── sender/SenderWorker.java
│       ├── producer/MessageGenerator.java
│       ├── metrics/Metrics.java
│       └── model/ChatMessage.java
│
├── server-v2/                WebSocket server — RabbitMQ producer + Redis subscriber + Metrics API
│   └── src/main/java/assign2/server/v2/
│       ├── config/
│       │   ├── RabbitMQConfig.java
│       │   ├── RedisConfig.java
│       │   └── DbConfig.java
│       ├── model/
│       │   ├── ChatMessageDto.java
│       │   ├── QueueMessage.java
│       │   └── ChatResponse.java
│       ├── service/
│       │   ├── MessagePublisher.java
│       │   ├── RoomManager.java
│       │   ├── RedisSubscriber.java
│       │   ├── MetricsService.java       4 core + 4 analytics SQL queries
│       │   ├── ServerMetrics.java        publish rate + system metrics reporter
│       │   └── rabbitmq/
│       │       ├── ChannelPool.java
│       │       └── CircuitBreaker.java
│       └── controller/
│           ├── ServerEndpoint.java       WebSocket /chat/{roomId}
│           ├── MetricsServlet.java       GET /metrics
│           └── HealthServlet.java        GET /health
│
├── consumer-v3/              RabbitMQ consumer — Redis publisher + MySQL persistence
│   └── src/main/java/assign2/consumer/v3/
│       ├── ConsumerMain.java
│       ├── config/
│       │   ├── RabbitMQConfig.java
│       │   ├── RedisConfig.java
│       │   └── DbConfig.java
│       ├── model/
│       │   ├── QueueMessage.java
│       │   └── ProcessingResult.java
│       ├── service/
│       │   ├── RedisPublisher.java
│       │   ├── MessageProcessor.java
│       │   ├── DeliveryHandler.java      ack/nack + offer() to batch queue
│       │   ├── ConsumerMetrics.java      throughput + DB insert metrics
│       │   ├── rabbitmq/CircuitBreaker.java
│       │   └── db/
│       │       ├── MessageBatchQueue.java   LinkedBlockingQueue singleton (100K cap)
│       │       └── DbBatchWriter.java       batch INSERT IGNORE + circuit breaker
│       └── controller/
│           ├── ConsumerManager.java      manages RoomConsumer + DbBatchWriter lifecycle
│           └── RoomConsumer.java
│
├── database/                 Schema and setup scripts
│   ├── schema.sql            CREATE TABLE messages + 3 indexes
│   └── setup.sh              create DB/user, apply schema
│
├── deployment/               AWS deployment scripts
│   ├── deploy-all.sh         orchestrates all steps
│   ├── rabbitmq-setup.sh
│   ├── database-setup.sh     create chatflow DB/user on RDS/EC2
│   ├── deploy-server.sh
│   ├── consumer-setup.sh
│   └── client-setup.sh
│
├── monitoring/
│   ├── mysql-stats.sh        query MySQL status metrics
│   └── consumer-stats.sh     tail consumer logs for throughput output
│
└── load-tests/
    ├── test1-baseline.env    500K messages
    ├── test2-stress.env      1M messages
    ├── test3-endurance.env   sustained 30min
    └── README.md             batch tuning instructions
```

---

## I. Local Development

### Prerequisites

- Docker Desktop
- Java 11 (Temurin/OpenJDK recommended)
- Maven 3.x
- GNU Make (`winget install GnuWin32.Make` on Windows)

### Full pipeline 

```bash
make run
# This one command runs in order:
# make build
# make up
# rabbitmq-init
# make test
```



### Load tests
```bash
make run-test1  # Baseline — 500K messages, batch=500, same as `test` but restart consumer
```
```bash
make run-test2  # Stress — 1M messages, batch=1000
```
```bash
make run-test3  # Endurance — 4 × 500K (~30 min on EC2), batch=500
```
```bash
make run-batch-tune  # Batch size comparison — 4 runs with batch=100/500/1000/5000
```

### Collecting metrics
Open a second terminal to collect additional data:

```bash
# Consumer throughput + DB insert rate (prints every 10s)
make logs-consumer
```
After test completes, collect MySQL statistics:
```bash
docker exec mysql-local mysql -uroot -proot -e "
  SHOW STATUS LIKE 'Queries';
  SHOW STATUS LIKE 'Threads_connected';
  SHOW STATUS LIKE 'Innodb_row_lock_waits';
  SELECT COUNT(*) as total_messages FROM chatflow.messages;
"
```

### Stopping the project

```bash
make down        # stop and remove all containers
```

---

## II. Cloud Deployment (AWS)

Edit the variable block at the top of `deployment/deploy-all.sh` (single source of truth),
then run:

```bash
chmod +x deployment/deploy-all.sh
./deployment/deploy-all.sh
# Order: RabbitMQ → Database → server-v2 × 4 → consumer-v3
```

### Monitor

```bash
# Client metrics (live)
ssh -i ~/.ssh/cs6650-assignment2.pem ec2-user@<client-ip> \
  'tail -f /opt/client/client.log'

# Consumer throughput + DB inserts
ssh -i ~/.ssh/cs6650-assignment2.pem ec2-user@<consumer-ip> \
  'tail -f /opt/consumer/consumer.log'

# Server publish rate + system metrics
ssh -i ~/.ssh/cs6650-assignment2.pem ec2-user@<server-ip> \
  'tail -f /opt/tomcat9/logs/catalina.out'

# MySQL stats (run on any machine with mysql client)
MYSQL_HOST=<db-host> MYSQL_PASS=<db-pass> ./monitoring/mysql-stats.sh
```

### Environment Variables

| Variable | Used by | Default | Notes |
|---|---|---|---|
| `RABBITMQ_HOST` | server-v2, consumer-v3 | `localhost` | EC2 private IP in cloud |
| `RABBITMQ_USER` | server-v2, consumer-v3 | `guest` | |
| `RABBITMQ_PASS` | server-v2, consumer-v3 | `guest` | |
| `RABBITMQ_CHANNEL_POOL_SIZE` | server-v2 | `20` | Raise for higher throughput |
| `RABBITMQ_DELIVERY_MODE` | server-v2 | `1` | 1=transient (fast), 2=persistent |
| `RABBITMQ_PREFETCH` | consumer-v3 | `50` | In-flight messages per thread |
| `REDIS_HOST` | server-v2, consumer-v3 | `localhost` | ElastiCache endpoint |
| `CONSUMER_THREADS` | consumer-v3 | `10` | RoomConsumer thread count |
| `SERVER_ID` | server-v2 | `server-1` | Per-instance identifier |
| `DB_HOST` | server-v2, consumer-v3 | `localhost` | RDS endpoint or EC2 private IP |
| `DB_NAME` | server-v2, consumer-v3 | `chatflow` | |
| `DB_USER` | server-v2, consumer-v3 | `chatflow` | |
| `DB_PASS` | server-v2, consumer-v3 | *(required)* | |
| `DB_BATCH_SIZE` | consumer-v3 | `500` | Rows per INSERT batch |
| `DB_FLUSH_INTERVAL_MS` | consumer-v3 | `500` | Max wait before partial flush |
| `DB_WRITER_THREADS` | consumer-v3 | `3` | DbBatchWriter thread count |
| `WS_URI` | client | `ws://localhost:8080/server/chat/` | ALB endpoint |

### AWS Infrastructure

| Component | Service | Notes |
|---|---|---|
| server-v2 | EC2 (×1–4) + Tomcat | Behind ALB, sticky session (source-IP hash) |
| consumer-v3 | EC2 (×1) | Standalone JAR, no inbound traffic |
| RabbitMQ | EC2 (×1) | Port 5672 open to server-v2 + consumer SGs |
| Redis | ElastiCache for Redis | Port 6379 open to server-v2 + consumer SGs |
| MySQL | RDS MySQL 8 or EC2 | Port 3306 open to server-v2 + consumer SGs |
| Load Balancer | ALB | Sticky session, health check `GET /health` every 30s |

### Security Group Rules

**server-v2 EC2**
- Inbound: port 8080 from ALB security group
- Outbound: 5672 (RabbitMQ), 6379 (Redis), 3306 (MySQL)

**consumer-v3 EC2**
- Inbound: port 22 from your IP (SSH)
- Outbound: 5672 (RabbitMQ), 6379 (Redis), 3306 (MySQL)

**RabbitMQ EC2**
- Inbound: 5672 from server-v2 + consumer SGs; 15672 from your IP (management console)

**MySQL / RDS**
- Inbound: 3306 from server-v2 + consumer SGs

**ElastiCache**
- Inbound: 6379 from server-v2 + consumer SGs

**ALB**
- Inbound: 80/443 from 0.0.0.0/0
- Outbound: 8080 to server-v2 SG

---

## Configuration Priority

```
1. Environment variable   (e.g. DB_HOST)
2. config.properties      (e.g. db.host)
3. Hardcoded default      (e.g. "localhost")
```

Copy `*/src/main/resources/config.properties.example` → `config.properties` for local dev.

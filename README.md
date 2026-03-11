# ChatFlow2 — CS6650 Assignment 2

A distributed real-time chat system built on WebSocket, RabbitMQ, and Redis Pub/Sub.
Messages are produced by WebSocket servers, queued through RabbitMQ for reliable delivery,
consumed by a dedicated consumer service, and broadcast to all connected clients via Redis Pub/Sub.

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
  │ RoomManager │   │ RoomManager │   │ RoomManager │   │ RoomManager │
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
│                                                                         │
│   room.1  room.2  room.3  ...  room.20                                  │
│   [████]  [████]  [████]  ...  [████]   ← durable queues               │
└─────────────────────────────────┬───────────────────────────────────────┘
                                  │ consume (push-based, basicAck/basicNack)
                                  ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                          consumer (EC2-C)                               │
│                                                                         │
│   ConsumerMain                                                          │
│       └── ConsumerManager                                               │
│               ├── RoomConsumer thread-0  (room.1, room.5, ...)          │
│               ├── RoomConsumer thread-1  (room.2, room.6, ...)          │
│               ├── RoomConsumer thread-2  (room.3, room.7, ...)          │
│               └── RoomConsumer thread-N  (room.4, room.8, ...)          │
│                         │                                               │
│                         │ on successful consume:                        │
│                         ▼                                               │
│               RedisPublisher                                            │
│               jedis.publish("room.{roomId}", messageJson)               │
└─────────────────────────────────┬───────────────────────────────────────┘
                                  │ Redis Pub/Sub
                                  │ channel: room.{roomId}
                                  ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                    AWS ElastiCache for Redis (same VPC)                 │
└────────┬─────────────────┬─────────────────┬─────────────────┬──────────┘
         │ push            │ push            │ push            │ push
         ▼                 ▼                 ▼                 ▼
  ┌─────────────┐   ┌─────────────┐   ┌─────────────┐   ┌─────────────┐
  │  server-v2  │   │  server-v2  │   │  server-v2  │   │  server-v2  │
  │  (EC2-A1)   │   │  (EC2-A2)   │   │  (EC2-A3)   │   │  (EC2-A4)   │
  │             │   │             │   │             │   │             │
  │ RedisSubs   │   │ RedisSubs   │   │ RedisSubs   │   │ RedisSubs   │
  │ onPMessage()│   │ onPMessage()│   │ onPMessage()│   │ onPMessage()│
  │ RoomManager │   │ RoomManager │   │ RoomManager │   │ RoomManager │
  └──────┬──────┘   └──────┬──────┘   └──────┬──────┘   └──────┬──────┘
         │                 │                 │                 │
         └─────────────────┴────────┬────────┴─────────────────┘
                                    │ WebSocket broadcast
                                    │ (each instance broadcasts only to
                                    │  sessions it locally holds)
                                    ▼
                          WebSocket Clients (chat users)
```

---

## Message Flow

```
1. client sends ChatMessage over WebSocket
       ↓
2. ALB routes to a server-v2 instance (sticky session)
       ↓
3. ServerEndpoint.onMessage()
   - validates ChatMessageDto
   - builds QueueMessage (adds roomId, serverId, clientIp)
   - ChannelPool.borrow() → basicPublish → waitForConfirms(3s)
   - CircuitBreaker: 5 failures → OPEN (fast-fail), 30s cooldown → HALF_OPEN → probe → CLOSED
   - confirm success → sendAck(messageId) back to client
   - confirm failure → sendError back to client (client retries)
       ↓
4. RabbitMQ queues message in "room.{roomId}"
       ↓
5. RoomConsumer.handleDelivery()
   - deserializes JSON → QueueMessage
   - malformed message → basicAck (discard, avoid infinite requeue)
   - RedisPublisher.publish("room.{roomId}", json)
     - success        → basicAck ✅
     - first failure  → basicNack(requeue=true) 🔄
     - retry failure  → basicNack(requeue=false), discard ⚠️
       ↓
6. Redis pushes message to all subscribed server-v2 instances
       ↓
7. RedisSubscriber.onPMessage() on each server-v2
   - deserializes JSON → QueueMessage
   - RoomManager.getSessions(roomId) → local sessions only
   - broadcasts ChatResponse(BROADCAST) to each open session
       ↓
8. WebSocket clients receive the broadcast message
```

---

## Delivery Guarantees

| Segment | Mechanism | Guarantee |
|---|---|---|
| client → RabbitMQ queue | publisher confirms + CircuitBreaker | at-least-once |
| RabbitMQ queue → consumer | basicAck / basicNack, max 1 requeue | at-least-once |
| consumer → server-v2 | Redis Pub/Sub | fire-and-forget (best-effort) |
| server-v2 → client | WebSocket sendText | best-effort |

---

## Module Structure

```
ChatFlow2/
├── client/              Load test client
│   └── src/main/java/assign2/client/
│       ├── ClientMain.java
│       ├── ClientEndpoint.java
│       ├── connection/ConnectionManager.java
│       ├── sender/SenderWorker.java
│       ├── producer/MessageGenerator.java
│       ├── metrics/Metrics.java
│       └── model/ChatMessage.java
│
├── server-v2/           WebSocket server — RabbitMQ producer + Redis subscriber
│   └── src/main/java/assign2/server/v2/
│       ├── config/
│       │   ├── RabbitMQConfig.java
│       │   └── RedisConfig.java
│       ├── model/
│       │   ├── ChatMessageDto.java       incoming WebSocket message
│       │   ├── QueueMessage.java         published to RabbitMQ
│       │   └── ChatResponse.java         returned to client (ACK/ERROR/BROADCAST)
│       ├── service/
│       │   ├── MessagePublisher.java     publish to RabbitMQ with confirms
│       │   ├── RoomManager.java          roomId → Set<Session> mapping
│       │   ├── RedisSubscriber.java      subscribe room.* and broadcast to local sessions
│       │   └── rabbitmq/
│       │       ├── ChannelPool.java      singleton, 20 pre-created channels, borrow/return
│       │       └── CircuitBreaker.java   CLOSED/OPEN/HALF_OPEN state machine
│       └── controller/
│           ├── ServerEndpoint.java       WebSocket /chat/{roomId}
│           └── HealthServlet.java        HTTP GET /health (ALB health check)
│
├── consumer/            RabbitMQ consumer — Redis publisher
│   └── src/main/java/assign2/consumer/
│       ├── ConsumerMain.java
│       ├── config/
│       │   ├── RabbitMQConfig.java
│       │   └── RedisConfig.java
│       ├── model/
│       │   └── QueueMessage.java         mirrors server-v2 QueueMessage
│       ├── service/
│       │   ├── RedisPublisher.java       publish to Redis channel room.{roomId}
│       │   └── ConsumerMetrics.java      singleton; tracks Redis/RabbitMQ throughput, logs every 10s
│       └── controller/
│           ├── ConsumerManager.java      round-robin room distribution, thread lifecycle
│           └── RoomConsumer.java         push-based RabbitMQ consumer, ack/nack logic
│
└── deployment/          Shell scripts for AWS deployment
    ├── rabbitmq-setup.sh
    ├── deploy-server.sh
    ├── consumer-setup.sh
    └── deploy-all.sh
```

---

## I. Local Development

All services default to `localhost`. Rename `config.properties.example` to `config.properties`
in both `server-v2/src/main/resources/` and `consumer/src/main/resources/` before starting.

### 1. Start RabbitMQ

```bash
# First time only — starts RabbitMQ with management console enabled
docker run -d --name rabbitmq \
  -p 5672:5672 -p 15672:15672 \
  rabbitmq:3-management
```

Verify at http://localhost:15672 (username/password: `guest`/`guest`).

### 2. Start Redis

```bash
# First time only — starts Redis on default port 6379
docker run -d --name redis-local \
  -p 6379:6379 \
  redis:7
```

### 3. Set up RabbitMQ queues
Run from ChatFlow2/ root — creates exchange and 20 room queues
```bash
RABBITMQ_HOST=localhost RABBITMQ_USER=guest RABBITMQ_PASS=guest ./deployment/rabbitmq-setup.sh
```

### 4. Start server-v2
Build and deploy WAR to local Tomcat
```bash
cd server-v2 && mvn clean package -q
  mv target/server-v2-1.0-SNAPSHOT.war target/server.war
  cp target/server.war ~/Library/Tomcat/webapps/
  ~/Library/Tomcat/bin/startup.sh
```
Verify server is running
```bash
curl http://localhost:8080/server/health
```

### 5. Start consumer

```bash
cd consumer && mvn clean package -q
java -jar target/consumer-1.0-SNAPSHOT.jar
```

Consumer defaults: `CONSUMER_THREADS=10`, connects to `localhost:5672` (RabbitMQ) and `localhost:6379` (Redis).

### 6. Start client

```bash
cd client && mvn clean package -q
java -jar target/client-1.0-SNAPSHOT.jar
```

---

## II. Cloud Deployment (AWS)

Environment variables are injected automatically by the deployment scripts.
Before running, update the variable values at the top of `deployment/deploy-all.sh`
(single source of truth — no need to edit the other scripts).

#### 1. Deploy servers
```bash
chmod +x deployment/deploy-all.sh
./deployment/deploy-all.sh
```
#### 2.(a) Test with 1 instance with server1 private ip
#### Check health using curl using public ip
```bash
WS_URI="ws://172.31.22.27:8080/server/chat/" ./deployment/client-setup.sh
```
```bash
ssh -i ~/.ssh/cs6650-assignment2.pem ec2-user@54.214.134.25 'curl http://localhost:8080/server/health'
```
#### 2.(b) Test with 2 servers
```bash
WS_URI="ws://cs6650-lb-2-1866624947.us-west-2.elb.amazonaws.com/server/chat/" ./deployment/client-setup.sh
```
#### 2.(c) Test with 4 servers
```bash
WS_URI="ws://cs6650-lb-2-1866624947.us-west-2.elb.amazonaws.com/server/chat/" \
    ./deployment/client-setup.sh
```
#### 3. Follow Client Metrics
```bash
ssh -i ~/.ssh/cs6650-assignment2.pem ec2-user@184.32.148.42 'tail -f /opt/client/client.log'
```
#### 4. Follow Consumer Metrics
```bash
ssh -i ~/.ssh/cs6650-assignment2.pem ec2-user@54.212.1.204 'tail -f /opt/consumer/consumer.log'
```

#### 5. Follow Server Metrics — publish rate + system
```bash
ssh -i ~/.ssh/cs6650-assignment2.pem ec2-user@54.214.134.25 'tail -f /opt/tomcat9/logs/catalina.out'
```

### Environment Variables

| Variable | Used by | Description |
|---|---|---|
| `RABBITMQ_HOST` | server-v2, consumer | RabbitMQ EC2 private IP |
| `RABBITMQ_PORT` | server-v2, consumer | RabbitMQ port (default: 5672) |
| `RABBITMQ_USER` | server-v2, consumer | RabbitMQ username |
| `RABBITMQ_PASS` | server-v2, consumer | RabbitMQ password |
| `RABBITMQ_EXCHANGE` | server-v2, consumer | Exchange name (default: chat.exchange) |
| `REDIS_HOST` | server-v2, consumer | ElastiCache endpoint |
| `REDIS_PORT` | server-v2, consumer | Redis port (default: 6379) |
| `SERVER_ID` | server-v2 | Instance identifier (e.g. server-1 ~ server-4) |
| `CONSUMER_THREADS` | consumer | Number of RoomConsumer threads (test: 10/20/40/80) |
| `WS_URI` | client | WebSocket ALB endpoint URL |

### AWS Infrastructure

| Component | Service | Notes |
|---|---|---|
| server-v2 | EC2 (×1~4) | Behind ALB, sticky session enabled |
| consumer | EC2 (×1) | Standalone JAR, no inbound traffic |
| RabbitMQ | EC2 (×1) | Port 5672 open to server-v2 and consumer security groups |
| Redis | ElastiCache for Redis | Port 6379 open to server-v2 and consumer security groups |
| Load Balancer | ALB | Sticky session, health check GET /health every 30s |

### Security Group Rules

**ElastiCache**
- Inbound: port 6379 from server-v2 security group, port 6379 from consumer security group
- Outbound: none required

**server-v2 EC2**
- Inbound: port 8080 from ALB security group
- Outbound: port 5672 to RabbitMQ security group, port 6379 to ElastiCache security group

**consumer EC2**
- Inbound: port 22 from your IP (SSH, optional)
- Outbound: port 5672 to RabbitMQ security group, port 6379 to ElastiCache security group

**RabbitMQ EC2**
- Inbound: port 5672 from server-v2 security group, port 5672 from consumer security group, port 15672 from your IP (management console, optional)
- Outbound: none required

**ALB**
- Inbound: port 80/443 from 0.0.0.0/0
- Outbound: port 8080 to server-v2 security group

---

## Configuration Priority

All config values follow this priority order (highest to lowest):

```
1. Environment variable   (e.g. RABBITMQ_HOST)
2. config.properties      (e.g. rabbitmq.host)
3. Hardcoded default      (e.g. "localhost")
```

This means local development works out of the box with `config.properties`,
while AWS deployment overrides via environment variables without touching any code.

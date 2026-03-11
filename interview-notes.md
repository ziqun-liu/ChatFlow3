# ChatFlow2 Interview Notes

---

## Q1: Latency 怎么算的？p99 latency 怎么算的？

### Latency 定义

**Round-trip time (RTT)**：从客户端发送消息 → 收到服务器 ACK 的时间。

```java
// SenderWorker.java
long startMs = System.currentTimeMillis();     // 发送前
String ack = client.sendAndAwaitAck(...);       // 等 server 回 ACK
long latencyMs = System.currentTimeMillis() - startMs;  // 收到 ACK 后
```

包含的链路：
```
client send → ALB → server-v2 → publish to RabbitMQ → server ACK → ALB → client receive
```

**注意**：不包括 consumer 处理（RabbitMQ→Redis→broadcast），那条链路是异步的。

### p99 怎么算

所有 latency 存在 `ConcurrentLinkedQueue<Long>`，打印报告时：

```java
// Metrics.java
long[] sorted = latencies.stream().mapToLong(Long::longValue).sorted().toArray();

long median = sorted[sorted.length / 2];
long p95    = sorted[(int)(sorted.length * 0.95)];
long p99    = sorted[(int)(sorted.length * 0.99)];
```

排序后取对应下标：
- **p99 = 173 ms** → 500,000 条消息里，99% 的消息 latency ≤ 173 ms
- **p95 = 133 ms** → 95% ≤ 133 ms
- **median = 91 ms** → 50% ≤ 91 ms

---

## Q2: 面试怎么总结这个项目？（英文）

### "Tell me about a distributed system you built."

> I built a real-time chat system called ChatFlow2 that handles high-throughput WebSocket messaging across a distributed backend.

> The architecture has four layers: clients connect via WebSocket to one of four server instances behind an AWS Application Load Balancer. Each server publishes incoming messages to RabbitMQ using a topic exchange, routing by room ID. A dedicated consumer service pulls from RabbitMQ and publishes to Redis Pub/Sub. All four server instances subscribe to Redis, so each one receives every message and broadcasts it to whichever WebSocket sessions it holds locally.

> The key design decision was using Redis Pub/Sub as the fan-out layer instead of having servers call each other directly. This keeps servers stateless with respect to message routing — no cross-server HTTP calls, no need to track which server owns which session globally.

> For reliability, the consumer uses manual RabbitMQ acknowledgements — it only acks after a successful Redis publish. On first Redis failure it requeues; on redeliver failure it discards. Malformed messages are acked immediately to avoid blocking the queue. I also added deduplication using Redis SET NX with a 30-second TTL to handle requeued messages that were already processed.

> I load tested it with 500,000 messages across 200 threads and 20 rooms. The system sustained around 2,100 messages per second with p99 latency at 173 ms and zero failures. The ALB distributed load perfectly evenly — each server handled exactly 25% of the WebSocket connections.

### Key numbers to remember

- 500K messages, 0 failures
- ~2,100 msg/s throughput
- p99 = 173 ms (client → server ACK round-trip)
- 4 server instances, 1 consumer, 20 rooms, 1 RabbitMQ, 1 Redis

### Likely follow-up questions

| Question | Answer |
|---|---|
| Why RabbitMQ instead of Kafka? | Per-room queues with push delivery; Kafka would need partition-per-room and polling |
| What's the bottleneck? | RabbitMQ publish confirm on the server path; Redis fan-out on the consumer path |
| How do you scale the consumer? | Increase `CONSUMER_THREADS` — rooms are distributed round-robin across threads |
| What happens if a server crashes? | ALB health check removes it; Redis Pub/Sub is fire-and-forget so in-flight messages to that server's sessions are lost |
| How do you handle duplicate messages? | Redis `SET NX EX 30` on `messageId` before publishing |

---

## Q3: Testing Load Distribution — how to verify?

### 1. Monitor connection distribution

```bash
for host in 54.214.134.25 16.146.124.128 54.214.104.80 34.222.11.122; do
  echo "=== $host ==="
  ssh -i ~/.ssh/cs6650-assignment2.pem ec2-user@$host \
    "sudo grep -c 'GET /server/chat/' /opt/tomcat9/logs/localhost_access_log.*.txt 2>/dev/null || echo 0"
done
```

Result: each server got 10 WebSocket upgrade requests → perfectly even distribution.

### 2. Verify sticky session behavior

WebSocket is a persistent TCP connection — once the upgrade completes, all frames go to the
same server. Stickiness is inherent, no ALB cookie needed.

Verify: pick a session ID from the logs and confirm it only appears on one server.

```bash
SESSION_ID="abc123"
for host in 54.214.134.25 16.146.124.128 54.214.104.80 34.222.11.122; do
  echo "=== $host ==="
  ssh -i ~/.ssh/cs6650-assignment2.pem ec2-user@$host \
    "sudo grep '$SESSION_ID' /opt/tomcat9/logs/catalina.out | wc -l"
done
# Only one server will show a non-zero count
```

### 3. Test failover scenarios

```bash
# Kill one server
ssh -i ~/.ssh/cs6650-assignment2.pem ec2-user@54.214.134.25 \
  "sudo /opt/tomcat9/bin/shutdown.sh"

# Run load test — watch "Reconnections" counter
WS_URI="ws://cs6650-assignment2-lb-1697352352.us-west-2.elb.amazonaws.com/server/chat/" \
  java -jar target/client-1.0-SNAPSHOT.jar

# Restore
ssh -i ~/.ssh/cs6650-assignment2.pem ec2-user@54.214.134.25 \
  "sudo /opt/tomcat9/bin/startup.sh"
```

Expected: ALB detects unhealthy instance, new connections route to remaining 3 servers,
client reconnects automatically, 0 failed messages.

### 4. Measure latency impact

```bash
# Baseline: 4 servers
WS_URI="..." java -jar target/client-1.0-SNAPSHOT.jar 2>&1 | grep "p99"

# Degraded: 3 servers (after killing one)
WS_URI="..." java -jar target/client-1.0-SNAPSHOT.jar 2>&1 | grep "p99"
```

Compare p99 between runs — degraded mode should show slightly higher latency due to
increased load per server.

### Summary table

| Test | Method | Expected |
|---|---|---|
| Connection distribution | grep WebSocket upgrade count per server | Equal count on all 4 |
| Sticky session | grep session ID across servers | Only 1 server has it |
| Failover | kill Tomcat + run load test | Reconnections > 0, failures = 0 |
| Latency impact | p99 with 4 vs 3 servers | p99 slightly higher with 3 |

---

## Q4: Failover Test Results

### Setup
Killed server-1 (54.214.134.25) mid load test while 500,000 messages were in flight.

### Results

| Metric | Normal (4 servers) | Failover (3 servers) |
|---|---|---|
| Throughput | 2,170 msg/s | 2,176 msg/s |
| p95 latency | 133 ms | 131 ms |
| p99 latency | 173 ms | 168 ms |
| Max latency | 495 ms | 772 ms |
| Failed messages | 0 | 0 |
| Reconnections | 0 | 7 |
| Retries | 0 | 60 |

### Interpretation

- **Reconnections: 7** — 7 WebSocket connections dropped when server-1 went down; client reconnected to surviving servers automatically.
- **Retries: 60** — 60 messages were in-flight during the disconnect, failed on first attempt, succeeded on retry.
- **0 failed messages** — no data loss despite a server going down mid-test.
- **p99/p95 nearly identical** — the 3 remaining servers absorbed the load without degradation.
- **Max latency 495→772 ms** — spike caused by the reconnection event, not steady-state load.
- **Throughput unaffected** — system continued at ~2,175 msg/s throughout the failure.

### Conclusion
The system handles server failure gracefully: zero message loss, automatic reconnection, and negligible latency impact under failover conditions.

---

## Q5: 你们做了哪些优化，把吞吐量从 ~2,000/s 提升到 ~6,000/s？

### 第一阶段：找到真正的瓶颈（吞吐量卡在 ~2,167/s）

调了很多参数（`numSenders`、`CONSUMER_THREADS`、`REDIS_POOL_MAX_TOTAL`），吞吐量完全没变化。

**根因（Little's Law）**：`ChannelPool=20`，每个 channel 同步 `waitForConfirms`，`deliveryMode=2` 要写 EBS 磁盘再 confirm。**磁盘 IOPS 是天花板**，其他参数调多少都没用。

### 第二阶段：消灭 EBS 磁盘瓶颈（~2,167/s → ~5,165/s，+138%）

**改动**：`RABBITMQ_DELIVERY_MODE` 2 → 1（transient，消息只存内存，不写磁盘）

- `MessagePublisher.java`：`.deliveryMode(RabbitMQConfig.DELIVERY_MODE)`
- `deploy-all.sh`：`export RABBITMQ_DELIVERY_MODE="1"`

同期配置化改造（消除硬编码）：
- `ChannelPool` 大小硬编码 20 → 读 `RABBITMQ_CHANNEL_POOL_SIZE`（设为 100）
- `JedisPool` 从默认 8 连接 → 显式配置 `REDIS_POOL_MAX_TOTAL`
- Consumer `PREFETCH_COUNT` 硬编码 10 → 读 `RABBITMQ_PREFETCH`（设为 100）

### 第三阶段：消灭互联网 RTT 瓶颈（Client 迁到 EC2）

Mac 本地跑 client，互联网 RTT ~80ms，512 workers / 97ms ≈ 5,165/s 是天花板。

**改动**：新建 `deployment/client-setup.sh`，把 client 部署到 AWS us-west-2 同 region EC2。

- Min latency 从 ~80ms 降到 ~1ms（VPC 内部）
- 吞吐量维持 ~4,694/s（此时 RabbitMQ t3.micro 成为新瓶颈）

### 第四阶段：升级 RabbitMQ 实例 + 修复 Consumer 双倍 Redis 开销（→ ~6,000+/s）

**问题 1**：RabbitMQ t3.micro（1 vCPU）扛 400 个并发 channel（4 server × 100），CPU 成为瓶颈。
→ RabbitMQ EC2 升级到 **t3.small**（2 vCPU，2 GB RAM）

**问题 2**：Consumer 每条消息做 **2 次阻塞 Redis 调用**：
- `isDuplicate()` → Redis SET NX（第一次投递根本不可能重复，纯粹浪费）
- `publish()` → Redis PUBLISH

→ `DeliveryHandler.java`：加 `isRedeliver` 守卫，只在重投递时才调 `isDuplicate()`

```java
// Before
if (redisPublisher.isDuplicate(msg.getMessageId())) { ... }

// After
if (isRedeliver && redisPublisher.isDuplicate(msg.getMessageId())) { ... }
```

**问题 3**：Consumer 线程不够。
→ `CONSUMER_THREADS` 20 → 40，`REDIS_POOL_MAX_TOTAL` 40 → 80

### 汇总

| 阶段 | 核心改动 | 吞吐量 |
|---|---|---|
| 基线 | deliveryMode=2, ChannelPool=20 | ~2,167/s |
| deliveryMode=1 + ChannelPool=100 | 消灭 EBS 磁盘瓶颈 | ~5,165/s |
| Client 迁到 EC2 | 消灭互联网 RTT | ~4,694/s |
| RabbitMQ t3.small + isDuplicate 优化 + threads×2 | 消灭 broker CPU + Consumer Redis 双倍开销 | ~6,000+/s |

### 面试关键句

> "The bottleneck wasn't where I thought. Tuning thread counts and pool sizes had zero effect because the real ceiling was EBS disk IOPS — RabbitMQ's persistent delivery mode required a disk write before every confirm. Switching to transient mode tripled throughput instantly. The next bottleneck was client-side network RTT, which I eliminated by moving the load tester into the same AWS region. Finally I found the consumer was making two sequential blocking Redis calls per message — a dedup check that was completely unnecessary on first delivery. Guarding it with `isRedeliver` cut per-message Redis work in half."

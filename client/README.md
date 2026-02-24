# ChatFlow Client — Design & Algorithm

## Overview

A multithreaded WebSocket load-testing client that sends **500,000 chat messages** to a remote
server and collects latency and throughput metrics. The core design goal is **maximum throughput**
via connection pipelining: multiple threads share each WebSocket connection and send messages
concurrently without waiting for each other's ACKs.

---

## Code Structure

```
client/src/main/java/assign1/client/
├── ClientMain.java               Entry point; orchestrates warmup and main phase
├── ClientEndpoint.java           WebSocket connection with pipelined ACK queue
├── connection/
│   └── ConnectionManager.java   Maintains 1 connection per room + semaphore per room
├── sender/
│   └── SenderWorker.java        Runnable: dequeues messages, sends, records metrics
├── producer/
│   └── MessageGenerator.java    Runnable: generates random ChatMessages into queues
├── model/
│   └── ChatMessage.java         Message data model + JSON serialization
└── metrics/
    └── Metrics.java             Thread-safe stats: latency, throughput, CSV output
```

---

## Threading Model

```
                    ┌─────────────────────────────────────────────┐
                    │              ClientMain                      │
                    │                                             │
                    │  MessageGenerator (1 thread)                │
                    │    └─► per-room BlockingQueue[20]           │
                    │                    │                        │
                    │  SenderWorker pool (200 threads)            │
                    │    10 workers × 20 rooms                    │
                    │    each worker polls its room's queue       │
                    └─────────────────────────────────────────────┘
                                         │
                    ┌────────────────────▼────────────────────────┐
                    │           ConnectionManager                  │
                    │                                             │
                    │  Room 1 ──► ClientEndpoint  Semaphore(50)   │
                    │  Room 2 ──► ClientEndpoint  Semaphore(50)   │
                    │  ...                                        │
                    │  Room 20 ► ClientEndpoint  Semaphore(50)    │
                    └─────────────────────────────────────────────┘
                                         │  WebSocket (ws://)
                    ┌────────────────────▼────────────────────────┐
                    │              EC2 Server (Tomcat)             │
                    └─────────────────────────────────────────────┘
```

---

## Algorithm

### Phase 1 — Warmup (32,000 messages)

Purpose: warm up the server's JIT and connection state before the measured run.

1. Start 1 `MessageGenerator` thread → fills a single shared `LinkedBlockingQueue` with 32,000 messages.
2. Start 32 sender threads, each assigned to room `threadId % 20 + 1`.
3. Each thread loops 1,000 times:
   - `connMgr.acquire(roomId)` — take a semaphore permit (max 50 concurrent per room)
   - `connMgr.conn(roomId)` — get the single WebSocket for that room (lazy-created on first call)
   - `endpoint.sendAndAwaitAck(json, 5000ms)` — send and block until server echoes back
   - `connMgr.release(roomId)` — return the permit
4. After all 32 threads finish: `connMgr.closeAll()` shuts down all WebSocket threads, print summary.

### Phase 2 — Main Phase (500,000 messages)

1. **Message generation** (`MessageGenerator`):
   - Single dedicated thread generates random `ChatMessage` objects.
   - Routes each message into the per-room queue: `queues.get(roomId).put(msg)`.
   - Blocks on `put()` if a room's queue is full — back-pressure, queue capacity = 10,000.

2. **Sending** (`SenderWorker`, 200 threads, 10 per room):
   - Each worker polls its room's queue (`poll` with 2s timeout).
   - On receiving a message, calls `sendWithRetry(msg)`.
   - Exits when it receives the sentinel `ChatMessage.POISON`.

3. **Pipelining** — `sendWithRetry` per message:
   ```
   for attempt in 0..4:
     acquire semaphore(roomId)       ← blocks if 50 already in-flight on this connection
     try:
       client = conn(roomId)
       ack = client.sendAndAwaitAck(json, 5000ms)
       if ack != null:
         record latency, statusCode, roomId → Metrics
         return                       ← success
       else:
         reconnect(roomId)            ← timeout: replace the connection
     catch Exception:
       reconnect(roomId)              ← transport error: replace the connection
     finally:
       release semaphore(roomId)      ← always free the slot
     sleep(100ms × 2^attempt)        ← exponential backoff before retry
   record failure
   ```

4. **Shutdown**:
   - Generator finishes → main thread puts one `POISON` per sender thread into each room's queue.
   - Workers drain their queue, exit on POISON, executor shuts down.
   - `connMgr.closeAll()` calls `closeBlocking()` on all 20 connections — joins their internal
     threads so the JVM exits cleanly (without this, WebSocket threads are non-daemon and hang).

---

## Key Design Decisions

### Pipelining with `ArrayBlockingQueue` as ACK queue

```
ClientEndpoint
  ackQueue: ArrayBlockingQueue<String>(50)   ← capacity = max in-flight per connection

  onMessage(msg)  → ackQueue.offer(msg)      ← server echo arrives; any waiter unblocks
  onClose/onError → ackQueue.clear()         ← unblock all waiting threads immediately

  sendAndAwaitAck(json, timeoutMs):
    send(json)                               ← put message on the wire (non-blocking)
    return ackQueue.poll(timeoutMs)          ← block until an ACK arrives (or timeout)
```

Multiple threads share **one connection per room**. Per-message ID matching is not needed — the
server echoes messages in order on a single connection, so any ACK correctly satisfies the
longest-waiting sender on that connection.

### Semaphore as in-flight limiter

```
ConnectionManager
  semaphores: Map<roomId → Semaphore(50)>
```

The semaphore caps concurrent senders at 50 per connection, which matches the `ackQueue` capacity.
This prevents the ACK queue from overflowing and limits server-side backlog per room.

### Little's Law — throughput sizing

```
Throughput = Concurrency / Latency
           = (20 rooms × 10 threads/room) / 0.004 s/msg
           = 50,000 msg/s  (theoretical ceiling on <1ms loopback)
```

On EC2 t2.micro with server on same host, observed latency ≈ 4 ms and CPU is shared, giving
~7,000–10,000 msg/s in practice.

### LongAdder over AtomicLong for counters

Under 200 concurrent threads, `AtomicLong.incrementAndGet()` causes CAS contention on a single
memory location. `LongAdder` maintains per-CPU striped cells and sums them on `sum()` — near-zero
contention at the cost of a slightly stale read, which is acceptable for metrics.

### Producer/Consumer with back-pressure

`MessageGenerator` calls `queue.put(msg)` which blocks when the queue is full (capacity 10,000).
This prevents the generator from running far ahead of the senders, bounding memory use to at most
`20 rooms × 10,000 messages × ~200 bytes ≈ 40 MB`.

### Poison pill shutdown

Rather than interrupting threads, the main thread enqueues one `ChatMessage.POISON` sentinel per
worker per room after the generator finishes. Workers exit cleanly on receipt, completing any
in-flight message first.

---

## Retry & Exponential Backoff

- Up to **5 attempts** per message.
- On timeout (null ACK): reconnect the connection, then sleep `100ms × 2^attempt` (100, 200, 400, 800, 1600 ms).
- On exception (transport error): same reconnect + backoff.
- Backoff prevents a "retry storm" — if the server is overloaded, spreading retries over time
  lets it recover rather than compounding the load.

---

## Throughput & Latency Results (EC2 t2.micro, client + server on same instance)

| Metric | Warmup | Main Phase |
|---|---|---|
| Messages | 32,000 | 500,000 |
| Wall time | 4.5 s | 70 s |
| Throughput | 7,176 msg/s | 7,134 msg/s |
| Mean latency | — | 4.18 ms |
| Median latency | — | 4 ms |
| p95 latency | — | 9 ms |
| p99 latency | — | 15 ms |
| Failures | 0 | 0 |
| Connections | 20 | 20 |
| Reconnections | 0 | 0 |

---

## Output Files

| File | Contents |
|---|---|
| `results/metrics.csv` | One row per message: `timestamp_ms, messageType, latency_ms, statusCode, roomId` |
| stdout | Summary table, latency percentiles, per-room throughput, ASCII throughput chart (10s buckets) |

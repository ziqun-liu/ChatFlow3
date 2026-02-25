## Architecture
```text
┌─────────────────────────────────────────────────────────────────────┐
│                          Load Test Client                           │
│                                                                     │
│  MessageGenerator → LinkedBlockingQueue → SenderWorker × N          │
│                                               │                     │
│                                          WebSocket                  │
└───────────────────────────────────────────────│─────────────────────┘
                                                │
                                                ▼
┌─────────────────────────────────────────────────────────────────────┐
│                     AWS Application Load Balancer                   │
└──────────┬──────────────────┬──────────────────┬────────────────────┘
           │                  │                  │
           ▼                  ▼                  ▼
    ┌──────────────┐   ┌──────────────┐   ┌──────────────┐
    │  server-v2   │   │  server-v2   │   │  server-v2   │
    │  (EC2-A1)    │   │  (EC2-A2)    │   │  (EC2-A3)    │
    │              │   │              │   │              │
    │ ServerEndpt  │   │ ServerEndpt  │   │ ServerEndpt  │
    │ ChannelPool  │   │ ChannelPool  │   │ ChannelPool  │
    │ MsgPublisher │   │ MsgPublisher │   │ MsgPublisher │
    └──────┬───────┘   └──────┬───────┘   └──────┬───────┘
           │                  │                  │
           └──────────────────┴──────────────────┘
                              │ publish
                              │ routing key: room.{roomId}
                              ▼
┌─────────────────────────────────────────────────────────────────────┐
│                       RabbitMQ (EC2-B)                              │
│                                                                     │
│   Exchange: chat.exchange (topic)                                   │
│                                                                     │
│   room.1  room.2  room.3  ...  room.20                              │
│   [████]  [████]  [████]  ...  [████]                               │
└──────────────────────────────┬──────────────────────────────────────┘
                               │ consume
                               ▼
┌─────────────────────────────────────────────────────────────────────┐
│                        consumer (EC2-C)                             │
│                                                                     │
│   ConsumerMain                                                      │
│       └── ConsumerPool                                              │
│               ├── RoomConsumer thread (room.1  ~ room.5 )           │
│               ├── RoomConsumer thread (room.6  ~ room.10)           │
│               ├── RoomConsumer thread (room.11 ~ room.15)           │
│               └── RoomConsumer thread (room.16 ~ room.20)           │
│                         │                                           │
│                         ▼                                           │
│               RoomSessionRegistry                                   │
│               ConcurrentHashMap<roomId, Set<Session>>               │
│                         │                                           │
│                         ▼                                           │
│               WebSocketBroadcaster                                  │
└──────────────────────────────┬──────────────────────────────────────┘
                               │ broadcast
                               ▼
                    WebSocket Clients (chat users)
```
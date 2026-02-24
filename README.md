## Deploy server WAR on EC2

##### local, ssh into EC2

```bash
ssh -i cs6650-assignment1-key.pem ec2-user@54.148.180.35
```

##### local ChatFlowExample/

```bash
cd server/
mvn clean install
cd ..
mv target/server-1.0-SNAPSHOT.war target/server.war
```

##### local, secure copy WAR to ec2 /tmp/

```bash
scp -i cs6650-assignment1-key.pem ChatFlowExample/server/target/server.war ec2-user@54.148.180.35:/tmp/
```

##### eec2-user, clean previous deployments

```bash
sudo rm -rf /opt/tomcat9/webapps/server*
sudo rm -rf /opt/tomcat9/work/Catalina/localhost/server*
sudo rm -rf /opt/tomcat9/temp/
sudo rm -rf /opt/tomcat9/work/
```

##### ec2-user, copy WAR from /tmp/ to /webapps/

```bash
sudo cp /tmp/server.war /opt/tomcat9/webapps/
```

##### ec2-user, set permissions

```bash
sudo chown ec2-user:ec2-user /opt/tomcat9/webapps/server.war
```

##### ec2-user, start Tomcat

```bash
/opt/tomcat9/bin/startup.sh
```

##### ec2-user, check Tomcat status

```bash
ps aux | grep tomcat
```

##### ec2-user, check port 8080 is listening

```bash
netstat -tlnp | grep 8080
```

##### ec2-user, verify deployment

```bash
ls -la /opt/tomcat9/webapps/
```

#####  ec2-user, live catalina log

```bash
sudo tail -f /opt/tomcat9/logs/catalina.out
```

## Run Client

#### local, ChatFlowExample/

```bash
cd client
mvn clean install

java -jar ./target/client-1.0-SNAPSHOT.jar
```

## Test

##### local

```bash
curl http://54.148.180.35:8080/server/health
```

## Close Tomcat and exit EC2 instance

#### ec2-user

```bash
sudo /opt/tomcat9/bin/shutdown.sh
exit
```




## Architecture
```text
┌─────────────────────────────────────────────────────────────────┐
│                         CLIENT (JVM)                            │
│                                                                 │
│  ┌─────────────────┐      ┌──────────────────────────────────┐  │
│  │ MessageGenerator│      │         Warmup Phase             │  │
│  │  (1 thread)     │─────▶│  32 threads × 1000 msgs          │  │
│  │                 │      │  1 shared queue                  │  │
│  │  500K messages  │      │  32 endpoints (closed after)     │  │
│  │  50 msg pool    │      └──────────────────────────────────┘  │
│  │  20 rooms       │                                            │
│  │  90/5/5 dist.   │      ┌──────────────────────────────────┐  │
│  │                 │─────▶│         Main Phase               │  │
│  └─────────────────┘      │  120 SenderWorker threads        │  │
│                           │  20 per-room BlockingQueues      │  │
│                           │  6 workers per room              │  │
│                           └────────────┬─────────────────────┘  │
│                                        │                        │
│                           ┌────────────▼─────────────────────┐  │
│                           │       ConnectionManager          │  │
│                           │  pool: roomId →                  │  │
│                           │    BlockingQueue<ClientEndpoint> │  │
│                           │  max 6 endpoints per room        │  │
│                           │  conn / release / reconn         │  │
│                           └────────────┬─────────────────────┘  │
│                                        │                        │
│                           ┌────────────▼─────────────────────┐  │
│                           │        ClientEndpoint            │  │
│                           │  sendAndWait(messageId, json)    │  │
│                           │  CountDownLatch for round-trip   │  │
│                           │  onMessage: match messageId      │  │
│                           └────────────┬─────────────────────┘  │
│                                        │                        │
│                           ┌────────────▼─────────────────────┐  │
│                           │           Metrics                │  │
│                           │  success / failure / latency     │  │
│                           │  per-room throughput             │  │
│                           │  mean/median/p95/p99/min/max     │  │
│                           └──────────────────────────────────┘  │
└─────────────────────────────┬───────────────────────────────────┘
│  WebSocket ws://host:8080/server/chat/{roomId}
│  (persistent connections, JSON messages)
┌─────────────────────────────▼───────────────────────────────────┐
│                    SERVER (Tomcat on EC2)                        │
│                                                                 │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │                   ServerEndpoint                         │   │
│  │  /chat/{roomId}                                          │   │
│  │                                                          │   │
│  │  rooms: ConcurrentHashMap<roomId, Set<Session>>          │   │
│  │                                                          │   │
│  │  onMessage:                                              │   │
│  │    1. parse JSON → ChatMessageDto                        │   │
│  │    2. validate (userId / username / message /            │   │
│  │                 timestamp / messageType)                 │   │
│  │    3a. valid   → ChatResponse(OK)  → broadcast to room   │   │
│  │    3b. invalid → ChatResponse(ERROR) → sender only       │   │
│  └──────────────────────────────────────────────────────────┘   │
│                                                                 │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │                   HealthServlet                          │   │
│  │  GET /health → 200 OK                                    │   │
│  └──────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
```
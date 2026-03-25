.PHONY: build up down test run rabbitmq-init \
        test1 test2 test3 batch-tune \
        restart-server restart-consumer \
        logs-server logs-consumer clean setup

MYSQL_CONNECTOR_JAR = docker/lib/mysql-connector-j-8.3.0.jar
MYSQL_CONNECTOR_URL = https://repo1.maven.org/maven2/com/mysql/mysql-connector-j/8.3.0/mysql-connector-j-8.3.0.jar

# ── Full pipeline ────────────────────────────────────────────────────────────

run: build up rabbitmq-init test
run-test1: build up rabbitmq-init test1
run-test2: build up rabbitmq-init test2
run-test3: build up rabbitmq-init test3
run-batch-tune: build up rabbitmq-init batch-tune

# ── Build ────────────────────────────────────────────────────────────────────

build:
	cd server-v2   && mvn clean package -q
	cd consumer-v3 && mvn clean package -q
	cd client      && mvn clean package -q

# ── Infrastructure ───────────────────────────────────────────────────────────

setup: $(MYSQL_CONNECTOR_JAR)

$(MYSQL_CONNECTOR_JAR):
	mkdir -p docker/lib
	curl -sL $(MYSQL_CONNECTOR_URL) -o $(MYSQL_CONNECTOR_JAR)

up: setup
	docker compose up -d --wait

down:
	docker compose down
	rm -rf docker/lib

rabbitmq-init:
	RABBITMQ_HOST=localhost RABBITMQ_USER=guest RABBITMQ_PASS=guest \
	  ./deployment/rabbitmq-setup.sh

# ── Load tests ───────────────────────────────────────────────────────────────

test:
	java -jar client/target/client-1.0-SNAPSHOT.jar

# Test 1: Baseline — 500K messages
test1:
	DB_BATCH_SIZE=500 DB_FLUSH_INTERVAL_MS=500 CONSUMER_THREADS=10 RABBITMQ_PREFETCH=50 \
	  docker compose up -d --force-recreate consumer
	sleep 5
	TOTAL_MESSAGES=500000 java -jar client/target/client-1.0-SNAPSHOT.jar

# Test 2: Stress — 1M messages
test2:
	DB_BATCH_SIZE=1000 DB_FLUSH_INTERVAL_MS=500 CONSUMER_THREADS=10 RABBITMQ_PREFETCH=100 \
	  docker compose up -d --force-recreate consumer
	sleep 5
	TOTAL_MESSAGES=1000000 java -jar client/target/client-1.0-SNAPSHOT.jar

# Test 3: Endurance — 4x 500K (~30 min)
test3:
	DB_BATCH_SIZE=500 DB_FLUSH_INTERVAL_MS=500 CONSUMER_THREADS=10 RABBITMQ_PREFETCH=50 \
	  docker compose up -d --force-recreate consumer
	sleep 5
	for i in 1 2 3 4; do \
	  echo "=== Endurance Run $$i/4 ==="; \
	  TOTAL_MESSAGES=500000 java -jar client/target/client-1.0-SNAPSHOT.jar; \
	  sleep 5; \
	done

# Batch size tuning — 4 runs with DB_BATCH_SIZE=100/500/1000/5000
batch-tune:
	for size in 100 500 1000 5000; do \
	  echo "=== Batch size: $$size ==="; \
	  DB_BATCH_SIZE=$$size docker compose up -d --force-recreate consumer; \
	  sleep 5; \
	  TOTAL_MESSAGES=500000 java -jar client/target/client-1.0-SNAPSHOT.jar; \
	done

# ── Daily dev (rebuild + restart one service) ────────────────────────────────

restart-server:
	cd server-v2 && mvn clean package -q
	docker compose restart server

restart-consumer:
	cd consumer-v3 && mvn clean package -q
	docker compose restart consumer

# ── Logs ─────────────────────────────────────────────────────────────────────

logs-server:
	docker logs -f server

logs-consumer:
	docker logs -f consumer

# ── Clean ────────────────────────────────────────────────────────────────────

clean:
	cd server-v2   && mvn clean -q
	cd consumer-v3 && mvn clean -q
	cd client      && mvn clean -q

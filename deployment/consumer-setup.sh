#!/bin/bash
# consumer-setup.sh
# Builds consumer-v3 JAR and deploys to the consumer EC2 instance.
#
# Usage:
#   chmod +x consumer-setup.sh
#   ./consumer-setup.sh
#
# Prerequisites on consumer EC2:
#   - Java 11+ installed
#   - ec2-user has write access to /opt/consumer

set -e

# ── Configuration ────────────────────────────────────────────────────────────
SSH_KEY="~/.ssh/cs6650-assignment2.pem"
EC2_USER="ec2-user"
CONSUMER_HOST="54.212.1.204"   # Replace with public IP of consumer EC2 instance

# Inherited from deploy-all.sh (or export before calling directly):
# RABBITMQ_HOST, RABBITMQ_USER, RABBITMQ_PASS, REDIS_HOST, REDIS_PORT,
# CONSUMER_THREADS, RABBITMQ_PREFETCH, REDIS_POOL_MAX_TOTAL, REDIS_POOL_MAX_WAIT_MS
# DB_HOST, DB_PORT, DB_NAME, DB_USER, DB_PASS,
# DB_BATCH_SIZE, DB_FLUSH_INTERVAL_MS, DB_POOL_SIZE, DB_WRITER_THREADS

CONSUMER_DIR="$(cd "$(dirname "$0")/../consumer-v3" && pwd)"
JAR_NAME="consumer-v3-1.0-SNAPSHOT.jar"
LOCAL_JAR="$CONSUMER_DIR/target/$JAR_NAME"
REMOTE_DIR="/opt/consumer"
# ─────────────────────────────────────────────────────────────────────────────

echo "======================================"
echo " Step 1: Build consumer-v3 JAR"
echo "======================================"
cd "$CONSUMER_DIR"
mvn clean package -q
echo "Build complete: $LOCAL_JAR"

echo ""
echo "======================================"
echo " Step 2: Deploy to consumer EC2 ($CONSUMER_HOST)"
echo "======================================"

echo "  Uploading $JAR_NAME..."
ssh -i "$SSH_KEY" -o StrictHostKeyChecking=no "$EC2_USER@$CONSUMER_HOST" \
  "sudo mkdir -p $REMOTE_DIR && sudo chown $EC2_USER:$EC2_USER $REMOTE_DIR"

scp -i "$SSH_KEY" -o StrictHostKeyChecking=no \
  "$LOCAL_JAR" \
  "$EC2_USER@$CONSUMER_HOST:$REMOTE_DIR/$JAR_NAME"

echo ""
echo "======================================"
echo " Step 3: Start consumer-v3 on EC2"
echo "======================================"

ssh -i "$SSH_KEY" -o StrictHostKeyChecking=no "$EC2_USER@$CONSUMER_HOST" bash <<EOF
  set -e

  echo "  Stopping existing consumer (if any)..."
  pkill -f "consumer.*SNAPSHOT.jar" 2>/dev/null || true
  sleep 2

  echo "  Starting consumer-v3..."
  nohup env \
    RABBITMQ_HOST="$RABBITMQ_HOST" \
    RABBITMQ_PORT="5672" \
    RABBITMQ_USER="$RABBITMQ_USER" \
    RABBITMQ_PASS="$RABBITMQ_PASS" \
    REDIS_HOST="$REDIS_HOST" \
    REDIS_PORT="$REDIS_PORT" \
    CONSUMER_THREADS="$CONSUMER_THREADS" \
    RABBITMQ_PREFETCH="$RABBITMQ_PREFETCH" \
    REDIS_POOL_MAX_TOTAL="$REDIS_POOL_MAX_TOTAL" \
    REDIS_POOL_MAX_WAIT_MS="$REDIS_POOL_MAX_WAIT_MS" \
    DB_HOST="$DB_HOST" \
    DB_PORT="${DB_PORT:-3306}" \
    DB_NAME="${DB_NAME:-chatflow}" \
    DB_USER="$DB_USER" \
    DB_PASS="$DB_PASS" \
    DB_BATCH_SIZE="${DB_BATCH_SIZE:-500}" \
    DB_FLUSH_INTERVAL_MS="${DB_FLUSH_INTERVAL_MS:-500}" \
    DB_POOL_SIZE="${DB_POOL_SIZE:-10}" \
    DB_WRITER_THREADS="${DB_WRITER_THREADS:-3}" \
    java -jar "$REMOTE_DIR/$JAR_NAME" \
    > "$REMOTE_DIR/consumer.log" 2>&1 &

  echo "  Consumer PID: \$!"
  echo \$! > "$REMOTE_DIR/consumer.pid"

  sleep 3
  if kill -0 \$(cat "$REMOTE_DIR/consumer.pid") 2>/dev/null; then
    echo "  Consumer-v3 started successfully."
    echo "  Logs: $REMOTE_DIR/consumer.log"
  else
    echo "  Consumer-v3 failed to start. Last log lines:"
    tail -20 "$REMOTE_DIR/consumer.log"
    exit 1
  fi
EOF

echo ""
echo "======================================"
echo " Deployment complete"
echo "======================================"
echo "  Consumer running on: $CONSUMER_HOST"
echo "  Logs: ssh -i $SSH_KEY $EC2_USER@$CONSUMER_HOST 'tail -f $REMOTE_DIR/consumer.log'"
echo "  Stop: ssh -i $SSH_KEY $EC2_USER@$CONSUMER_HOST 'pkill -f $JAR_NAME'"
#!/bin/bash
# client-setup.sh
# Builds client JAR and runs load test on a dedicated EC2 instance.
# Results are saved to /opt/client/client.log on EC2.
#
# Usage:
#   chmod +x client-setup.sh
#   ./client-setup.sh
#
# Prerequisites on client EC2:
#   - Java 11 installed: sudo yum install -y java-11-amazon-corretto
#   - ec2-user has write access to /opt/client

set -e

# ── Configuration ────────────────────────────────────────────────────────────
SSH_KEY="~/.ssh/cs6650-assignment2.pem"
EC2_USER="ec2-user"
CLIENT_HOST="184.32.148.42"

# WS_URI inherited from deploy-all.sh (or must be exported before calling directly)

CLIENT_DIR="$(cd "$(dirname "$0")/../client" && pwd)"
JAR_NAME="client-1.0-SNAPSHOT.jar"
LOCAL_JAR="$CLIENT_DIR/target/$JAR_NAME"
REMOTE_DIR="/opt/client"
# ─────────────────────────────────────────────────────────────────────────────

echo "======================================"
echo " Step 1: Build client JAR"
echo "======================================"
cd "$CLIENT_DIR"
mvn clean package -q
echo "Build complete: $LOCAL_JAR"

echo ""
echo "======================================"
echo " Step 2: Deploy to client EC2 ($CLIENT_HOST)"
echo "======================================"

ssh -i "$SSH_KEY" -o StrictHostKeyChecking=no "$EC2_USER@$CLIENT_HOST" \
  "sudo mkdir -p $REMOTE_DIR && sudo chown $EC2_USER:$EC2_USER $REMOTE_DIR"

scp -i "$SSH_KEY" -o StrictHostKeyChecking=no \
  "$LOCAL_JAR" \
  "$EC2_USER@$CLIENT_HOST:$REMOTE_DIR/$JAR_NAME"

echo ""
echo "======================================"
echo " Step 3: Run load test on EC2"
echo "======================================"

ssh -i "$SSH_KEY" -o StrictHostKeyChecking=no "$EC2_USER@$CLIENT_HOST" bash <<EOF
  set -e

  # Kill any existing client process
  pkill -f "$JAR_NAME" 2>/dev/null || true
  sleep 1

  echo "  Starting load test..."
  nohup env \
    WS_URI="$WS_URI" \
    java -jar "$REMOTE_DIR/$JAR_NAME" \
    > "$REMOTE_DIR/client.log" 2>&1 &

  echo "  Client PID: \$!"
  echo \$! > "$REMOTE_DIR/client.pid"
  echo "  Logs: $REMOTE_DIR/client.log"
EOF

echo ""
echo "======================================"
echo " Load test started on: $CLIENT_HOST"
echo "======================================"
echo "  Monitor (live):  ssh -i $SSH_KEY $EC2_USER@$CLIENT_HOST 'tail -f $REMOTE_DIR/client.log'"
echo "  Final results:   ssh -i $SSH_KEY $EC2_USER@$CLIENT_HOST 'cat $REMOTE_DIR/client.log'"
echo "  Stop:            ssh -i $SSH_KEY $EC2_USER@$CLIENT_HOST 'pkill -f $JAR_NAME'"

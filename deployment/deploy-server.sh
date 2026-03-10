#!/bin/bash
# deploy-server.sh
# Builds server-v2 WAR and deploys to all EC2 instances.
#
# Usage:
#   chmod +x deploy-server.sh
#   ./deploy-server.sh
#
# Prerequisites on each EC2:
#   - Tomcat installed at /opt/tomcat
#   - ec2-user has sudo permission to start/stop Tomcat
#   - SSH key has access to all instances

set -e  # exit on any error

# Variables: SSH_KEY, EC2_HOSTS, RABBITMQ_HOST
# ── Configuration (assign variables) ────────────────────────
SSH_KEY="~/.ssh/cs6650-assignment2.pem"

EC2_USER="ec2-user"

EC2_HOSTS=(
  "54.214.134.25"                      # Replace with public IP of server-v2 EC2 instance 1
  "16.146.124.128"                     # Replace with public IP of server-v2 EC2 instance 2
  "54.214.104.80"                      # Replace with public IP of server-v2 EC2 instance 3
  "34.222.11.122"                      # Replace with public IP of server-v2 EC2 instance 4
)

TOMCAT_DIR="/opt/tomcat9"
TOMCAT_WEBAPPS="$TOMCAT_DIR/webapps"
WAR_NAME="server.war"
SERVER_V2_DIR="$(cd "$(dirname "$0")/../server-v2" && pwd)"
LOCAL_WAR="$SERVER_V2_DIR/target/$WAR_NAME"

# RABBITMQ_HOST, RABBITMQ_USER, RABBITMQ_PASS, REDIS_HOST, REDIS_PORT
# are inherited from deploy-all.sh (or must be exported before calling this script directly)
# ───────────────────────────────────────────────────────────────────────────

echo "======================================"
echo " Step 1: Build server-v2 WAR"
echo "======================================"
cd "$SERVER_V2_DIR"
mvn clean package -q
# Rename to server.war for consistent deployment path
mv target/server-v2-1.0-SNAPSHOT.war target/server.war 2>/dev/null || true
echo "Build complete: $LOCAL_WAR"

echo ""
echo "======================================"
echo " Step 2: Deploy to EC2 instances"
echo "======================================"

for i in "${!EC2_HOSTS[@]}"; do
  HOST="${EC2_HOSTS[$i]}"
  SERVER_ID="server-$((i + 1))"
  echo ""
  echo "--- Deploying to $SERVER_ID ($HOST) ---"

  # Copy WAR to EC2
  echo "  Uploading $WAR_NAME..."
  scp -i "$SSH_KEY" -o StrictHostKeyChecking=no \
    "$LOCAL_WAR" \
    "$EC2_USER@$HOST:/tmp/$WAR_NAME"

  # Remote: stop Tomcat, deploy WAR, set env vars, start Tomcat
  ssh -i "$SSH_KEY" -o StrictHostKeyChecking=no "$EC2_USER@$HOST" bash <<EOF
    set -e

    echo "  Stopping Tomcat..."
    sudo "$TOMCAT_DIR/bin/shutdown.sh" 2>/dev/null || true
    sleep 5
    # Force-kill any process still holding port 8080
    sudo fuser -k 8080/tcp 2>/dev/null || true
    sleep 1

    echo "  Removing old deployment..."
    sudo rm -rf "$TOMCAT_WEBAPPS/server" "$TOMCAT_WEBAPPS/$WAR_NAME"

    echo "  Deploying new WAR..."
    sudo cp /tmp/$WAR_NAME "$TOMCAT_WEBAPPS/$WAR_NAME"
    sudo chown -R ec2-user:ec2-user "$TOMCAT_WEBAPPS/$WAR_NAME"

    echo "  Writing Tomcat environment config..."
    # Pass config as JVM system properties via CATALINA_OPTS so Tomcat inherits them
    # regardless of shell login type. Config.java reads System.getProperty() as fallback.
    sudo tee "$TOMCAT_DIR/bin/setenv.sh" > /dev/null <<ENV
export CATALINA_OPTS="\
  -DSERVER_ID=$SERVER_ID \
  -DRABBITMQ_HOST=$RABBITMQ_HOST \
  -DRABBITMQ_PORT=5672 \
  -DRABBITMQ_USER=$RABBITMQ_USER \
  -DRABBITMQ_PASS=$RABBITMQ_PASS \
  -DREDIS_HOST=$REDIS_HOST \
  -DREDIS_PORT=$REDIS_PORT"
ENV
    sudo chmod +x "$TOMCAT_DIR/bin/setenv.sh"

    echo "  Starting Tomcat..."
    sudo "$TOMCAT_DIR/bin/startup.sh"

    echo "  Waiting for Tomcat to start..."
    for attempt in 1 2 3 4 5; do
      sleep 5
      if curl -sf http://localhost:8080/server/health > /dev/null; then
        echo "  Health check PASSED (attempt \$attempt)"
        break
      fi
      echo "  Not ready yet, retrying... (\$attempt/5)"
      if [ "\$attempt" -eq 5 ]; then
        echo "  Health check FAILED after 5 attempts"
      fi
    done
EOF

  echo "  Done: $SERVER_ID ($HOST)"
done

echo ""
echo "======================================"
echo " Deployment complete"
echo "======================================"
echo " Instances:"
for i in "${!EC2_HOSTS[@]}"; do
  echo "   server-$((i+1)): http://${EC2_HOSTS[$i]}:8080/server/health"
done

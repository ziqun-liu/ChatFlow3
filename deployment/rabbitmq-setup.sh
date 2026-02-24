#!/bin/bash
# RabbitMQ Setup Script
# Usage: ./rabbitmq-setup.sh
# Required environment variables:
#   RABBITMQ_HOST     - RabbitMQ server hostname or IP
#   RABBITMQ_USER     - RabbitMQ username
#   RABBITMQ_PASS     - RabbitMQ password
# Example:
#   export RABBITMQ_HOST=ec2-xx-xx-xx-xx.compute-1.amazonaws.com
#   export RABBITMQ_USER=chatuser
#   export RABBITMQ_PASS=yourpassword
#   ./rabbitmq-setup.sh

set -e

# ── Validate environment variables ───────────────────────────────────────────
if [ -z "$RABBITMQ_HOST" ] || [ -z "$RABBITMQ_USER" ] || [ -z "$RABBITMQ_PASS" ]; then
  echo "ERROR: RABBITMQ_HOST, RABBITMQ_USER, and RABBITMQ_PASS must be set."
  exit 1
fi

RABBITMQ_PORT=15672
BASE_URL="http://${RABBITMQ_HOST}:${RABBITMQ_PORT}/api"
AUTH="-u ${RABBITMQ_USER}:${RABBITMQ_PASS}"
EXCHANGE="chat.exchange"
VHOST="%2F"  # default vhost "/"

echo "================================================"
echo " RabbitMQ Setup"
echo " Host:     $RABBITMQ_HOST:$RABBITMQ_PORT"
echo " User:     $RABBITMQ_USER"
echo " Exchange: $EXCHANGE"
echo "================================================"

# ── 1. Create topic exchange ──────────────────────────────────────────────────
echo ""
echo "[1/3] Creating topic exchange: $EXCHANGE ..."
curl -s -o /dev/null -w "    HTTP %{http_code}\n" \
  -X PUT "${BASE_URL}/exchanges/${VHOST}/${EXCHANGE}" \
  $AUTH \
  -H "Content-Type: application/json" \
  -d '{
    "type": "topic",
    "durable": true,
    "auto_delete": false,
    "arguments": {}
  }'

# ── 2. Create queues room.1 ~ room.20 ─────────────────────────────────────────
echo ""
echo "[2/3] Creating queues room.1 ~ room.20 ..."
for i in $(seq 1 20); do
  QUEUE_NAME="room.${i}"
  curl -s -o /dev/null -w "    room.${i}: HTTP %{http_code}\n" \
    -X PUT "${BASE_URL}/queues/${VHOST}/${QUEUE_NAME}" \
    $AUTH \
    -H "Content-Type: application/json" \
    -d '{
      "durable": true,
      "auto_delete": false,
      "arguments": {
        "x-message-ttl": 60000,
        "x-max-length": 10000
      }
    }'
done

# ── 3. Bind queues to exchange with routing key room.{roomId} ─────────────────
echo ""
echo "[3/3] Binding queues to exchange ..."
for i in $(seq 1 20); do
  QUEUE_NAME="room.${i}"
  ROUTING_KEY="room.${i}"
  curl -s -o /dev/null -w "    room.${i} -> $EXCHANGE [room.${i}]: HTTP %{http_code}\n" \
    -X POST "${BASE_URL}/bindings/${VHOST}/e/${EXCHANGE}/q/${QUEUE_NAME}" \
    $AUTH \
    -H "Content-Type: application/json" \
    -d "{
      \"routing_key\": \"${ROUTING_KEY}\",
      \"arguments\": {}
    }"
done

echo ""
echo "================================================"
echo " Setup complete!"
echo " Exchange : $EXCHANGE (topic, durable)"
echo " Queues   : room.1 ~ room.20 (durable)"
echo "            TTL: 60000ms, Max length: 10000"
echo " Bindings : room.N -> room.N"
echo "================================================"

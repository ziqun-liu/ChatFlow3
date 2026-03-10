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
DLX="chat.dlx"
DLQ="room.dlq"
VHOST="%2F"  # default vhost "/"

echo "================================================"
echo " RabbitMQ Setup"
echo " Host:     $RABBITMQ_HOST:$RABBITMQ_PORT"
echo " User:     $RABBITMQ_USER"
echo " Exchange: $EXCHANGE"
echo " DLX:      $DLX  →  $DLQ"
echo "================================================"

# ── 1. Create topic exchange ──────────────────────────────────────────────────
echo ""
echo "[1/5] Creating topic exchange: $EXCHANGE ..."
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

# ── 2. Create dead-letter exchange (fanout) ───────────────────────────────────
echo ""
echo "[2/5] Creating dead-letter exchange: $DLX ..."
curl -s -o /dev/null -w "    HTTP %{http_code}\n" \
  -X PUT "${BASE_URL}/exchanges/${VHOST}/${DLX}" \
  $AUTH \
  -H "Content-Type: application/json" \
  -d '{
    "type": "fanout",
    "durable": true,
    "auto_delete": false,
    "arguments": {}
  }'

# ── 3. Create and bind dead-letter queue ──────────────────────────────────────
echo ""
echo "[3/5] Creating dead-letter queue: $DLQ ..."
curl -s -o /dev/null -w "    queue: HTTP %{http_code}\n" \
  -X PUT "${BASE_URL}/queues/${VHOST}/${DLQ}" \
  $AUTH \
  -H "Content-Type: application/json" \
  -d '{
    "durable": true,
    "auto_delete": false,
    "arguments": {}
  }'

curl -s -o /dev/null -w "    binding: HTTP %{http_code}\n" \
  -X POST "${BASE_URL}/bindings/${VHOST}/e/${DLX}/q/${DLQ}" \
  $AUTH \
  -H "Content-Type: application/json" \
  -d '{
    "routing_key": "",
    "arguments": {}
  }'

# ── 4. Delete + recreate queues room.1 ~ room.20 with x-dead-letter-exchange ──
echo ""
echo "[4/5] Recreating queues room.1 ~ room.20 (with DLX) ..."
for i in $(seq 1 20); do
  QUEUE_NAME="room.${i}"
  # Delete existing queue (ignore 404 if it doesn't exist yet)
  curl -s -o /dev/null \
    -X DELETE "${BASE_URL}/queues/${VHOST}/${QUEUE_NAME}" \
    $AUTH
  # Recreate with x-dead-letter-exchange
  curl -s -o /dev/null -w "    room.${i}: HTTP %{http_code}\n" \
    -X PUT "${BASE_URL}/queues/${VHOST}/${QUEUE_NAME}" \
    $AUTH \
    -H "Content-Type: application/json" \
    -d "{
      \"durable\": true,
      \"auto_delete\": false,
      \"arguments\": {
        \"x-message-ttl\": 60000,
        \"x-max-length\": 10000,
        \"x-dead-letter-exchange\": \"${DLX}\"
      }
    }"
done

# ── 5. Bind queues to exchange with routing key room.{roomId} ─────────────────
echo ""
echo "[5/5] Binding queues to exchange ..."
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
echo " DLX      : $DLX (fanout, durable)"
echo " DLQ      : $DLQ (durable)"
echo " Queues   : room.1 ~ room.20 (durable)"
echo "            TTL: 60000ms, Max length: 10000"
echo "            Dead-letter → $DLX → $DLQ"
echo " Bindings : room.N -> room.N"
echo "================================================"

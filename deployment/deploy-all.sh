#!/bin/bash
# deploy-all.sh
# Runs all deployment scripts in order: RabbitMQ → server-v2 → consumer
#
# Usage:
#   chmod +x deploy-all.sh
#   ./deploy-all.sh

set -e
cd "$(dirname "$0")"

# ── Configuration (single source of truth — edit only here) ──────────────────
RABBITMQ_PUBLIC_IP="44.255.34.231"     # RabbitMQ EC2 public IP (used by rabbitmq-setup.sh from local machine)
export RABBITMQ_HOST="172.31.28.111"   # RabbitMQ EC2 private IP (used by server-v2 and consumer on EC2)
export RABBITMQ_USER="guest"
export RABBITMQ_PASS="guest"
export REDIS_HOST="cs6650-assignment2-redis.yl1jzz.ng.0001.usw2.cache.amazonaws.com"
export REDIS_PORT="6379"
export CONSUMER_THREADS="20"
export RABBITMQ_PREFETCH="100"
export REDIS_POOL_MAX_TOTAL="40"  # 2× consume threads
export REDIS_POOL_MAX_WAIT_MS="2000"
export RABBITMQ_CHANNEL_POOL_SIZE="100"
export RABBITMQ_DELIVERY_MODE="1"       # 1=transient(fast), 2=persistent(durable)
export WS_URI="ws://cs6650-assignment2-lb-1697352352.us-west-2.elb.amazonaws.com/server/chat/"
# ───────────────────────────────────────────────────────────────────────────

# Ensure all scripts are executable
chmod +x rabbitmq-setup.sh deploy-server.sh consumer-setup.sh

echo "======================================"
echo " ChatFlow2 Full Deployment"
echo "======================================"

echo ""
echo "=== Step 1: RabbitMQ Setup ==="
RABBITMQ_HOST="$RABBITMQ_PUBLIC_IP" ./rabbitmq-setup.sh

echo ""
echo "=== Step 2: Server-v2 Deploy ==="
./deploy-server.sh

echo ""
echo "=== Step 3: Consumer Deploy ==="
./consumer-setup.sh

echo ""
echo "======================================"
echo " All deployments complete"
echo "======================================"

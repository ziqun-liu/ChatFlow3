#!/bin/bash
# deploy-all.sh
# Runs all deployment scripts in order:
#   RabbitMQ → Database → Server-v2 → Consumer-v3
#
# Usage:
#   chmod +x deploy-all.sh
#   ./deploy-all.sh

set -e
cd "$(dirname "$0")"

# ── Configuration (single source of truth — edit only here) ──────────────────

# RabbitMQ
RABBITMQ_PUBLIC_IP="44.255.34.231"     # RabbitMQ EC2 public IP (used by rabbitmq-setup.sh from local machine)
export RABBITMQ_HOST="172.31.28.111"   # RabbitMQ EC2 private IP (used by server-v2 and consumer on EC2)
export RABBITMQ_USER="guest"
export RABBITMQ_PASS="guest"

# Redis (ElastiCache)
export REDIS_HOST="cs6650-assignment2-redis.yl1jzz.ng.0001.usw2.cache.amazonaws.com"
export REDIS_PORT="6379"

# Consumer-v3 tuning
export CONSUMER_THREADS="20"
export RABBITMQ_PREFETCH="100"
export REDIS_POOL_MAX_TOTAL="40"       # 2× CONSUMER_THREADS
export REDIS_POOL_MAX_WAIT_MS="2000"

# Server-v2 tuning
export RABBITMQ_CHANNEL_POOL_SIZE="100"
export RABBITMQ_DELIVERY_MODE="1"      # 1=transient (fast), 2=persistent (durable)

# Client
export WS_URI="ws://cs6650-assignment2-lb-1697352352.us-west-2.elb.amazonaws.com/server/chat/"

# MySQL (RDS or EC2)
export DB_HOST="<rds-endpoint-or-ec2-private-ip>"  # Replace with actual DB host
export DB_PORT="3306"
export DB_NAME="chatflow"
export DB_USER="chatflow"
export DB_PASS="<db-password>"                     # Replace with actual password
export DB_ROOT_USER="admin"                        # RDS master user (for schema setup only)
export DB_ROOT_PASS="<rds-root-password>"          # Replace with actual RDS root password

# DB batch writer tuning (consumer-v3)
export DB_BATCH_SIZE="500"
export DB_FLUSH_INTERVAL_MS="500"
export DB_POOL_SIZE="10"
export DB_WRITER_THREADS="3"

# DB connection pool for MetricsServlet (server-v2)
# DB_POOL_SIZE is reused (defaults to 5 on server side)

# ─────────────────────────────────────────────────────────────────────────────

chmod +x rabbitmq-setup.sh deploy-server.sh consumer-setup.sh database-setup.sh

echo "======================================"
echo " ChatFlow3 Full Deployment (Assignment 3)"
echo "======================================"

echo ""
echo "=== Step 1: RabbitMQ Setup ==="
RABBITMQ_HOST="$RABBITMQ_PUBLIC_IP" ./rabbitmq-setup.sh

echo ""
echo "=== Step 2: Database Setup ==="
./database-setup.sh

echo ""
echo "=== Step 3: Server-v2 Deploy ==="
./deploy-server.sh

echo ""
echo "=== Step 4: Consumer-v3 Deploy ==="
./consumer-setup.sh

echo ""
echo "======================================"
echo " All deployments complete"
echo "======================================"

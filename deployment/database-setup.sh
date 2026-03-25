#!/bin/bash
# database-setup.sh
# Creates the chatflow database, user, and schema on a remote MySQL/RDS instance.
#
# Usage:
#   chmod +x database-setup.sh
#   ./database-setup.sh
#
# Prerequisites:
#   - mysql client installed locally
#   - MySQL/RDS instance reachable from this machine
#   - DB_ROOT_PASS set (or script will prompt)

set -e
cd "$(dirname "$0")"

# ── Configuration ────────────────────────────────────────────────────────────
# DB_HOST, DB_ROOT_USER, DB_ROOT_PASS, DB_USER, DB_PASS inherited from deploy-all.sh
# or must be exported before calling this script directly.
DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-3306}"
DB_ROOT_USER="${DB_ROOT_USER:-root}"
DB_ROOT_PASS="${DB_ROOT_PASS:-}"
DB_NAME="${DB_NAME:-chatflow}"
DB_USER="${DB_USER:-chatflow}"
DB_PASS="${DB_PASS:?DB_PASS is required}"

SCHEMA_FILE="$(cd "$(dirname "$0")/../database" && pwd)/schema.sql"
# ─────────────────────────────────────────────────────────────────────────────

echo "======================================"
echo " Database Setup: $DB_HOST:$DB_PORT/$DB_NAME"
echo "======================================"

MYSQL_CMD="mysql -h${DB_HOST} -P${DB_PORT} -u${DB_ROOT_USER}"
if [ -n "$DB_ROOT_PASS" ]; then
  MYSQL_CMD="${MYSQL_CMD} -p${DB_ROOT_PASS}"
fi

echo ""
echo "=== Step 1: Create user and grant privileges ==="
${MYSQL_CMD} <<EOF
CREATE USER IF NOT EXISTS '${DB_USER}'@'%' IDENTIFIED BY '${DB_PASS}';
GRANT ALL PRIVILEGES ON ${DB_NAME}.* TO '${DB_USER}'@'%';
FLUSH PRIVILEGES;
EOF
echo "  User '${DB_USER}' created/verified."

echo ""
echo "=== Step 2: Apply schema ==="
${MYSQL_CMD} < "$SCHEMA_FILE"
echo "  Schema applied: messages table + indexes."

echo ""
echo "======================================"
echo " Database setup complete."
echo "  Host : $DB_HOST:$DB_PORT"
echo "  DB   : $DB_NAME"
echo "  User : $DB_USER"
echo "======================================"
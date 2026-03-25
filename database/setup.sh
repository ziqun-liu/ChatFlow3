#!/bin/bash
# Creates the chatflow database, user, and schema.
# Usage: MYSQL_ROOT_PASS=secret DB_PASS=chatpass ./setup.sh
# All variables have defaults for local dev.

MYSQL_HOST="${MYSQL_HOST:-localhost}"
MYSQL_PORT="${MYSQL_PORT:-3306}"
MYSQL_ROOT_USER="${MYSQL_ROOT_USER:-root}"
MYSQL_ROOT_PASS="${MYSQL_ROOT_PASS:-}"
DB_NAME="${DB_NAME:-chatflow}"
DB_USER="${DB_USER:-chatflow}"
DB_PASS="${DB_PASS:-chatflow}"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

echo "=== ChatFlow3 DB Setup ==="
echo "Host: $MYSQL_HOST:$MYSQL_PORT | DB: $DB_NAME | User: $DB_USER"

MYSQL_CMD="mysql -h${MYSQL_HOST} -P${MYSQL_PORT} -u${MYSQL_ROOT_USER}"
if [ -n "$MYSQL_ROOT_PASS" ]; then
  MYSQL_CMD="${MYSQL_CMD} -p${MYSQL_ROOT_PASS}"
fi

# Create user and grant privileges
${MYSQL_CMD} <<EOF
CREATE USER IF NOT EXISTS '${DB_USER}'@'%' IDENTIFIED BY '${DB_PASS}';
GRANT ALL PRIVILEGES ON ${DB_NAME}.* TO '${DB_USER}'@'%';
FLUSH PRIVILEGES;
EOF

if [ $? -ne 0 ]; then
  echo "ERROR: Failed to create user. Check root credentials."
  exit 1
fi

# Run schema
${MYSQL_CMD} < "${SCRIPT_DIR}/schema.sql"

if [ $? -ne 0 ]; then
  echo "ERROR: Failed to apply schema."
  exit 1
fi

echo "Setup complete. Table 'messages' created in database '${DB_NAME}'."

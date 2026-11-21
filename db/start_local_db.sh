#!/usr/bin/env bash
set -euo pipefail

# Start a local PostgreSQL container (or reuse an existing one), apply db/schema.sql
# on a fresh DB, and seed the demo user via db/seed_demo.sql.
#
# Configurable env vars:
#   CONTAINER_NAME  Docker container name      (default: monmon-local-postgres)
#   PG_IMAGE        Postgres Docker image      (default: postgres:11)
#   PGHOST          Host when SKIP_DOCKER=1    (default: localhost)
#   PGPORT          Host port                  (default: 5432)
#   PGUSER          Postgres superuser         (default: postgres)
#   PGPASSWORD      Postgres password          (default: monmon_local)
#   PGDB            Database name              (default: monmon-play)
#   SKIP_DOCKER     Set to 1 to use an existing local Postgres instead of Docker
#   NO_SEED         Set to 1 to skip running db/seed_demo.sql

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

CONTAINER_NAME="${CONTAINER_NAME:-monmon-local-postgres}"
PG_IMAGE="${PG_IMAGE:-postgres:11}"
PGHOST="${PGHOST:-localhost}"
PGPORT="${PGPORT:-5432}"
PGUSER="${PGUSER:-postgres}"
PGPASSWORD="${PGPASSWORD:-monmon_local}"
PGDB="${PGDB:-monmon-play}"
SKIP_DOCKER="${SKIP_DOCKER:-0}"
NO_SEED="${NO_SEED:-0}"

run_psql() {
    local db="$1"
    shift
    if [ "$SKIP_DOCKER" = "1" ]; then
        PGPASSWORD="$PGPASSWORD" psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$db" -v ON_ERROR_STOP=1 "$@"
    else
        docker exec -i -e PGPASSWORD="$PGPASSWORD" "$CONTAINER_NAME" \
            psql -U "$PGUSER" -d "$db" -v ON_ERROR_STOP=1 "$@"
    fi
}

if [ "$SKIP_DOCKER" != "1" ]; then
    if ! command -v docker >/dev/null 2>&1; then
        echo "Error: docker not found. Install Docker or set SKIP_DOCKER=1 to use an existing Postgres." >&2
        exit 1
    fi

    if docker ps -a --format '{{.Names}}' | grep -qx "$CONTAINER_NAME"; then
        if ! docker ps --format '{{.Names}}' | grep -qx "$CONTAINER_NAME"; then
            echo "==> Starting existing container '$CONTAINER_NAME'..."
            docker start "$CONTAINER_NAME" >/dev/null
        else
            echo "==> Reusing running container '$CONTAINER_NAME'."
        fi
    else
        echo "==> Creating container '$CONTAINER_NAME' ($PG_IMAGE) on port $PGPORT..."
        docker run -d \
            --name "$CONTAINER_NAME" \
            -e "POSTGRES_USER=$PGUSER" \
            -e "POSTGRES_PASSWORD=$PGPASSWORD" \
            -e "POSTGRES_DB=$PGDB" \
            -p "${PGPORT}:5432" \
            "$PG_IMAGE" >/dev/null
    fi

    echo "==> Waiting for PostgreSQL to accept connections..."
    for _ in $(seq 1 30); do
        if docker exec "$CONTAINER_NAME" pg_isready -U "$PGUSER" >/dev/null 2>&1; then
            break
        fi
        sleep 1
    done
    if ! docker exec "$CONTAINER_NAME" pg_isready -U "$PGUSER" >/dev/null 2>&1; then
        echo "Error: PostgreSQL in '$CONTAINER_NAME' did not become ready in time." >&2
        exit 1
    fi
fi

# Ensure target database exists
DB_EXISTS="$(run_psql postgres -tAc "SELECT 1 FROM pg_database WHERE datname = '${PGDB}'" || true)"
if [ "$DB_EXISTS" != "1" ]; then
    echo "==> Creating database '$PGDB'..."
    run_psql postgres -c "CREATE DATABASE \"${PGDB}\";"
fi

# Apply schema.sql if core tables aren't present yet
HAS_ACCOUNT="$(run_psql "$PGDB" -tAc "SELECT to_regclass('public.account') IS NOT NULL")"
if [ "$HAS_ACCOUNT" != "t" ]; then
    echo "==> Applying schema from db/schema.sql..."
    run_psql "$PGDB" < "$SCRIPT_DIR/schema.sql" >/dev/null
else
    echo "==> Schema already present in '$PGDB' (skipping db/schema.sql)."
fi

# Seed demo data (idempotent)
if [ "$NO_SEED" != "1" ]; then
    echo "==> Seeding demo user data from db/seed_demo.sql..."
    run_psql "$PGDB" < "$SCRIPT_DIR/seed_demo.sql"
fi

DB_URL="postgres://${PGUSER}:${PGPASSWORD}@${PGHOST}:${PGPORT}/${PGDB}"
echo ""
echo "Local MonMon PostgreSQL is ready."
echo "  DB_URL: $DB_URL"
echo "  Demo login: username 'demo', password 'demo'"
echo ""
echo "Start the Rust app with:"
echo "  cd modules/monmon-rs && PORT=9000 BASE_URL=http://localhost:9000 DB_URL=\"$DB_URL\" cargo run"

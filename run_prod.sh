#!/usr/bin/env bash
set -euo pipefail

APP_DIR="${APP_DIR:-$(pwd)}"
cd "$APP_DIR"

echo "=== MonMon Rust Production Deployment & Startup ==="

# 1. Environment configuration
ENV_FILE="$APP_DIR/.env"
if [ -f "$ENV_FILE" ]; then
    echo "Found $ENV_FILE - loading environment."
    chmod 600 "$ENV_FILE" || true
else
    echo "⚠️ WARNING: No $ENV_FILE found at $APP_DIR/.env. Containers will use defaults."
fi

DB_PASSWORD=$(grep -E '^DB_PASSWORD=' "$ENV_FILE" 2>/dev/null | head -n1 | cut -d'=' -f2- || true)

# 2. Ensure PostgreSQL password matches DB_PASSWORD in .env
if [ -n "${DB_PASSWORD:-}" ] && docker ps --format '{{.Names}}' | grep -q '^postgres-monmon$'; then
    echo "Synchronizing PostgreSQL password with .env..."
    docker exec postgres-monmon psql -U postgres -c "ALTER USER postgres WITH PASSWORD '${DB_PASSWORD}';" >/dev/null 2>&1 || true
fi

# 3. Stop any conflicting legacy JVM containers
echo "Ensuring no legacy port conflicts..."
docker update --restart=no legmon monmon 2>/dev/null || true
docker stop legmon monmon 2>/dev/null || true

# 4. Start MonMon Rust application services (monmon-rs, legmon-rs, memmon-rs) without touching Rauthy
echo "Starting MonMon Rust services via docker compose (leaving Rauthy untouched)..."
docker compose -f docker-compose.rust.yaml up -d --no-deps --no-recreate memmon legmon 2>/dev/null || true
docker compose -f docker-compose.rust.yaml up -d --no-deps --force-recreate monmon

# 5. Wait for services to become healthy via HTTP health checks
echo "Waiting for monmon-rs service (probing http://127.0.0.1:9000/health)..."
MAX_ATTEMPTS=25
ATTEMPT=1
HEALTHY=false

while [ "$ATTEMPT" -le "$MAX_ATTEMPTS" ]; do
    HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" --max-time 3 http://127.0.0.1:9000/health 2>/dev/null || true)
    if [ "$HTTP_CODE" = "200" ]; then
        HEALTHY=true
        break
    fi
    echo "Attempt $ATTEMPT/$MAX_ATTEMPTS: Waiting for monmon-rs (HTTP probe: $HTTP_CODE)..."
    sleep 2
    ATTEMPT=$((ATTEMPT + 1))
done

if [ "$HEALTHY" = true ]; then
    echo "✅ monmon-rs is healthy and accepting traffic (HTTP 200)!"
else
    echo "❌ ERROR: monmon-rs health check failed after $((MAX_ATTEMPTS * 2)) seconds! Container logs:" >&2
    docker compose -f docker-compose.rust.yaml logs --tail 40 monmon || true
    exit 1
fi

# 6. Verify MemMon cache status
MEMMON_CODE="000"
for _ in $(seq 1 10); do
    MEMMON_CODE=$(curl -s -o /dev/null -w "%{http_code}" --max-time 2 http://127.0.0.1:9006/health 2>/dev/null || true)
    if [ "$MEMMON_CODE" = "200" ]; then
        break
    fi
    sleep 1
done
if [ "$MEMMON_CODE" = "200" ]; then
    echo "✅ memmon-rs in-memory cache service is healthy (HTTP 200)!"
else
    echo "ℹ️ Note: memmon-rs probe returned $MEMMON_CODE (monmon-rs fallback in-memory cache will be used until memmon starts)."
fi

# 7. Verify LegMon status
LEGMON_CODE="000"
for _ in $(seq 1 15); do
    LEGMON_CODE=$(curl -s -o /dev/null -w "%{http_code}" --max-time 3 http://127.0.0.1:9005/status 2>/dev/null || true)
    if [ "$LEGMON_CODE" = "200" ]; then
        break
    fi
    sleep 1
done
if [ "$LEGMON_CODE" = "200" ]; then
    echo "✅ legmon-rs OCR service is healthy (HTTP 200)!"
else
    echo "⚠️ Warning: legmon-rs probe returned $LEGMON_CODE - check docker compose logs if statement OCR fails."
fi

docker compose -f docker-compose.rust.yaml ps

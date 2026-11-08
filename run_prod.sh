#!/usr/bin/env bash
set -euo pipefail

APP_DIR="${APP_DIR:-$(pwd)}"
cd "$APP_DIR"

echo "=== MonMon Rust & Rauthy Production Deployment & Startup ==="

# 1. Environment configuration
ENV_FILE="$APP_DIR/.env"
if [ -f "$ENV_FILE" ]; then
    echo "Found $ENV_FILE - loading environment."
    chmod 600 "$ENV_FILE" || true
else
    echo "⚠️ WARNING: No $ENV_FILE found at $APP_DIR/.env. Containers will use defaults."
fi

# 2. Start unified Rust stack (monmon-rs, legmon-rs, rauthy) via docker compose
echo "Starting MonMon Rust stack via docker compose..."
docker compose -f docker-compose.rust.yaml up -d

# 3. Wait for services to become healthy via HTTP health checks
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

# 4. Verify Rauthy status
RAUTHY_CODE=$(curl -s -o /dev/null -w "%{http_code}" --max-time 3 http://127.0.0.1:8085/auth/v1/ping 2>/dev/null || true)
if [ "$RAUTHY_CODE" = "200" ]; then
    echo "✅ Rauthy Auth service is healthy (HTTP 200)!"
else
    echo "⚠️ Warning: Rauthy probe returned $RAUTHY_CODE - check docker compose logs if auth fails."
fi

docker compose -f docker-compose.rust.yaml ps

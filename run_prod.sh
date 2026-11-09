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

# 2. Render Rauthy configuration and bootstrap files from .env
echo "Rendering Rauthy configs from templates using .env secrets..."
python3 - <<'PYEOF'
import os
from pathlib import Path

env_vars = dict(os.environ)
env_path = Path(".env")
if env_path.exists():
    for line in env_path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        env_vars[k.strip()] = v.strip()

replacements = {
    "__RAUTHY_SECRET_RAFT__": env_vars.get("RAUTHY_SECRET_RAFT", ""),
    "__RAUTHY_SECRET_API__": env_vars.get("RAUTHY_SECRET_API", ""),
    "__RAUTHY_ENC_KEY_ID__": env_vars.get("RAUTHY_ENC_KEY_ID", "2026-11-09"),
    "__RAUTHY_ENC_KEY__": env_vars.get("RAUTHY_ENC_KEY", ""),
    "__OIDC_CLIENT_SECRET__": env_vars.get("OIDC_CLIENT_SECRET", ""),
    "__KEYCLOAK_ADMIN_PASSWORD__": env_vars.get("KEYCLOAK_ADMIN_PASSWORD", ""),
    "__INBREWJ_PASSWORD__": env_vars.get("INBREWJ_PASSWORD", env_vars.get("KEYCLOAK_ADMIN_PASSWORD", "")),
    "__CAITY_PASSWORD__": env_vars.get("CAITY_PASSWORD", ""),
}

templates = [
    ("rust-rewrite/rauthy/config.toml.template", "rust-rewrite/rauthy/config.toml"),
    ("rust-rewrite/rauthy/bootstrap/clients.json.template", "rust-rewrite/rauthy/bootstrap/clients.json"),
    ("rust-rewrite/rauthy/bootstrap/users.json.template", "rust-rewrite/rauthy/bootstrap/users.json"),
]

for src, dst in templates:
    src_path = Path(src)
    dst_path = Path(dst)
    if src_path.exists():
        content = src_path.read_text(encoding="utf-8")
        for k, v in replacements.items():
            content = content.replace(k, v)
        dst_path.write_text(content, encoding="utf-8")
        os.chmod(dst_path, 0o644)
PYEOF

DB_PASSWORD=$(grep -E '^DB_PASSWORD=' "$ENV_FILE" 2>/dev/null | head -n1 | cut -d'=' -f2- || true)
ACTIVE_KEY_ID=$(grep -E '^RAUTHY_ENC_KEY_ID=' "$ENV_FILE" 2>/dev/null | head -n1 | cut -d'=' -f2- || echo "2026-11-09")

# 3. Ensure PostgreSQL password matches DB_PASSWORD in .env
if [ -n "${DB_PASSWORD:-}" ] && docker ps --format '{{.Names}}' | grep -q '^postgres-monmon$'; then
    echo "Synchronizing PostgreSQL password with .env..."
    docker exec postgres-monmon psql -U postgres -c "ALTER USER postgres WITH PASSWORD '${DB_PASSWORD}';" >/dev/null 2>&1 || true
fi

# 4. Check if Rauthy encryption key / bootstrap secrets were rotated
CURRENT_KID=$(docker run --rm -v "$APP_DIR/rust-rewrite/rauthy/data:/data:ro" alpine sh -c "if [ -f /data/state_machine/db/hiqlite.db ]; then apk add --no-cache sqlite >/dev/null 2>&1 && sqlite3 /data/state_machine/db/hiqlite.db \"SELECT secret_kid FROM clients WHERE id='monmon-web';\"; fi" 2>/dev/null || true)
if [ -n "$CURRENT_KID" ] && [ "$CURRENT_KID" != "$ACTIVE_KEY_ID" ]; then
    echo "Detected Rauthy secret key rotation ($CURRENT_KID -> $ACTIVE_KEY_ID). Re-bootstrapping Rauthy while preserving passkeys..."
    docker compose -f docker-compose.rust.yaml stop rauthy || true

    PASSKEY_SQL="/tmp/rauthy_passkeys_restore.sql"
    mkdir -p "$APP_DIR/backups"
    docker run --rm -v "$APP_DIR/rust-rewrite/rauthy/data:/data:ro" -v "$APP_DIR/backups:/backups" alpine sh -c "
        cp -f /data/state_machine/db/hiqlite.db /backups/rauthy_pre_rotation.sqlite 2>/dev/null || true
        apk add --no-cache sqlite >/dev/null 2>&1
        sqlite3 /data/state_machine/db/hiqlite.db \".mode insert passkeys\" \"SELECT * FROM passkeys;\"
        sqlite3 /data/state_machine/db/hiqlite.db \"SELECT 'UPDATE users SET webauthn_user_id = ''' || webauthn_user_id || ''' WHERE id = ''' || id || ''';' FROM users WHERE webauthn_user_id IS NOT NULL;\"
    " > "$PASSKEY_SQL" 2>/dev/null || true

    docker run --rm -v "$APP_DIR/rust-rewrite/rauthy:/rauthy" alpine sh -c "rm -rf /rauthy/data/* /rauthy/data/.[!.]* 2>/dev/null || true"

    docker compose -f docker-compose.rust.yaml up -d rauthy
    echo "Waiting for Rauthy bootstrap to complete..."
    for _ in $(seq 1 60); do
        if [ "$(curl -s -o /dev/null -w "%{http_code}" http://127.0.0.1:8085/auth/v1/ping 2>/dev/null || true)" = "200" ]; then
            break
        fi
        sleep 1
    done
    sleep 2

    if [ -s "$PASSKEY_SQL" ]; then
        echo "Restoring registered WebAuthn passkeys into Rauthy..."
        docker compose -f docker-compose.rust.yaml stop rauthy
        docker run --rm -v "$APP_DIR/rust-rewrite/rauthy/data:/data" -v "$PASSKEY_SQL:/restore.sql:ro" alpine sh -c "
            apk add --no-cache sqlite >/dev/null 2>&1
            sqlite3 /data/state_machine/db/hiqlite.db < /restore.sql
        "
        rm -f "$PASSKEY_SQL"
    fi
fi

# 5. Start unified Rust stack (monmon-rs, legmon-rs, rauthy) via docker compose
echo "Starting MonMon Rust stack via docker compose..."
docker compose -f docker-compose.rust.yaml up -d --force-recreate

# 6. Wait for services to become healthy via HTTP health checks
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

# 7. Verify Rauthy status
RAUTHY_CODE="000"
for _ in $(seq 1 15); do
    RAUTHY_CODE=$(curl -s -o /dev/null -w "%{http_code}" --max-time 3 http://127.0.0.1:8085/auth/v1/ping 2>/dev/null || true)
    if [ "$RAUTHY_CODE" = "200" ]; then
        break
    fi
    sleep 1
done
if [ "$RAUTHY_CODE" = "200" ]; then
    echo "✅ Rauthy Auth service is healthy (HTTP 200)!"
else
    echo "⚠️ Warning: Rauthy probe returned $RAUTHY_CODE - check docker compose logs if auth fails."
fi

docker compose -f docker-compose.rust.yaml ps

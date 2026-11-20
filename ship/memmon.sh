#!/usr/bin/env bash
set -euo pipefail

# ANSI color codes
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
BOLD='\033[1m'
NC='\033[0m' # No Color

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$SCRIPT_DIR"

if ! command -v cargo >/dev/null 2>&1 && [ -d "$HOME/.cargo/bin" ]; then
    export PATH="$HOME/.cargo/bin:$PATH"
fi

GREENSTREET_HOST="${1:-greenstreet@greenstreet.local}"
REMOTE_USER="${GREENSTREET_HOST%@*}"
REMOTE_DIR="/home/${REMOTE_USER}/app"

echo -e "${BLUE}${BOLD}==> [1/4] Testing & cross-compiling memmon-rs for ARM64 on local machine...${NC}"
if command -v cargo >/dev/null 2>&1; then
    (cd modules/memmon-rs && cargo test --quiet)
    echo -e "${GREEN}✓ memmon-rs unit tests passed.${NC}"
fi
./build-arm64.sh memmon

echo -e "${BLUE}${BOLD}==> [2/4] Syncing memmon-rs prebuilt ARM64 binary and Dockerfile to ${GREENSTREET_HOST}...${NC}"
ssh "$GREENSTREET_HOST" "mkdir -p ${REMOTE_DIR}/modules/memmon-rs/prebuilt"
rsync -avz --delete \
    --exclude 'target' \
    modules/memmon-rs/ \
    "${GREENSTREET_HOST}:${REMOTE_DIR}/modules/memmon-rs/"
scp docker-compose.rust.yaml "${GREENSTREET_HOST}:${REMOTE_DIR}/"
if [[ -f .env ]]; then
    scp .env "${GREENSTREET_HOST}:${REMOTE_DIR}/.env"
    ssh "$GREENSTREET_HOST" "chmod 600 ${REMOTE_DIR}/.env"
fi

echo -e "${BLUE}${BOLD}==> [3/4] Packaging and updating memmon runtime container on ${GREENSTREET_HOST} (no Rust compile on Pi)...${NC}"
ssh "$GREENSTREET_HOST" "cd ${REMOTE_DIR} && docker compose -f docker-compose.rust.yaml build memmon && docker compose -f docker-compose.rust.yaml up -d --no-deps --force-recreate memmon"

echo -e "${BLUE}${BOLD}==> [4/4] Verifying MemMon health on port 9006...${NC}"
MEMMON_HEALTHY=false
for _ in $(seq 1 15); do
    if ssh "$GREENSTREET_HOST" "curl -sf http://127.0.0.1:9006/health || curl -sf http://127.0.0.1:9006/status"; then
        MEMMON_HEALTHY=true
        break
    fi
    sleep 1
done
echo ""
if [ "$MEMMON_HEALTHY" != true ]; then
    echo -e "${RED}${BOLD}ERROR: MemMon failed health check on port 9006! Container logs:${NC}" >&2
    ssh "$GREENSTREET_HOST" "cd ${REMOTE_DIR} && docker compose -f docker-compose.rust.yaml logs --tail 30 memmon" || true
    exit 1
fi

echo -e "${GREEN}${BOLD}🎉 MemMon in-memory cache deployment to ${GREENSTREET_HOST} completed successfully!${NC}"

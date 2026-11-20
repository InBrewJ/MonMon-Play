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

echo -e "${BLUE}${BOLD}==> [1/4] Cross-compiling legmon-rs for ARM64 on local machine...${NC}"
./build-arm64.sh legmon

echo -e "${BLUE}${BOLD}==> [2/4] Syncing legmon-rs prebuilt ARM64 binary and Dockerfile to ${GREENSTREET_HOST}...${NC}"
ssh "$GREENSTREET_HOST" "mkdir -p ${REMOTE_DIR}/modules/legmon-rs/prebuilt"
rsync -avz --delete \
    --exclude 'target' \
    modules/legmon-rs/ \
    "${GREENSTREET_HOST}:${REMOTE_DIR}/modules/legmon-rs/"
scp docker-compose.rust.yaml "${GREENSTREET_HOST}:${REMOTE_DIR}/"
if [[ -f .env ]]; then
    scp .env "${GREENSTREET_HOST}:${REMOTE_DIR}/.env"
    ssh "$GREENSTREET_HOST" "chmod 600 ${REMOTE_DIR}/.env"
fi

echo -e "${BLUE}${BOLD}==> [3/4] Packaging and updating legmon runtime container on ${GREENSTREET_HOST} (no Rust compile on Pi)...${NC}"
ssh "$GREENSTREET_HOST" "cd ${REMOTE_DIR} && docker compose -f docker-compose.rust.yaml build legmon && docker compose -f docker-compose.rust.yaml up -d --no-deps --force-recreate legmon"

echo -e "${BLUE}${BOLD}==> [4/4] Verifying LegMon health on port 9005...${NC}"
LEGMON_HEALTHY=false
for _ in $(seq 1 15); do
    if ssh "$GREENSTREET_HOST" "curl -sf http://127.0.0.1:9005/health || curl -sf http://127.0.0.1:9005/status"; then
        LEGMON_HEALTHY=true
        break
    fi
    sleep 1
done
echo ""
if [ "$LEGMON_HEALTHY" != true ]; then
    echo -e "${RED}${BOLD}ERROR: LegMon failed health check on port 9005! Container logs:${NC}" >&2
    ssh "$GREENSTREET_HOST" "cd ${REMOTE_DIR} && docker compose -f docker-compose.rust.yaml logs --tail 30 legmon" || true
    exit 1
fi

echo -e "${GREEN}${BOLD}🎉 LegMon Rust deployment to ${GREENSTREET_HOST} completed successfully!${NC}"

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

GREENSTREET_HOST="${1:-greenstreet@greenstreet.local}"
REMOTE_USER="${GREENSTREET_HOST%@*}"
REMOTE_DIR="/home/${REMOTE_USER}/app"

GIT_COMMIT=$(git rev-parse --short HEAD 2>/dev/null || echo "local")
GIT_COUNT=$(git rev-list --count HEAD 2>/dev/null || echo "dev")

echo -e "${BLUE}${BOLD}==> [1/5] Cross-compiling monmon-rs for ARM64 on local machine (commit: ${GIT_COMMIT}, build: ${GIT_COUNT})...${NC}"
./build-arm64.sh monmon

echo -e "${BLUE}${BOLD}==> [2/5] Preparing remote directories on ${GREENSTREET_HOST}...${NC}"
ssh "$GREENSTREET_HOST" "mkdir -p ${REMOTE_DIR}/modules/monmon-rs/prebuilt ${REMOTE_DIR}/modules/legmon-rs ${REMOTE_DIR}/modules/memmon-rs ${REMOTE_DIR}/public ${REMOTE_DIR}/logs"

echo -e "${BLUE}${BOLD}==> [3/5] Syncing prebuilt ARM64 binary, assets, and configs to ${GREENSTREET_HOST}...${NC}"
# Copy monmon-rs prebuilt ARM64 binary, Dockerfile, and assets (excluding target)
rsync -avz --delete \
    --exclude 'target' \
    modules/monmon-rs/ \
    "${GREENSTREET_HOST}:${REMOTE_DIR}/modules/monmon-rs/"

# Copy public static directory
rsync -avz public/ "${GREENSTREET_HOST}:${REMOTE_DIR}/public/"
rsync -avz public/ "${GREENSTREET_HOST}:${REMOTE_DIR}/modules/monmon-rs/public/"

# Copy compose and run script
scp docker-compose.rust.yaml "${GREENSTREET_HOST}:${REMOTE_DIR}/"
scp run_prod.sh "${GREENSTREET_HOST}:${REMOTE_DIR}/run_prod.sh"
ssh "$GREENSTREET_HOST" "chmod +x ${REMOTE_DIR}/run_prod.sh"

if [[ -f .env ]]; then
    scp .env "${GREENSTREET_HOST}:${REMOTE_DIR}/.env"
    ssh "$GREENSTREET_HOST" "chmod 600 ${REMOTE_DIR}/.env"
fi

echo -e "${BLUE}${BOLD}==> [4/5] Packaging lightweight MonMon runtime container image on ${GREENSTREET_HOST} (no Rust compile on Pi)...${NC}"
ssh "$GREENSTREET_HOST" "cd ${REMOTE_DIR} && docker compose -f docker-compose.rust.yaml build monmon"

echo -e "${BLUE}${BOLD}==> [5/5] Starting MonMon Rust stack on ${GREENSTREET_HOST} via run_prod.sh...${NC}"
ssh "$GREENSTREET_HOST" "cd ${REMOTE_DIR} && ./run_prod.sh"

echo -e "${GREEN}${BOLD}🎉 MonMon Rust deployment to ${GREENSTREET_HOST} completed successfully!${NC}"

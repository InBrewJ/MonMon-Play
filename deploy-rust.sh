#!/usr/bin/env bash
set -euo pipefail

# ANSI color codes
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
BOLD='\033[1m'
NC='\033[0m' # No Color

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

GREENSTREET_HOST="${1:-greenstreet@greenstreet.local}"
REMOTE_USER="${GREENSTREET_HOST%@*}"
REMOTE_DIR="/home/${REMOTE_USER}/app"

GIT_COMMIT=$(git rev-parse --short HEAD 2>/dev/null || echo "local")
GIT_COUNT=$(git rev-list --count HEAD 2>/dev/null || echo "dev")
echo "build.commit=${GIT_COMMIT}" > rust-rewrite/monmon-rs/build.properties
echo "build.number=${GIT_COUNT}" >> rust-rewrite/monmon-rs/build.properties

echo -e "${BLUE}${BOLD}==> [1/4] Preparing remote directories on ${GREENSTREET_HOST} (commit: ${GIT_COMMIT}, build: ${GIT_COUNT})...${NC}"
ssh "$GREENSTREET_HOST" "mkdir -p ${REMOTE_DIR}/rust-rewrite/monmon-rs ${REMOTE_DIR}/rust-rewrite/legmon-rs ${REMOTE_DIR}/rust-rewrite/rauthy/bootstrap ${REMOTE_DIR}/rust-rewrite/rauthy/data ${REMOTE_DIR}/public ${REMOTE_DIR}/logs"

echo -e "${BLUE}${BOLD}==> [2/4] Syncing source code, templates, and configs to ${GREENSTREET_HOST}...${NC}"
# Copy monmon-rs source, templates, Dockerfile, Cargo files
rsync -avz --delete \
    --exclude 'target' \
    rust-rewrite/monmon-rs/ \
    "${GREENSTREET_HOST}:${REMOTE_DIR}/rust-rewrite/monmon-rs/"

# Copy legmon-rs source, Dockerfile, Cargo files
rsync -avz --delete \
    --exclude 'target' \
    rust-rewrite/legmon-rs/ \
    "${GREENSTREET_HOST}:${REMOTE_DIR}/rust-rewrite/legmon-rs/"

# Copy rauthy configuration and bootstrap
scp rust-rewrite/rauthy/config.toml "${GREENSTREET_HOST}:${REMOTE_DIR}/rust-rewrite/rauthy/"
rsync -avz rust-rewrite/rauthy/bootstrap/ "${GREENSTREET_HOST}:${REMOTE_DIR}/rust-rewrite/rauthy/bootstrap/"

# Copy public static directory
rsync -avz public/ "${GREENSTREET_HOST}:${REMOTE_DIR}/public/"
rsync -avz public/ "${GREENSTREET_HOST}:${REMOTE_DIR}/rust-rewrite/monmon-rs/public/"

# Copy compose and run script
scp docker-compose.rust.yaml "${GREENSTREET_HOST}:${REMOTE_DIR}/"
scp run_prod.sh "${GREENSTREET_HOST}:${REMOTE_DIR}/run_prod.sh"
ssh "$GREENSTREET_HOST" "chmod +x ${REMOTE_DIR}/run_prod.sh"

if [[ -f .env ]]; then
    scp .env "${GREENSTREET_HOST}:${REMOTE_DIR}/.env"
    ssh "$GREENSTREET_HOST" "chmod 600 ${REMOTE_DIR}/.env"
fi

echo -e "${BLUE}${BOLD}==> [3/4] Building Rust container images natively on ${GREENSTREET_HOST}...${NC}"
ssh "$GREENSTREET_HOST" "cd ${REMOTE_DIR} && BUILD_COMMIT=${GIT_COMMIT} BUILD_NUMBER=${GIT_COUNT} docker compose -f docker-compose.rust.yaml build"

echo -e "${BLUE}${BOLD}==> [4/4] Starting Rust stack on ${GREENSTREET_HOST} via run_prod.sh...${NC}"
ssh "$GREENSTREET_HOST" "cd ${REMOTE_DIR} && ./run_prod.sh"

echo -e "${GREEN}${BOLD}🎉 MonMon Rust, LegMon Rust & Rauthy deployment to ${GREENSTREET_HOST} completed successfully!${NC}"

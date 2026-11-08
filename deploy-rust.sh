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

# 1. Verify build artifacts
if [[ ! -f "rust-rewrite/monmon-rs/target/release/monmon-rs" ]] || [[ ! -f "rust-rewrite/legmon-rs/target/release/legmon-rs" ]]; then
    echo -e "${RED}${BOLD}❌ ERROR: Rust release binaries not found!${NC}" >&2
    echo -e "${YELLOW}Please run ./build-rust.sh first.${NC}" >&2
    exit 1
fi

echo -e "${BLUE}${BOLD}==> [1/4] Preparing remote directories on ${GREENSTREET_HOST}...${NC}"
ssh "$GREENSTREET_HOST" "mkdir -p ${REMOTE_DIR}/rust-rewrite/monmon-rs ${REMOTE_DIR}/rust-rewrite/legmon-rs ${REMOTE_DIR}/rust-rewrite/rauthy/bootstrap ${REMOTE_DIR}/rust-rewrite/rauthy/data ${REMOTE_DIR}/logs"

echo -e "${BLUE}${BOLD}==> [2/4] A/B Database Isolation: Ensuring monmon-rust copy exists on ${GREENSTREET_HOST}...${NC}"
ssh "$GREENSTREET_HOST" "
if docker ps -f name=postgres --format '{{.Names}}' | grep -q postgres; then
    docker exec -i postgres bash -c '
        if ! psql -U postgres -lqt | cut -d \| -f 1 | grep -qw monmon-rust; then
            echo \"Creating isolated monmon-rust database cloned from monmon-play...\"
            psql -U postgres -c \"CREATE DATABASE \\\"monmon-rust\\\";\"
            pg_dump -U postgres -d \"monmon-play\" | psql -U postgres -d \"monmon-rust\" -q
            echo \"✅ Database monmon-rust cloned successfully for A/B parallel execution.\"
        else
            echo \"✅ Database monmon-rust already exists. Preserving data.\"
        fi
    '
else
    echo \"⚠️ PostgreSQL container not found yet; skipping DB clone step.\"
fi
"

echo -e "${BLUE}${BOLD}==> [3/4] Copying Rust deployment artifacts, Rauthy config, and compose file...${NC}"
# Copy monmon-rs
scp rust-rewrite/monmon-rs/Dockerfile "${GREENSTREET_HOST}:${REMOTE_DIR}/rust-rewrite/monmon-rs/"
ssh "$GREENSTREET_HOST" "mkdir -p ${REMOTE_DIR}/rust-rewrite/monmon-rs/target/release"
scp rust-rewrite/monmon-rs/target/release/monmon-rs "${GREENSTREET_HOST}:${REMOTE_DIR}/rust-rewrite/monmon-rs/target/release/"
scp -r rust-rewrite/monmon-rs/public "${GREENSTREET_HOST}:${REMOTE_DIR}/rust-rewrite/monmon-rs/"

# Copy legmon-rs
scp rust-rewrite/legmon-rs/Dockerfile "${GREENSTREET_HOST}:${REMOTE_DIR}/rust-rewrite/legmon-rs/"
ssh "$GREENSTREET_HOST" "mkdir -p ${REMOTE_DIR}/rust-rewrite/legmon-rs/target/release"
scp rust-rewrite/legmon-rs/target/release/legmon-rs "${GREENSTREET_HOST}:${REMOTE_DIR}/rust-rewrite/legmon-rs/target/release/"

# Copy rauthy configuration
scp rust-rewrite/rauthy/config.toml "${GREENSTREET_HOST}:${REMOTE_DIR}/rust-rewrite/rauthy/"
scp -r rust-rewrite/rauthy/bootstrap "${GREENSTREET_HOST}:${REMOTE_DIR}/rust-rewrite/rauthy/"

# Copy compose and run script
scp docker-compose.rust.yaml "${GREENSTREET_HOST}:${REMOTE_DIR}/"
sed "s|/home/circle/app|${REMOTE_DIR}|g" run_prod.sh | ssh "$GREENSTREET_HOST" "cat > ${REMOTE_DIR}/run_prod.sh && chmod +x ${REMOTE_DIR}/run_prod.sh"

if [[ -f .env ]]; then
    scp .env "${GREENSTREET_HOST}:${REMOTE_DIR}/.env"
    ssh "$GREENSTREET_HOST" "chmod 600 ${REMOTE_DIR}/.env"
fi

echo -e "${BLUE}${BOLD}==> [4/4] Starting Rust stack on ${GREENSTREET_HOST} via run_prod.sh...${NC}"
ssh "$GREENSTREET_HOST" "cd ${REMOTE_DIR} && ./run_prod.sh"

echo -e "${GREEN}${BOLD}🎉 MonMon Rust & Rauthy A/B deployment to ${GREENSTREET_HOST} completed successfully!${NC}"

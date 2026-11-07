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

ZIP_FILE="target/universal/monmon-play-java--1.0-SNAPSHOT.zip"
if [[ ! -f "$ZIP_FILE" ]]; then
    echo -e "${RED}${BOLD}❌ ERROR: $ZIP_FILE not found!${NC}" >&2
    echo -e "${YELLOW}Please run ./build.sh first.${NC}" >&2
    exit 1
fi

echo -e "${BLUE}${BOLD}==> [1/3] Preparing remote directory ${REMOTE_DIR}/build-context on ${GREENSTREET_HOST}...${NC}"
ssh "$GREENSTREET_HOST" "mkdir -p ${REMOTE_DIR}/build-context/target/universal ${REMOTE_DIR}/logs"

echo -e "${BLUE}${BOLD}==> [2/3] Copying universal zip, Dockerfile, docker-compose.yaml, run_prod.sh, and .env to ${GREENSTREET_HOST}...${NC}"
scp "$ZIP_FILE" "${GREENSTREET_HOST}:${REMOTE_DIR}/build-context/target/universal/"
scp Dockerfile "${GREENSTREET_HOST}:${REMOTE_DIR}/build-context/"

COMPOSE_FILE="/home/inbrewj/workshop/frontdown/backup/inbrewj/workshop/oz.urawizard.com/monmon/app/docker-compose.yaml"
if [[ -f "$COMPOSE_FILE" ]]; then
    scp "$COMPOSE_FILE" "${GREENSTREET_HOST}:${REMOTE_DIR}/docker-compose.yaml"
fi

# Adapt APP_DIR in run_prod.sh from /home/circle/app to /home/greenstreet/app on the remote host
sed "s|/home/circle/app|${REMOTE_DIR}|g" run_prod.sh | ssh "$GREENSTREET_HOST" "cat > ${REMOTE_DIR}/run_prod.sh && chmod +x ${REMOTE_DIR}/run_prod.sh"

if [[ -f .env ]]; then
    scp .env "${GREENSTREET_HOST}:${REMOTE_DIR}/.env"
    ssh "$GREENSTREET_HOST" "chmod 600 ${REMOTE_DIR}/.env"
fi

echo -e "${BLUE}${BOLD}==> [3/3] Building monmon:latest natively on ${GREENSTREET_HOST} and executing run_prod.sh...${NC}"
ssh "$GREENSTREET_HOST" "cd ${REMOTE_DIR}/build-context && docker build -t monmon:latest . && cd ${REMOTE_DIR} && ./run_prod.sh"

echo -e "${GREEN}${BOLD}🎉 MonMon deployment to ${GREENSTREET_HOST} completed successfully!${NC}"

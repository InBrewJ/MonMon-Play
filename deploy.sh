#!/usr/bin/env bash
set -euo pipefail

# ANSI color codes
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
BOLD='\033[1m'
NC='\033[0m' # No Color

REMOTE_USER_HOST="circle@boom.dryark.uk"
REMOTE_DEST_DIR="/home/circle/app"
RUN_PROD_SCRIPT="run_prod.sh"

echo -e "${BLUE}${BOLD}==> [1/3] Verifying local Docker image monmon:latest...${NC}"
if ! docker image inspect monmon:latest >/dev/null 2>&1; then
    echo -e "${RED}${BOLD}❌ ERROR: Local Docker image 'monmon:latest' not found!${NC}" >&2
    echo -e "${YELLOW}Please run ./build.sh first.${NC}" >&2
    exit 1
fi

# 1. Sync local root .env to remote if it exists
if [ -f ".env" ]; then
    echo -e "${BLUE}==> Syncing root .env to ${REMOTE_USER_HOST}:${REMOTE_DEST_DIR}/.env...${NC}"
    scp -o StrictHostKeyChecking=no .env "${REMOTE_USER_HOST}:${REMOTE_DEST_DIR}/.env"
    ssh -o StrictHostKeyChecking=no "${REMOTE_USER_HOST}" "chmod 600 ${REMOTE_DEST_DIR}/.env"
    echo -e "${GREEN}✅ .env transferred and secured.${NC}"
fi

# 2. Transfer Docker image via docker save & docker load
echo -e "${BLUE}${BOLD}==> [2/3] Transferring Docker image monmon:latest to ${REMOTE_USER_HOST}...${NC}"
docker save monmon:latest | gzip | ssh -o StrictHostKeyChecking=no "${REMOTE_USER_HOST}" "gunzip | docker load"
echo -e "${GREEN}✅ Docker image transferred and loaded on remote server.${NC}"

# 3. Execute remote run_prod.sh via SSH
if [ ! -f "$RUN_PROD_SCRIPT" ]; then
    echo -e "${RED}${BOLD}❌ ERROR: Local script '$RUN_PROD_SCRIPT' not found!${NC}" >&2
    exit 1
fi

echo -e "${BLUE}${BOLD}==> [3/3] Executing remote deployment script on ${REMOTE_USER_HOST}...${NC}"
if ! ssh -o StrictHostKeyChecking=no "${REMOTE_USER_HOST}" 'bash -s' < "$RUN_PROD_SCRIPT"; then
    echo -e "${RED}${BOLD}❌ ERROR: Remote execution of $RUN_PROD_SCRIPT failed on ${REMOTE_USER_HOST}!${NC}" >&2
    exit 1
fi

echo -e "${GREEN}${BOLD}🎉 MonMon deployment to ${REMOTE_USER_HOST} completed successfully!${NC}"

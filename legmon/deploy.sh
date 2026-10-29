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
REMOTE_LOG_DIR="/home/circle/app/legmon/logs"

echo -e "${BLUE}${BOLD}==> [1/3] Verifying local Docker image legmon:latest...${NC}"
if ! docker image inspect legmon:latest >/dev/null 2>&1; then
    echo -e "${RED}${BOLD}❌ ERROR: Local Docker image 'legmon:latest' not found!${NC}" >&2
    echo -e "${YELLOW}Please run ./build.sh first.${NC}" >&2
    exit 1
fi

echo -e "${BLUE}${BOLD}==> [2/3] Transferring Docker image to ${REMOTE_USER_HOST} via docker save & docker load...${NC}"
docker save legmon:latest | gzip | ssh -o StrictHostKeyChecking=no "${REMOTE_USER_HOST}" "gunzip | docker load"
echo -e "${GREEN}✅ Docker image transferred and loaded on remote server.${NC}"

# Sync local legmon/.env to remote if it exists
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [ -f "$SCRIPT_DIR/.env" ]; then
    echo -e "${BLUE}==> Transferring local legmon/.env to ${REMOTE_USER_HOST}:/home/circle/app/legmon/.env...${NC}"
    ssh -o StrictHostKeyChecking=no "${REMOTE_USER_HOST}" "mkdir -p /home/circle/app/legmon"
    scp -o StrictHostKeyChecking=no "$SCRIPT_DIR/.env" "${REMOTE_USER_HOST}:/home/circle/app/legmon/.env"
    echo -e "${GREEN}✅ .env transferred to remote host.${NC}"
fi

echo -e "${BLUE}${BOLD}==> [3/3] Launching LegMon container on ${REMOTE_USER_HOST}...${NC}"
ssh -o StrictHostKeyChecking=no "${REMOTE_USER_HOST}" 'bash -s' << 'REMOTE_SCRIPT'
set -euo pipefail

mkdir -p /home/circle/app/legmon/logs

# Check for .env file
ENV_ARG=""
if [ -f /home/circle/app/legmon/.env ]; then
    ENV_ARG="--env-file /home/circle/app/legmon/.env"
    echo "Found /home/circle/app/legmon/.env - loading environment into container..."
fi

# Stop existing container if running
if docker ps -q -f name=^legmon$ | grep -q .; then
    echo "Stopping existing legmon container..."
    docker stop legmon || true
fi

if docker ps -a -q -f name=^legmon$ | grep -q .; then
    echo "Removing previous legmon container..."
    docker rm legmon || true
fi

echo "Starting new LegMon container on port 9005..."
docker run -d \
    --name legmon \
    --restart always \
    --network host \
    -v /home/circle/app/legmon/logs:/app/logs \
    $ENV_ARG \
    -e PORT=9005 \
    -e MONMON_URL="http://127.0.0.1:9000" \
    legmon:latest

sleep 3
if docker ps | grep -q legmon; then
    echo "✅ LegMon container is running:"
    docker ps -f name=^legmon$
    echo "Status probe:"
    curl -s http://127.0.0.1:9005/status || echo "Probe waiting..."
else
    echo "❌ ERROR: LegMon failed to start! Container logs:" >&2
    docker logs --tail 30 legmon || true
    exit 1
fi
REMOTE_SCRIPT

echo -e "${GREEN}${BOLD}🎉 LegMon deployment to ${REMOTE_USER_HOST} completed successfully!${NC}"

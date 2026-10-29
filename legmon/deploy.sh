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

# Sync local root .env or legmon/.env to remote if it exists
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
if [ -f "$ROOT_DIR/.env" ]; then
    echo -e "${BLUE}==> Transferring root .env to ${REMOTE_USER_HOST}:/home/circle/app/.env...${NC}"
    scp -o StrictHostKeyChecking=no "$ROOT_DIR/.env" "${REMOTE_USER_HOST}:/home/circle/app/.env"
    ssh -o StrictHostKeyChecking=no "${REMOTE_USER_HOST}" "chmod 600 /home/circle/app/.env"
    echo -e "${GREEN}✅ Unified root .env transferred to remote host.${NC}"
elif [ -f "$SCRIPT_DIR/.env" ]; then
    echo -e "${BLUE}==> Transferring local legmon/.env to ${REMOTE_USER_HOST}:/home/circle/app/legmon/.env...${NC}"
    ssh -o StrictHostKeyChecking=no "${REMOTE_USER_HOST}" "mkdir -p /home/circle/app/legmon"
    scp -o StrictHostKeyChecking=no "$SCRIPT_DIR/.env" "${REMOTE_USER_HOST}:/home/circle/app/legmon/.env"
    echo -e "${GREEN}✅ .env transferred to remote host.${NC}"
fi

echo -e "${BLUE}${BOLD}==> [3/3] Launching LegMon container on ${REMOTE_USER_HOST}...${NC}"
ssh -o StrictHostKeyChecking=no "${REMOTE_USER_HOST}" 'bash -s' << 'REMOTE_SCRIPT'
set -euo pipefail

mkdir -p /home/circle/app/legmon/logs

# Check for .env file (either root .env or legmon/.env)
ENV_ARG=""
if [ -f /home/circle/app/.env ]; then
    ENV_ARG="--env-file /home/circle/app/.env"
    echo "Found /home/circle/app/.env - loading unified environment into LegMon container..."
elif [ -f /home/circle/app/legmon/.env ]; then
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

echo "Starting new LegMon container on port 9005 with log rotation..."
docker run -d \
    --name legmon \
    --restart always \
    --network host \
    --log-driver json-file \
    --log-opt max-size=10m \
    --log-opt max-file=5 \
    -v /home/circle/app/legmon/logs:/app/logs \
    $ENV_ARG \
    -e PORT=9005 \
    -e MONMON_URL="http://127.0.0.1:9000" \
    legmon:latest

echo "Waiting for LegMon service to become healthy (probing http://127.0.0.1:9005/health)..."
MAX_ATTEMPTS=25
ATTEMPT=1
HEALTHY=false

while [ "$ATTEMPT" -le "$MAX_ATTEMPTS" ]; do
    RUNNING=$(docker inspect -f '{{.State.Running}}' legmon 2>/dev/null || echo "false")
    if [ "$RUNNING" != "true" ]; then
        echo "❌ ERROR: LegMon container is not running (State.Running=$RUNNING)!" >&2
        docker logs --tail 40 legmon || true
        exit 1
    fi

    HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" --max-time 3 http://127.0.0.1:9005/health 2>/dev/null || true)
    if [ "$HTTP_CODE" = "200" ]; then
        HEALTHY=true
        break
    fi

    echo "Attempt $ATTEMPT/$MAX_ATTEMPTS: LegMon starting up (HTTP probe: $HTTP_CODE)... waiting 2s"
    sleep 2
    ATTEMPT=$((ATTEMPT + 1))
done

if [ "$HEALTHY" = true ]; then
    echo "✅ LegMon is healthy and accepting traffic (HTTP $HTTP_CODE)!"
    docker ps -f name=^legmon$
    echo "LegMon Status Summary:"
    curl -s http://127.0.0.1:9005/status | head -n 15 || true
    echo ""
else
    echo "❌ ERROR: LegMon health check failed after $((MAX_ATTEMPTS * 2)) seconds! Container logs:" >&2
    docker logs --tail 40 legmon || true
    exit 1
fi
REMOTE_SCRIPT

echo -e "${GREEN}${BOLD}🎉 LegMon deployment to ${REMOTE_USER_HOST} completed successfully!${NC}"

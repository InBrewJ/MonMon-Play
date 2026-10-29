#!/usr/bin/env bash
set -euo pipefail

APP_DIR="/home/circle/app"
cd "$APP_DIR"

echo "=== MonMon Dockerized Deployment & Startup ==="

# 1. Stop any legacy non-docker MonMon processes
PID_FILE="$APP_DIR/monmon-play/monmon-play-java--1.0-SNAPSHOT/RUNNING_PID"
if [ -f "$PID_FILE" ]; then
    PREV_PID=$(cat "$PID_FILE" 2>/dev/null || true)
    if [ -n "$PREV_PID" ] && kill -0 "$PREV_PID" 2>/dev/null; then
        echo "Found legacy MonMon process PID $PREV_PID. Stopping..."
        kill -15 "$PREV_PID" 2>/dev/null || true
        sleep 2
        kill -9 "$PREV_PID" 2>/dev/null || true
    fi
    rm -f "$PID_FILE"
fi

FALLBACK_PIDS=$(pgrep -f "monmon-play-java-" || true)
if [ -n "$FALLBACK_PIDS" ]; then
    echo "Found running legacy monmon-play processes: $FALLBACK_PIDS. Stopping..."
    kill -15 $FALLBACK_PIDS 2>/dev/null || true
    sleep 2
    kill -9 $FALLBACK_PIDS 2>/dev/null || true
fi

# 2. Stop and remove existing monmon Docker container
if docker ps -q -f name=^monmon$ | grep -q .; then
    echo "Stopping existing monmon container..."
    docker stop monmon || true
fi

if docker ps -a -q -f name=^monmon$ | grep -q .; then
    echo "Removing previous monmon container..."
    docker rm monmon || true
fi

# 3. Environment configuration from single root .env
ENV_FILE="$APP_DIR/.env"
ENV_ARG=""
if [ -f "$ENV_FILE" ]; then
    ENV_ARG="--env-file $ENV_FILE"
    echo "Found $ENV_FILE - loading environment into MonMon container."
    chmod 600 "$ENV_FILE" || true
else
    echo "⚠️ WARNING: No $ENV_FILE found at $APP_DIR/.env. Container will use internal defaults."
fi

# 4. Start new MonMon Docker container with host networking and log rotation
mkdir -p "$APP_DIR/logs"
echo "Starting new MonMon container on host network (port 9000) with log rotation..."
docker run -d \
    --name monmon \
    --restart always \
    --network host \
    --log-driver json-file \
    --log-opt max-size=10m \
    --log-opt max-file=5 \
    -v "$APP_DIR/logs:/app/logs" \
    $ENV_ARG \
    monmon:latest

sleep 3

if docker ps | grep -q monmon; then
    echo "✅ MonMon container is running:"
    docker ps -f name=^monmon$
    echo "Status probe (HTTP localhost:9000):"
    curl -I -s http://127.0.0.1:9000/ || echo "Container started, waiting for Play framework to finish initial binding..."
else
    echo "❌ ERROR: MonMon container failed to start! Container logs:" >&2
    docker logs --tail 30 monmon || true
    exit 1
fi

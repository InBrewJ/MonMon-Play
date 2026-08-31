#!/usr/bin/env bash
set -e

export PATH=/home/circle/.sdkman/candidates/java/current/bin:$PATH
APP_DIR="/home/circle/app"
cd "$APP_DIR"

echo "=== MonMon Deployment & Startup ==="

# 1. Stop previously running MonMon instance safely
PID_FILE="$APP_DIR/monmon-play/monmon-play-java--1.0-SNAPSHOT/RUNNING_PID"

if [ -f "$PID_FILE" ]; then
    PREV_PID=$(cat "$PID_FILE" 2>/dev/null || true)
    if [ -n "$PREV_PID" ] && kill -0 "$PREV_PID" 2>/dev/null; then
        echo "Found running MonMon process PID $PREV_PID from $PID_FILE. Stopping gracefully..."
        kill -15 "$PREV_PID" 2>/dev/null || true
        for i in {1..10}; do
            if kill -0 "$PREV_PID" 2>/dev/null; then
                sleep 1
            else
                break
            fi
        done
        if kill -0 "$PREV_PID" 2>/dev/null; then
            echo "Process $PREV_PID did not exit after 10s. Force killing (SIGKILL)..."
            kill -9 "$PREV_PID" 2>/dev/null || true
            sleep 1
        fi
    fi
fi

# Fallback: find any leftover monmon-play process specifically (without killing Keycloak/Docker)
FALLBACK_PIDS=$(pgrep -f "monmon-play-java-" || true)
if [ -n "$FALLBACK_PIDS" ]; then
    echo "Found running monmon-play processes by name: $FALLBACK_PIDS. Stopping..."
    kill -15 $FALLBACK_PIDS 2>/dev/null || true
    sleep 2
    kill -9 $FALLBACK_PIDS 2>/dev/null || true
fi

echo "Previous MonMon processes stopped."

# 2. Extract new release
echo "Extracting new release..."
rm -rf ./monmon-play
unzip -q -o monmon-play-java--1.0-SNAPSHOT.zip -d ./monmon-play

# Ensure any stale RUNNING_PID is cleaned up
rm -f ./monmon-play/monmon-play-java--1.0-SNAPSHOT/RUNNING_PID

# 3. Apply secure production configuration
if [ -f "$APP_DIR/secureProd.conf" ]; then
    echo "Applying secureProd.conf..."
    cat "$APP_DIR/secureProd.conf" > "$APP_DIR/monmon-play/monmon-play-java--1.0-SNAPSHOT/conf/prod.conf"
fi

# 4. Start new MonMon process
echo "Starting new MonMon instance..."
chmod +x ./monmon-play/monmon-play-java--1.0-SNAPSHOT/bin/monmon-play-java-
nohup ./monmon-play/monmon-play-java--1.0-SNAPSHOT/bin/monmon-play-java- \
    -Dconfig.resource=prod.conf \
    -Dplay.http.secret.key="41fa^pSzvve:iunSpW5HproHJ^EF5Ml1o[1Wfbc[[gOD?jHC;[t?j9Ms0S8=ve</" \
    > ./monmon.out 2> ./monmon.err &

NEW_PID=$!
echo "MonMon launched with PID: $NEW_PID"
sleep 2

if kill -0 "$NEW_PID" 2>/dev/null; then
    echo "MonMon is running successfully (PID $NEW_PID)."
else
    echo "❌ ERROR: MonMon exited immediately. Check ./monmon.err and ./monmon.out:" >&2
    tail -n 20 ./monmon.err || true
    exit 1
fi
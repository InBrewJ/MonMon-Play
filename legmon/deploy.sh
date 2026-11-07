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
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$SCRIPT_DIR"

GREENSTREET_HOST="${1:-greenstreet@greenstreet.local}"
REMOTE_USER="${GREENSTREET_HOST%@*}"
REMOTE_DIR="/home/${REMOTE_USER}/app/legmon"

JAR_FILE="target/scala-2.13/legmon-assembly-1.0.0.jar"
if [[ ! -f "$JAR_FILE" ]]; then
    echo -e "${RED}${BOLD}❌ ERROR: $JAR_FILE not found!${NC}" >&2
    echo -e "${YELLOW}Please run ./build.sh first.${NC}" >&2
    exit 1
fi

echo -e "${BLUE}${BOLD}==> [1/3] Preparing ${REMOTE_DIR}/build-context/target/scala-2.13 on ${GREENSTREET_HOST}...${NC}"
ssh "$GREENSTREET_HOST" "mkdir -p ${REMOTE_DIR}/build-context/target/scala-2.13 ${REMOTE_DIR}/logs"

echo -e "${BLUE}${BOLD}==> [2/3] Copying assembly jar, ARM64 Dockerfile, and run_legmon.sh to ${GREENSTREET_HOST}...${NC}"
scp "$JAR_FILE" "${GREENSTREET_HOST}:${REMOTE_DIR}/build-context/target/scala-2.13/"

if [[ -f "$ROOT_DIR/.env" ]]; then
    scp "$ROOT_DIR/.env" "${GREENSTREET_HOST}:/home/${REMOTE_USER}/app/.env"
    ssh "$GREENSTREET_HOST" "chmod 600 /home/${REMOTE_USER}/app/.env"
fi

# Write ARM64-clean Dockerfile
ssh "$GREENSTREET_HOST" "cat > ${REMOTE_DIR}/build-context/Dockerfile" <<'EOF'
FROM eclipse-temurin:21-jre-jammy

RUN apt-get update && apt-get install -y --no-install-recommends \
    tesseract-ocr \
    tesseract-ocr-eng \
    poppler-utils \
    curl \
    ca-certificates \
    tar \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
RUN mkdir -p /app/logs

COPY target/scala-2.13/legmon-assembly-1.0.0.jar /app/legmon.jar

EXPOSE 9005

CMD ["java", "-Xms64m", "-Xmx192m", "-Duser.dir=/app", "-jar", "/app/legmon.jar"]
EOF

ssh "$GREENSTREET_HOST" "cat > ${REMOTE_DIR}/run_legmon.sh && chmod +x ${REMOTE_DIR}/run_legmon.sh" <<EOF
#!/usr/bin/env bash
set -euo pipefail

mkdir -p ${REMOTE_DIR}/logs

ENV_ARG=""
if [ -f /home/${REMOTE_USER}/app/.env ]; then
    ENV_ARG="--env-file /home/${REMOTE_USER}/app/.env"
fi

docker stop legmon 2>/dev/null || true
docker rm legmon 2>/dev/null || true

docker run -d \\
    --name legmon \\
    --restart always \\
    --network host \\
    --log-driver json-file \\
    --log-opt max-size=10m \\
    --log-opt max-file=5 \\
    -v ${REMOTE_DIR}/logs:/app/logs \\
    \$ENV_ARG \\
    -e PORT=9005 \\
    -e MONMON_URL="http://127.0.0.1:9000" \\
    legmon:latest

echo "Waiting for LegMon on http://127.0.0.1:9005/health..."
for i in {1..25}; do
    CODE=\$(curl -s -o /dev/null -w "%{http_code}" --max-time 3 http://127.0.0.1:9005/health 2>/dev/null || true)
    if [ "\$CODE" = "200" ]; then
        echo "✅ LegMon is healthy (HTTP 200)!"
        curl -s http://127.0.0.1:9005/status || true
        echo ""
        exit 0
    fi
    echo "Attempt \$i/25 (HTTP \$CODE)... waiting 2s"
    sleep 2
done
docker logs --tail 40 legmon
exit 1
EOF

echo -e "${BLUE}${BOLD}==> [3/3] Building legmon:latest natively on ${GREENSTREET_HOST} and executing run_legmon.sh...${NC}"
ssh "$GREENSTREET_HOST" "cd ${REMOTE_DIR}/build-context && docker build -t legmon:latest . && cd ${REMOTE_DIR} && ./run_legmon.sh"

echo -e "${GREEN}${BOLD}🎉 LegMon deployment to ${GREENSTREET_HOST} completed successfully!${NC}"

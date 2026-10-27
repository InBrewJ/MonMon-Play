#!/usr/bin/env bash
set -euo pipefail

# ANSI color codes
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
BOLD='\033[1m'
NC='\033[0m' # No Color

echo -e "${BLUE}${BOLD}==> [1/3] Generating build metadata in conf/build.properties...${NC}"
BUILD_TIMESTAMP=$(date -u +"%Y-%m-%d %H:%M:%S UTC")
BUILD_COMMIT=$(git rev-parse --short HEAD 2>/dev/null || echo "unknown")
BUILD_NUMBER=$(git rev-list --count HEAD 2>/dev/null || echo "1")

cat <<EOF > conf/build.properties
build.number=${BUILD_NUMBER}
build.timestamp=${BUILD_TIMESTAMP}
build.commit=${BUILD_COMMIT}
EOF
echo -e "${GREEN}Build info generated: #${BUILD_NUMBER} (${BUILD_COMMIT}) at ${BUILD_TIMESTAMP}${NC}"

echo -e "${BLUE}${BOLD}==> [2/3] Starting build with sbt dist...${NC}"

# Remove any stale zip before building so we never deploy an old artifact on failure
ZIP_FILE="./target/universal/monmon-play-java--1.0-SNAPSHOT.zip"
rm -f "$ZIP_FILE"

if ! sbt dist; then
    echo -e "${RED}${BOLD}❌ ERROR: 'sbt dist' failed! Build aborted.${NC}" >&2
    exit 1
fi

if [ ! -f "$ZIP_FILE" ] || [ ! -s "$ZIP_FILE" ]; then
    echo -e "${RED}${BOLD}❌ ERROR: Distribution artifact $ZIP_FILE was not created or is empty!${NC}" >&2
    exit 1
fi

ZIP_SIZE=$(du -h "$ZIP_FILE" | cut -f1)
echo -e "${GREEN}${BOLD}✅ Build successful!${NC} Created: ${ZIP_FILE} (${ZIP_SIZE})"
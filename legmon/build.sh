#!/usr/bin/env bash
set -euo pipefail

# ANSI color codes
RED='\033[0;31m'
GREEN='\033[0;32m'
BLUE='\033[0;34m'
BOLD='\033[1m'
NC='\033[0m' # No Color

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

echo -e "${BLUE}${BOLD}==> [1/2] Building LegMon assembly jar with sbt assembly...${NC}"
sbt assembly

JAR_FILE="target/scala-2.13/legmon-assembly-1.0.0.jar"
if [ ! -f "$JAR_FILE" ]; then
    echo -e "${RED}${BOLD}❌ ERROR: Assembly artifact $JAR_FILE not found!${NC}" >&2
    exit 1
fi

echo -e "${BLUE}${BOLD}==> [2/2] Building Docker image legmon:latest...${NC}"

CONF_FILE="$SCRIPT_DIR/../conf/secureProd.conf"
MISTRAL_KEY=""
MISTRAL_MODEL="mistral-small-latest"

if [ -f "$CONF_FILE" ]; then
    MISTRAL_KEY=$(grep -oP 'apiKey\s*=\s*"\K[^"]+' "$CONF_FILE" 2>/dev/null || grep -o 'apiKey\s*=\s*"[^"]*"' "$CONF_FILE" | head -1 | sed 's/.*"\(.*\)".*/\1/' || true)
    EXTRACTED_MODEL=$(grep -oP 'model\s*=\s*"\K[^"]+' "$CONF_FILE" 2>/dev/null || grep -o 'model\s*=\s*"[^"]*"' "$CONF_FILE" | head -1 | sed 's/.*"\(.*\)".*/\1/' || true)
    if [ -n "$EXTRACTED_MODEL" ]; then
        MISTRAL_MODEL="$EXTRACTED_MODEL"
    fi
    echo -e "${GREEN}Found secureProd.conf: baking Mistral configuration into Docker image.${NC}"
fi

# Fallback to current env var if secureProd.conf was missing
if [ -z "$MISTRAL_KEY" ] && [ -n "${MISTRAL_API_KEY:-}" ]; then
    MISTRAL_KEY="$MISTRAL_API_KEY"
fi

docker build \
    --build-arg MISTRAL_API_KEY="$MISTRAL_KEY" \
    --build-arg MISTRAL_MODEL="$MISTRAL_MODEL" \
    -t legmon:latest .

echo -e "${GREEN}${BOLD}✅ LegMon Docker image built successfully! (legmon:latest)${NC}"

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
docker build -t legmon:latest .

echo -e "${GREEN}${BOLD}✅ LegMon Docker image built successfully! (legmon:latest)${NC}"

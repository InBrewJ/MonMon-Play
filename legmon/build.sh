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

if ! command -v sbt >/dev/null 2>&1 && [ -d "$HOME/.sdkman/candidates/sbt/current/bin" ]; then
    export PATH="$HOME/.sdkman/candidates/sbt/current/bin:$HOME/.sdkman/candidates/java/current/bin:$PATH"
fi

echo -e "${BLUE}${BOLD}==> [1/1] Building LegMon universal assembly jar via sbt assembly...${NC}"
sbt assembly

JAR_FILE="target/scala-2.13/legmon-assembly-1.0.0.jar"
if [[ ! -f "$JAR_FILE" ]]; then
    echo -e "${RED}${BOLD}❌ ERROR: Assembly artifact $JAR_FILE not found!${NC}" >&2
    exit 1
fi

echo -e "${GREEN}${BOLD}✅ LegMon universal assembly jar ready! ($JAR_FILE)${NC}"

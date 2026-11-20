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
cd "$SCRIPT_DIR"

if ! command -v cargo >/dev/null 2>&1 && [ -d "$HOME/.cargo/bin" ]; then
    export PATH="$HOME/.cargo/bin:$PATH"
fi

GIT_COMMIT=$(git rev-parse --short HEAD 2>/dev/null || echo "local")
GIT_COUNT=$(git rev-list --count HEAD 2>/dev/null || echo "dev")
BUILD_TIME=$(date -u "+%Y-%m-%d %H:%M:%S UTC")
echo "build.commit=${GIT_COMMIT}" > modules/monmon-rs/build.properties
echo "build.number=${GIT_COUNT}" >> modules/monmon-rs/build.properties
echo "build.timestamp=${BUILD_TIME}" >> modules/monmon-rs/build.properties

echo -e "${BLUE}${BOLD}==> [1/4] Building monmon-rs native release binary (commit: ${GIT_COMMIT}, build: ${GIT_COUNT})...${NC}"
(cd modules/monmon-rs && cargo build --release)
mkdir -p modules/monmon-rs/prebuilt
cp modules/monmon-rs/target/release/monmon-rs modules/monmon-rs/prebuilt/monmon-rs

echo -e "${BLUE}${BOLD}==> [2/4] Building legmon-rs native release binary...${NC}"
(cd modules/legmon-rs && cargo build --release)
mkdir -p modules/legmon-rs/prebuilt
cp modules/legmon-rs/target/release/legmon-rs modules/legmon-rs/prebuilt/legmon-rs

echo -e "${BLUE}${BOLD}==> [3/4] Building memmon-rs native release binary...${NC}"
(cd modules/memmon-rs && cargo build --release)
mkdir -p modules/memmon-rs/prebuilt
cp modules/memmon-rs/target/release/memmon-rs modules/memmon-rs/prebuilt/memmon-rs

echo -e "${BLUE}${BOLD}==> [4/4] Syncing public assets...${NC}"
cp -ru public/* modules/monmon-rs/public/ 2>/dev/null || true

echo -e "${GREEN}${BOLD}🎉 Build completed successfully! Rust deployment artifacts ready.${NC}"

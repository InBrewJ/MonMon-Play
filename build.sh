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

echo -e "${BLUE}${BOLD}==> [1/3] Building monmon-rs native release binary...${NC}"
(cd rust-rewrite/monmon-rs && cargo build --release)

echo -e "${BLUE}${BOLD}==> [2/3] Building legmon-rs native release binary...${NC}"
(cd rust-rewrite/legmon-rs && cargo build --release)

echo -e "${BLUE}${BOLD}==> [3/3] Syncing public assets & building Docker container images...${NC}"
cp -a public/* rust-rewrite/monmon-rs/public/
docker compose -f docker-compose.rust.yaml build

echo -e "${GREEN}${BOLD}🎉 Build completed successfully! Rust deployment artifacts ready.${NC}"

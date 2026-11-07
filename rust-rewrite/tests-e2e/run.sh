#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

if ! command -v node >/dev/null 2>&1 && [ -d "$HOME/.nvm/versions/node" ]; then
    NODE_BIN=$(find "$HOME/.nvm/versions/node" -maxdepth 2 -name bin 2>/dev/null | head -n 1)
    if [ -n "$NODE_BIN" ]; then
        export PATH="$NODE_BIN:$PATH"
    fi
fi

export BASE_URL="${BASE_URL:-http://127.0.0.1:9000}"

echo "========================================================================="
echo " MonMon Playwright E2E Test Suite (Driven by playwright-cli)"
echo " Target URL: $BASE_URL"
echo "========================================================================="

npx playwright test "$@"

#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"

if ! command -v node >/dev/null 2>&1 && [ -d "$HOME/.nvm/versions/node" ]; then
    NODE_BIN=$(find "$HOME/.nvm/versions/node" -maxdepth 2 -name bin 2>/dev/null | head -n 1)
    if [ -n "$NODE_BIN" ]; then
        export PATH="$NODE_BIN:$PATH"
    fi
fi

export BASE_URL="${BASE_URL:-http://127.0.0.1:9000}"
export LEGMON_URL="${LEGMON_URL:-http://127.0.0.1:9005}"
export CORE_URL="${CORE_URL:-http://127.0.0.1:9001}"

echo "========================================================================="
echo " MonMon & LegMon Language-Agnostic Contract Test Suite"
echo " Target MonMon Play:     $BASE_URL"
echo " Target LegMon Service:  $LEGMON_URL"
echo " Target MonMon-rs Core:  $CORE_URL"
echo "========================================================================="

node --test "$SCRIPT_DIR"/legmon.contract.test.mjs "$SCRIPT_DIR"/monmon.contract.test.mjs

if curl -s "$CORE_URL/health" >/dev/null 2>&1; then
    echo "==> Running MonMon-rs Core Primitives Contract Tests..."
    node --test "$SCRIPT_DIR"/monmon-core-rs.contract.test.mjs
fi

echo "✅ All REST & HTTP contract tests PASSED!"

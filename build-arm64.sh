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

TARGET="aarch64-unknown-linux-gnu"
COMPONENT="${1:-all}"

if ! command -v aarch64-linux-gnu-gcc >/dev/null 2>&1; then
    echo -e "${RED}${BOLD}ERROR: aarch64-linux-gnu-gcc not found!${NC}" >&2
    echo -e "Install it with: ${BOLD}sudo apt update && sudo apt install -y gcc-aarch64-linux-gnu libc6-dev-arm64-cross${NC}" >&2
    exit 1
fi

if ! rustup target list --installed | grep -q "^${TARGET}$"; then
    echo -e "${YELLOW}Installing Rust target ${TARGET}...${NC}"
    rustup target add "$TARGET"
fi

export CARGO_TARGET_AARCH64_UNKNOWN_LINUX_GNU_LINKER=aarch64-linux-gnu-gcc

build_monmon() {
    GIT_COMMIT=$(git rev-parse --short HEAD 2>/dev/null || echo "local")
    GIT_COUNT=$(git rev-list --count HEAD 2>/dev/null || echo "dev")
    BUILD_TIME=$(date -u "+%Y-%m-%d %H:%M:%S UTC")
    echo "build.commit=${GIT_COMMIT}" > modules/monmon-rs/build.properties
    echo "build.number=${GIT_COUNT}" >> modules/monmon-rs/build.properties
    echo "build.timestamp=${BUILD_TIME}" >> modules/monmon-rs/build.properties

    echo -e "${BLUE}${BOLD}==> Cross-compiling monmon-rs for ARM64 (${TARGET}, commit: ${GIT_COMMIT}, build: ${GIT_COUNT})...${NC}"
    (
        cd modules/monmon-rs && \
        BUILD_COMMIT="$GIT_COMMIT" BUILD_NUMBER="$GIT_COUNT" BUILD_TIMESTAMP="$BUILD_TIME" \
        cargo build --release --target "$TARGET"
    )
    mkdir -p modules/monmon-rs/prebuilt
    cp "modules/monmon-rs/target/${TARGET}/release/monmon-rs" modules/monmon-rs/prebuilt/monmon-rs
    chmod +x modules/monmon-rs/prebuilt/monmon-rs
    cp -ru public/* modules/monmon-rs/public/ 2>/dev/null || true
    verify_arm64_binary "modules/monmon-rs/prebuilt/monmon-rs"
}

build_legmon() {
    echo -e "${BLUE}${BOLD}==> Cross-compiling legmon-rs for ARM64 (${TARGET})...${NC}"
    (cd modules/legmon-rs && cargo build --release --target "$TARGET")
    mkdir -p modules/legmon-rs/prebuilt
    cp "modules/legmon-rs/target/${TARGET}/release/legmon-rs" modules/legmon-rs/prebuilt/legmon-rs
    chmod +x modules/legmon-rs/prebuilt/legmon-rs
    verify_arm64_binary "modules/legmon-rs/prebuilt/legmon-rs"
}

build_memmon() {
    echo -e "${BLUE}${BOLD}==> Cross-compiling memmon-rs for ARM64 (${TARGET})...${NC}"
    (cd modules/memmon-rs && cargo build --release --target "$TARGET")
    mkdir -p modules/memmon-rs/prebuilt
    cp "modules/memmon-rs/target/${TARGET}/release/memmon-rs" modules/memmon-rs/prebuilt/memmon-rs
    chmod +x modules/memmon-rs/prebuilt/memmon-rs
    verify_arm64_binary "modules/memmon-rs/prebuilt/memmon-rs"
}

verify_arm64_binary() {
    local bin_path="$1"
    if command -v file >/dev/null 2>&1; then
        local file_info
        file_info=$(file "$bin_path")
        if [[ "$file_info" != *"ARM aarch64"* ]]; then
            echo -e "${RED}${BOLD}ERROR: Expected ARM aarch64 binary at ${bin_path}, got: ${file_info}${NC}" >&2
            exit 1
        fi
        echo -e "${GREEN}✓ Staged ARM64 binary: ${bin_path} ($(du -h "$bin_path" | cut -f1))${NC}"
    fi
}

case "$COMPONENT" in
    monmon|monmon-rs)
        build_monmon
        ;;
    legmon|legmon-rs)
        build_legmon
        ;;
    memmon|memmon-rs)
        build_memmon
        ;;
    all)
        build_monmon
        build_legmon
        build_memmon
        ;;
    *)
        echo -e "${RED}Usage: $0 [monmon|legmon|memmon|all]${NC}" >&2
        exit 1
        ;;
esac

echo -e "${GREEN}${BOLD}🎉 ARM64 cross-compilation complete! Prebuilt binary ready for Raspberry Pi deployment.${NC}"

#!/usr/bin/env bash
set -euo pipefail

# ANSI color codes
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
BOLD='\033[1m'
NC='\033[0m' # No Color

ZIP_FILE="./target/universal/monmon-play-java--1.0-SNAPSHOT.zip"
REMOTE_USER_HOST="circle@boom.dryark.uk"
REMOTE_DEST_DIR="/home/circle/app"
RUN_PROD_SCRIPT="run_prod.sh"

echo -e "${BLUE}${BOLD}==> [2/2] Preparing deployment...${NC}"

# 1. Verify build artifact exists and is non-empty
if [ ! -f "$ZIP_FILE" ] || [ ! -s "$ZIP_FILE" ]; then
    echo -e "${RED}${BOLD}❌ ERROR: Distribution archive '$ZIP_FILE' not found or empty!${NC}" >&2
    echo -e "${YELLOW}Please run ./build.sh first.${NC}" >&2
    exit 1
fi

ZIP_SIZE=$(du -h "$ZIP_FILE" | cut -f1)
echo -e "Artifact to deploy: ${BOLD}$ZIP_FILE${NC} (${ZIP_SIZE})"

# 2. Transfer the zip file to remote host
echo -e "${BLUE}==> Transferring artifact via SCP to ${REMOTE_USER_HOST}:${REMOTE_DEST_DIR}...${NC}"
if ! scp -o StrictHostKeyChecking=no "$ZIP_FILE" "${REMOTE_USER_HOST}:${REMOTE_DEST_DIR}/monmon-play-java--1.0-SNAPSHOT.zip"; then
    echo -e "${RED}${BOLD}❌ ERROR: SCP transfer failed! The zip file could not be uploaded to ${REMOTE_USER_HOST}.${NC}" >&2
    echo -e "${RED}${BOLD}❌ Deployment aborted. Remote server was NOT modified.${NC}" >&2
    exit 1
fi
echo -e "${GREEN}✅ SCP transfer complete.${NC}"

# 3. Execute remote run_prod.sh via SSH
if [ ! -f "$RUN_PROD_SCRIPT" ]; then
    echo -e "${RED}${BOLD}❌ ERROR: Local script '$RUN_PROD_SCRIPT' not found!${NC}" >&2
    exit 1
fi

echo -e "${BLUE}==> Executing remote deployment script on ${REMOTE_USER_HOST}...${NC}"
if ! ssh -o StrictHostKeyChecking=no "${REMOTE_USER_HOST}" 'bash -s' < "$RUN_PROD_SCRIPT"; then
    echo -e "${RED}${BOLD}❌ ERROR: Remote execution of $RUN_PROD_SCRIPT failed on ${REMOTE_USER_HOST}!${NC}" >&2
    exit 1
fi

echo -e "${GREEN}${BOLD}🎉 Deployment completed successfully!${NC}"

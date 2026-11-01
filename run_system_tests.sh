#!/usr/bin/env bash
set -euo pipefail

# ANSI color codes
RED='\033[0;31m'
GREEN='\033[0;32m'
BLUE='\033[0;34m'
BOLD='\033[1m'
NC='\033[0m'

echo -e "${BLUE}${BOLD}========================================================================${NC}"
echo -e "${BLUE}${BOLD}   MonMon Realistic System Tests (Standard Java JUnit + PostgreSQL)     ${NC}"
echo -e "${BLUE}${BOLD}========================================================================${NC}"

# 1. Verify PostgreSQL container is running
echo -e "${BLUE}==> Checking PostgreSQL container...${NC}"
if ! docker ps -f name=^postgres$ --format '{{.Names}}' | grep -x postgres >/dev/null 2>&1; then
    echo -e "${RED}❌ ERROR: Docker container 'postgres' is not running on port 5432!${NC}" >&2
    exit 1
fi
echo -e "${GREEN}✅ PostgreSQL is running.${NC}"

# 2. Execute standard Java system tests via sbt
echo -e "${BLUE}==> Running system.RealDatabaseSystemTest via sbt...${NC}"
sbt "testOnly system.RealDatabaseSystemTest"

echo -e "${GREEN}${BOLD}========================================================================${NC}"
echo -e "${GREEN}${BOLD}🎉 SUCCESS: All Real PostgreSQL System Tests PASSED!                    ${NC}"
echo -e "${GREEN}${BOLD}========================================================================${NC}"

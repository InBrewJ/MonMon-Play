#!/usr/bin/env bash
set -euo pipefail

# ANSI color codes
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
BOLD='\033[1m'
NC='\033[0m' # No Color

echo -e "${BLUE}${BOLD}========================================================================${NC}"
echo -e "${BLUE}${BOLD}   MonMon & LegMon Realistic System Test (Real PostgreSQL + Docker)     ${NC}"
echo -e "${BLUE}${BOLD}========================================================================${NC}"

SYSTEST_DB="monmon_systest"
MONMON_PORT=9002
LEGMON_PORT=9006
MONMON_CONTAINER="monmon_systest_runner"
LEGMON_CONTAINER="legmon_systest_runner"

cleanup() {
    echo -e "${YELLOW}==> Cleaning up test containers and resources...${NC}"
    docker stop "$MONMON_CONTAINER" "$LEGMON_CONTAINER" >/dev/null 2>&1 || true
    docker rm "$MONMON_CONTAINER" "$LEGMON_CONTAINER" >/dev/null 2>&1 || true
    docker exec -e PGPASSWORD=docker postgres psql -U postgres -c "DROP DATABASE IF EXISTS $SYSTEST_DB;" >/dev/null 2>&1 || true
}
trap cleanup EXIT

# 1. Verify PostgreSQL container is running
echo -e "${BLUE}==> [1/7] Checking PostgreSQL container...${NC}"
if ! docker ps -f name=^postgres$ --format '{{.Names}}' | grep -x postgres >/dev/null 2>&1; then
    echo -e "${RED}❌ ERROR: Docker container 'postgres' is not running on port 5432!${NC}" >&2
    exit 1
fi
echo -e "${GREEN}✅ PostgreSQL is running.${NC}"

# 2. Reset test database
echo -e "${BLUE}==> [2/7] Initializing fresh PostgreSQL test database '${SYSTEST_DB}'...${NC}"
docker exec -e PGPASSWORD=docker postgres psql -U postgres -c "DROP DATABASE IF EXISTS $SYSTEST_DB;" >/dev/null
docker exec -e PGPASSWORD=docker postgres psql -U postgres -c "CREATE DATABASE $SYSTEST_DB;" >/dev/null

# 3. RECREATE THE EXACT BUG STATE:
# Table exists with raw_data column typed as JSONB (as it was in commit 88e9676)
echo -e "${BLUE}==> [3/7] Re-creating legacy PostgreSQL schema bug: raw_data as JSONB...${NC}"
docker exec -e PGPASSWORD=docker postgres psql -U postgres -d "$SYSTEST_DB" -c "
CREATE TABLE bank_transaction (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL,
    account_id BIGINT,
    external_id VARCHAR(255) UNIQUE,
    timestamp BIGINT,
    date VARCHAR(64),
    amount REAL DEFAULT 0,
    description VARCHAR(500),
    merchant_name VARCHAR(255),
    category VARCHAR(255),
    merchant_address VARCHAR(500),
    latitude DOUBLE PRECISION,
    longitude DOUBLE PRECISION,
    merchant_emoji VARCHAR(50),
    source VARCHAR(64) DEFAULT 'MANUAL',
    pending BOOLEAN DEFAULT FALSE,
    created_at BIGINT,
    raw_data JSONB
);

CREATE TABLE account (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL,
    name VARCHAR(255) NOT NULL,
    type VARCHAR(50) NOT NULL,
    ledger_mode VARCHAR(50) DEFAULT 'ABL',
    available_limit REAL DEFAULT 0,
    archived BOOLEAN DEFAULT FALSE
);

INSERT INTO account (id, user_id, name, type, ledger_mode, available_limit, archived)
VALUES (101, 'systest_user', 'NatWest Reward Platinum', 'DEBIT', 'ABL', 0, false);
" >/dev/null

# Assert that raw_data is currently jsonb in postgres
PRE_TYPE=$(docker exec -e PGPASSWORD=docker postgres psql -U postgres -d "$SYSTEST_DB" -t -A -c "
SELECT data_type FROM information_schema.columns WHERE table_name = 'bank_transaction' AND column_name = 'raw_data';
")
echo -e "Verified initial table column type: ${YELLOW}raw_data is $PRE_TYPE${NC}"
if [ "$PRE_TYPE" != "jsonb" ]; then
    echo -e "${RED}❌ Setup error: expected jsonb but got $PRE_TYPE${NC}" >&2
    exit 1
fi

# 4. Start MonMon in Docker connected to real PostgreSQL
echo -e "${BLUE}==> [4/7] Launching real MonMon container on port ${MONMON_PORT} against PostgreSQL...${NC}"
docker run -d \
    --name "$MONMON_CONTAINER" \
    --network host \
    -e DB_URL="jdbc:postgresql://127.0.0.1:5432/$SYSTEST_DB" \
    -e DB_USERNAME="postgres" \
    -e DB_PASSWORD="docker" \
    monmon:latest \
    -Dhttp.port="$MONMON_PORT" >/dev/null

echo "Waiting for MonMon to become healthy on http://127.0.0.1:${MONMON_PORT}/health..."
MAX_WAIT=35
WAIT_COUNT=1
MONMON_HEALTHY=false
while [ "$WAIT_COUNT" -le "$MAX_WAIT" ]; do
    if ! docker ps -f name="^${MONMON_CONTAINER}$" --format '{{.Names}}' | grep -x "$MONMON_CONTAINER" >/dev/null 2>&1; then
        echo -e "${RED}❌ MonMon container stopped unexpectedly! Logs:${NC}" >&2
        docker logs --tail 40 "$MONMON_CONTAINER" || true
        exit 1
    fi
    CODE=$(curl -s -o /dev/null -w "%{http_code}" --max-time 2 "http://127.0.0.1:${MONMON_PORT}/health" 2>/dev/null || true)
    if [ "$CODE" = "200" ]; then
        MONMON_HEALTHY=true
        break
    fi
    sleep 2
    WAIT_COUNT=$((WAIT_COUNT + 1))
done

if [ "$MONMON_HEALTHY" != true ]; then
    echo -e "${RED}❌ MonMon failed to start within $((MAX_WAIT * 2))s! Logs:${NC}" >&2
    docker logs --tail 50 "$MONMON_CONTAINER" || true
    exit 1
fi
echo -e "${GREEN}✅ MonMon is healthy on port ${MONMON_PORT}.${NC}"

# Verify startup migration converted raw_data to text in PostgreSQL
POST_TYPE=$(docker exec -e PGPASSWORD=docker postgres psql -U postgres -d "$SYSTEST_DB" -t -A -c "
SELECT data_type FROM information_schema.columns WHERE table_name = 'bank_transaction' AND column_name = 'raw_data';
")
echo -e "Post-migration column type in PostgreSQL: ${GREEN}raw_data is $POST_TYPE${NC}"
if [ "$POST_TYPE" != "text" ]; then
    echo -e "${RED}❌ FAILURE: BankTransactionMigration failed to alter raw_data column to text (still $POST_TYPE)!${NC}" >&2
    exit 1
fi

# 5. Start LegMon in Docker
echo -e "${BLUE}==> [5/7] Launching real LegMon container on port ${LEGMON_PORT}...${NC}"
docker run -d \
    --name "$LEGMON_CONTAINER" \
    --network host \
    -e PORT="$LEGMON_PORT" \
    -e MONMON_URL="http://127.0.0.1:${MONMON_PORT}" \
    legmon:latest >/dev/null

echo "Waiting for LegMon to become healthy on http://127.0.0.1:${LEGMON_PORT}/health..."
WAIT_COUNT=1
LEGMON_HEALTHY=false
while [ "$WAIT_COUNT" -le 20 ]; do
    CODE=$(curl -s -o /dev/null -w "%{http_code}" --max-time 2 "http://127.0.0.1:${LEGMON_PORT}/health" 2>/dev/null || true)
    if [ "$CODE" = "200" ]; then
        LEGMON_HEALTHY=true
        break
    fi
    sleep 2
    WAIT_COUNT=$((WAIT_COUNT + 1))
done

if [ "$LEGMON_HEALTHY" != true ]; then
    echo -e "${RED}❌ LegMon failed to start! Logs:${NC}" >&2
    docker logs --tail 40 "$LEGMON_CONTAINER" || true
    exit 1
fi
echo -e "${GREEN}✅ LegMon is healthy on port ${LEGMON_PORT}.${NC}"

# 6. Ingest Statement via MonMon Webhook
echo -e "${BLUE}==> [6/7] Ingesting extracted statement via MonMon webhook...${NC}"
WEBHOOK_RESP=$(curl -s -X POST "http://127.0.0.1:${MONMON_PORT}/api/monzo/webhook?statement=true&userId=systest_user" \
    -H "Content-Type: application/json" \
    -d '{
      "type": "statement.extracted",
      "userId": "systest_user",
      "jobId": "systest_job_01",
      "bank": "NatWest",
      "startDate": "2026-08-01",
      "endDate": "2026-08-31",
      "openingBalance": 250.00,
      "closingBalance": 875.50,
      "targetAccountId": 101,
      "transactions": [
        {
          "type": "transaction.created",
          "data": {
            "id": "systest_tx_01",
            "amount": -100000,
            "created": "2026-08-15T09:00:00Z",
            "currency": "GBP",
            "description": "Techworks Salary",
            "category": "income",
            "merchant": {"name": "Employer Inc"}
          }
        },
        {
          "type": "transaction.created",
          "data": {
            "id": "systest_tx_02",
            "amount": 35000,
            "created": "2026-08-20T14:30:00Z",
            "currency": "GBP",
            "description": "Council Tax Edinburgh",
            "category": "bills",
            "merchant": {"name": "Edinburgh Council"}
          }
        },
        {
          "type": "transaction.created",
          "data": {
            "id": "systest_tx_03",
            "amount": 2450,
            "created": "2026-08-25T11:15:00Z",
            "currency": "GBP",
            "description": "Sainsbury Supermarkets",
            "category": "groceries",
            "merchant": {"name": "Sainsbury Stores"}
          }
        }
      ]
    }')

echo "Webhook response: $WEBHOOK_RESP"
STMT_ID=$(echo "$WEBHOOK_RESP" | grep -o '"statementId":[0-9]*' | cut -d':' -f2 || true)
if [ -z "$STMT_ID" ]; then
    echo -e "${RED}❌ Failed to extract statementId from webhook response!${NC}" >&2
    exit 1
fi
echo -e "${GREEN}✅ PendingStatement created in PostgreSQL with ID $STMT_ID.${NC}"

# 7. Confirm Statement (The exact step that previously threw PSQLException 42804!)
echo -e "${BLUE}==> [7/7] Confirming statement and inserting into PostgreSQL (testing raw_data insertion)...${NC}"
CONFIRM_RESP=$(curl -s -X POST "http://127.0.0.1:${MONMON_PORT}/api/bank/statement/${STMT_ID}/confirm?userId=systest_user&accountId=101")
echo "Confirmation response: $CONFIRM_RESP"

if ! echo "$CONFIRM_RESP" | grep -q '"status":"ok"'; then
    echo -e "${RED}❌ Statement confirmation failed! Response: $CONFIRM_RESP${NC}" >&2
    docker logs --tail 40 "$MONMON_CONTAINER" || true
    exit 1
fi

# Verify rows and data in real PostgreSQL
TX_COUNT=$(docker exec -e PGPASSWORD=docker postgres psql -U postgres -d "$SYSTEST_DB" -t -A -c "
SELECT count(*) FROM bank_transaction WHERE user_id = 'systest_user';
")
echo -e "Bank transactions inserted in PostgreSQL: ${BOLD}$TX_COUNT${NC} (expected 3)"
if [ "$TX_COUNT" != "3" ]; then
    echo -e "${RED}❌ Expected 3 bank transactions in PostgreSQL, found $TX_COUNT${NC}" >&2
    exit 1
fi

RAW_CHECK=$(docker exec -e PGPASSWORD=docker postgres psql -U postgres -d "$SYSTEST_DB" -t -A -c "
SELECT raw_data FROM bank_transaction WHERE external_id = 'systest_tx_01';
")
if ! echo "$RAW_CHECK" | grep -q "Techworks Salary"; then
    echo -e "${RED}❌ raw_data not populated properly in PostgreSQL! Value: $RAW_CHECK${NC}" >&2
    exit 1
fi

BAL_CHECK=$(docker exec -e PGPASSWORD=docker postgres psql -U postgres -d "$SYSTEST_DB" -t -A -c "
SELECT value FROM balance WHERE user_id = 'systest_user' ORDER BY id DESC LIMIT 1;
")
echo -e "Closing balance written to PostgreSQL balance table: ${BOLD}£$BAL_CHECK${NC} (expected 875.5)"
if [ "$BAL_CHECK" != "875.5" ]; then
    echo -e "${RED}❌ Expected closing balance 875.5, found $BAL_CHECK${NC}" >&2
    exit 1
fi

echo -e "${GREEN}${BOLD}========================================================================${NC}"
echo -e "${GREEN}${BOLD}🎉 SUCCESS: Realistic System Test PASSED!                               ${NC}"
echo -e "${GREEN}${BOLD}   • Real PostgreSQL database                                           ${NC}"
echo -e "${GREEN}${BOLD}   • Initialized with JSONB bug schema                                  ${NC}"
echo -e "${GREEN}${BOLD}   • Real MonMon container startup & migration                          ${NC}"
echo -e "${GREEN}${BOLD}   • Real LegMon container                                              ${NC}"
echo -e "${GREEN}${BOLD}   • End-to-end statement webhook ingestion                             ${NC}"
echo -e "${GREEN}${BOLD}   • Statement confirmation & raw_data insertion into PostgreSQL        ${NC}"
echo -e "${GREEN}${BOLD}========================================================================${NC}"

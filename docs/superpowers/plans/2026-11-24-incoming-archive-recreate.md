# Archive-and-Recreate Incoming Edits Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Convert incoming edits (`db::update_incoming`) from destructive in-place `UPDATE`s into atomic archive-and-recreate transactions, fix unarchived payday lookups in `management.rs`, and verify end-to-end against real PostgreSQL + Dockerized `monmon-rs` with system tests.

**Architecture:** `db::update_incoming` executes inside a `sqlx` transaction: it archives the owned active row (`archived = TRUE, archived_at = now_ms`) returning its `type` and `grossvalue`, then inserts a new active row (`created_at = now_ms, archived = FALSE`) preserving `type` and falling back to old `grossvalue` if omitted. Two payday queries in `management.rs` gain `AND (archived IS NULL OR archived = false)`. Real-PostgreSQL system tests in `modules/tests-contract/incoming-archive-recreate.contract.test.mjs` exercise both API and HTML form edits, access-control boundaries, Waste Bin visibility, Time Travel (`monmon_time_travel`), and `/historicSpent` historical continuity.

**Tech Stack:** Rust (`sqlx`, `axum`, `chrono`), PostgreSQL (`postgres-monmon`), Node built-in test runner (`node --test`), Docker (`monmon-rs` local container).

**Spec:** `docs/superpowers/specs/2026-11-24-incoming-archive-recreate-design.md`

## Global Constraints

- Atomic PostgreSQL transaction in `db::update_incoming` (`pool.begin()`): fail with `sqlx::Error::RowNotFound` without inserting if row is missing, already archived, or owned by another user.
- Preserve `type` from archived row and fall back to archived `grossvalue` when `UpdateIncoming.gross_value` is `None`; keep `history_json` on the archived row only.
- No schema migrations required; superseded rows remain visible in `/wasteBin` for 30 days (matching replaced Monthly Pots).
- System tests must run against real Dockerized PostgreSQL + real `monmon-rs` container (per `AGENTS.md`).
- Commit messages prefixed with `[agy]`. Never run deploy scripts (`ship/*.sh`).

## Review Focus

- **Stale/replayed edit on an already-archived incoming ID:** must return `403` (`RowNotFound`) and must NOT insert a duplicate active row (pinned in Task 2).
- **Cross-user edit attempt (`PUT /internal/incomings/:id` or `POST /updateIncoming/:id`):** must return `403`, must NOT archive the victim's incoming, and must NOT insert a new row (pinned in Task 2).
- **Editing an incoming's payday day-of-month (e.g. Day 25 → Day 15):** `management.rs` payday queries (`get_outgoings_page`, `get_edit_outgoing_page`) plus SPOG/Transfers must use the active Day 15, never the archived Day 25 (pinned in Task 1 & Task 2).
- **Preserving `type` and `grossvalue` when `UpdateIncoming.gross_value` is `None`, without duplicating `history_json`:** verified via unit helper test (Task 1) and DB/API system test (Task 2).
- **Historical continuity across Time Travel (`monmon_time_travel`) and `/historicSpent` with contiguous `old.archived_at == new.created_at`:** pre-edit dates/cycles show old salary, live views show new salary, and neither double-counts (pinned in Task 1 & Task 2).

---

### Task 1: Atomic Archive-and-Recreate in `db::update_incoming` & Payday Archival Filter in `management.rs`

**Files:**
- Modify: `modules/monmon-rs/src/db.rs:442-468`
- Modify: `modules/monmon-rs/src/management.rs:588-596`, `modules/monmon-rs/src/management.rs:775-783`
- Test: `modules/monmon-rs/src/historic_spent.rs` (`#[cfg(test)] mod tests`) and `modules/monmon-rs/src/db.rs` (`#[cfg(test)] mod tests`)

**Interfaces:**
- Produces:
  - `db::update_incoming(pool: &PgPool, id: i64, user_id: &str, inc: &UpdateIncoming) -> Result<Incoming, sqlx::Error>` (same signature; atomically archives old active row and returns newly inserted active row with new `id`)
  - `db::resolve_recreated_gross_value(requested: Option<f32>, previous: Option<f32>) -> Option<f32>`
  - `management.rs` payday queries at lines 590 & 777 filter `AND (archived IS NULL OR archived = false) ORDER BY id ASC LIMIT 1`

- [ ] **Step 1: Write failing unit tests in `modules/monmon-rs/src/db.rs` and `modules/monmon-rs/src/historic_spent.rs`**

Add:
- In `modules/monmon-rs/src/db.rs` (`#[cfg(test)] mod tests`):
  - `test_resolve_recreated_gross_value_preserves_previous_when_omitted`: asserts `resolve_recreated_gross_value(None, Some(4500.0)) == Some(4500.0)`, `resolve_recreated_gross_value(Some(5000.0), Some(4500.0)) == Some(5000.0)`, and `resolve_recreated_gross_value(None, None) == None`.
- In `modules/monmon-rs/src/historic_spent.rs` (`#[cfg(test)] mod tests`):
  - `test_archive_recreate_incoming_chain_separates_cycles_without_double_count`: constructs an archived incoming (`id: 10`, `net_value: 3000.0`, `created_at: 1 Sep 2026`, `archived: true`, `archived_at: 15 Nov 2026`) and its recreated replacement (`id: 11`, `net_value: 3500.0`, `created_at: 15 Nov 2026`, `archived: false`, `archived_at: None`), verifies `get_effective_start_sec` returns `created_at` for both, and verifies the Oct cycle (`pay_sec = 30 Sep`) selects ONLY `#10` (`£3,000`) while a post-edit cycle (`pay_sec = 30 Nov`) selects ONLY `#11` (`£3,500`) — and an on-payday edit (`archived_at == created_at == pay_sec + 3600`) selects ONLY `#11` with zero double-counting.

- [ ] **Step 2: Run new unit tests to verify RED**

Run: `cargo test --manifest-path modules/monmon-rs/Cargo.toml test_resolve_recreated_gross_value`
Expected: FAIL to compile (`resolve_recreated_gross_value` not defined).

- [ ] **Step 3: Implement atomic archive-and-recreate in `modules/monmon-rs/src/db.rs` and fix payday queries in `modules/monmon-rs/src/management.rs`**

- In `modules/monmon-rs/src/db.rs`:
  - Add `pub fn resolve_recreated_gross_value(requested: Option<f32>, previous: Option<f32>) -> Option<f32> { requested.or(previous) }`.
  - Rewrite `update_incoming` to open `let mut tx = pool.begin().await?;`, archive the active owned row (`WHERE id = $2 AND COALESCE(user_id, userid, "userId") = $3 AND (archived IS FALSE OR archived IS NULL) RETURNING type, grossvalue`), return `Err(sqlx::Error::RowNotFound)` if `None`, insert the new active row with `created_at = now_epoch, updated_at = now_epoch, archived = FALSE`, preserving `type` and `resolve_recreated_gross_value(inc.gross_value, old_gross)` (and leaving `history_json` `NULL` on the new row), then `tx.commit().await?;` and return the new `Incoming`.
- In `modules/monmon-rs/src/management.rs`:
  - Update lines 590 and 777 to: `"SELECT incomingmonthday FROM incoming WHERE (userid = $1 OR user_id = $1) AND (archived IS NULL OR archived = false) AND payday = true ORDER BY id ASC LIMIT 1"`.

- [ ] **Step 4: Run full Rust unit test suite to verify GREEN**

Run: `cargo test --manifest-path modules/monmon-rs/Cargo.toml`
Expected: PASS (0 failures, 0 warnings).

- [ ] **Step 5: Commit Task 1**

```bash
git add docs/superpowers/specs/2026-11-24-incoming-archive-recreate-design.md docs/superpowers/plans/2026-11-24-incoming-archive-recreate.md modules/monmon-rs/src/db.rs modules/monmon-rs/src/management.rs modules/monmon-rs/src/historic_spent.rs
git commit -m "[agy] Archive-and-recreate incoming rows on edit and filter archived paydays"
```

---

### Task 2: Real-PostgreSQL System Tests for Archive-and-Recreate Incomings

**Files:**
- Create: `modules/tests-contract/incoming-archive-recreate.contract.test.mjs`

**Interfaces:**
- Consumes from Task 1:
  - `POST /internal/incomings`, `GET /internal/incomings`, `PUT /internal/incomings/:id`
  - `POST /updateIncoming/:id`, `GET /myIncomings`, `GET /api/spog/metrics`, `GET /api/transfers`, `GET /api/waste-bin`, `GET /historicSpent`
  - Local Docker `monmon-rs` rebuilt locally (`docker compose -f docker-compose.rust.yaml build monmon` or local container rebuild — NEVER running `ship/*.sh` deploy scripts!) backed by real `postgres-monmon`.

- [ ] **Step 1: Write the system tests in `modules/tests-contract/incoming-archive-recreate.contract.test.mjs`**

Add two comprehensive end-to-end system tests against real PostgreSQL + real `monmon-rs`:
1. **`archives old incoming and creates a new active row on API & form edit without double-counting, with strict ownership & stale-ID guards`**:
   - Creates an isolated test user (`inc_hist_${Date.now()}`) and a DEBIT account.
   - Creates a primary payday incoming (`Old Salary`, `£3000.00`, `grossValue: 4200.0`, `incomingMonthDay: 25`, `payday: true`).
   - Edits via `PUT /internal/incomings/:id` (`Raised Salary`, `£3500.00`, `incomingMonthDay: 28`, omitting `grossValue`).
   - Asserts `updated.id !== created.id`, `updated.netValue === 3500`, `updated.grossValue === 4200`, `updated.incomingMonthDay === 28`.
   - Asserts `GET /internal/incomings?userId=...` returns **exactly 1** active row (`updated.id`).
   - Asserts `/api/spog/metrics` shows `monthlyIncome === 3500` (or `nextPayDay === 28`, no double-count to `£6500`!) and `/api/waste-bin` contains the archived original row (`entity_type === 'incoming'`, `id === created.id`).
   - Attempts to edit the **now-archived** `created.id` via `PUT /internal/incomings/${created.id}` → asserts `403`, and active incomings count stays `1`.
   - Attempts cross-user edit (`userId: 'other_attacker_user'`) against `updated.id` → asserts `403`, and original user's active incoming remains intact.
   - Edits `updated.id` via HTML form `POST /updateIncoming/${updated.id}` (`Form Salary`, `£3750.00`, `incomingMonthDay: 15`, `payday: true`) → asserts redirect `303`, active list has 1 row with a third distinct ID (`£3750.00`, Day 15), and `/api/spog/metrics` reflects `£3750` and `nextPayDay === 15`.
2. **`preserves historical income across Time Travel (monmon_time_travel) and /historicSpent cycles after salary raise`**:
   - Creates an isolated user, a DEBIT account with balances spanning 2 pay cycles (e.g. 45 days ago and today), and an incoming (`Initial Job`, `£2500.00`, Day 1, `payday: true`).
   - Backdates the incoming's `created_at` in real PostgreSQL (`postgres-monmon`) to 65 days ago so it covers the previous completed cycle.
   - Edits the incoming via `PUT /internal/incomings/:id` to `Promoted Job`, `£3200.00`, Day 1, `payday: true` (creating the archived/recreated chain at `now`).
   - Verifies live `/myIncomings` shows `Promoted Job` / `£3200` and does NOT show `Initial Job` / `£2500`.
   - Verifies Time Travel `/myIncomings` with cookie `monmon_time_travel=<35 days ago YYYY-MM-DD>` shows `Initial Job` / `£2500` and does NOT show `Promoted Job` / `£3200`.
   - Verifies `/historicSpent` renders `£2500.00` (previous completed cycle income) AND `£3200.00` (current cycle income) without double-counting `£5700.00`.

- [ ] **Step 2: Run the system test against the CURRENT (pre-rebuild) local `monmon-rs` container to verify RED**

Run: `PATH="$HOME/.nvm/versions/node/v26.13.0/bin:$PATH" MONMON_URL=http://127.0.0.1:9001 node --test modules/tests-contract/incoming-archive-recreate.contract.test.mjs`
Expected: FAIL (`updated.id !== created.id` fails because the running container still does in-place UPDATE).

- [ ] **Step 3: Rebuild & restart the local `monmon-rs` test container (locally only — never deploy scripts) and re-run the system tests**

Check how local `monmon-rs` on `:9001` is built/started, rebuild it locally with our changes, wait for `/health` = 200, and run:
`PATH="$HOME/.nvm/versions/node/v26.13.0/bin:$PATH" MONMON_URL=http://127.0.0.1:9001 node --test modules/tests-contract/incoming-archive-recreate.contract.test.mjs modules/tests-contract/monmon-core-rs.contract.test.mjs modules/tests-contract/balance-soft-delete.contract.test.mjs`
Expected: ALL system tests PASS against real PostgreSQL + real `monmon-rs`!

- [ ] **Step 4: Commit Task 2**

```bash
git add modules/tests-contract/incoming-archive-recreate.contract.test.mjs
git commit -m "[agy] Add real-PostgreSQL system tests for archive-and-recreate incoming edits"
```

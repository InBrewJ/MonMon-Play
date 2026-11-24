# Archive-and-Recreate Incoming Edits

**Date:** 2026-11-24
**Status:** Approved
**Size:** Small change
**Target files:**
- `modules/monmon-rs/src/db.rs`
- `modules/monmon-rs/src/management.rs`
- `modules/tests-contract/incoming-archive-recreate.contract.test.mjs`

---

## Intent & Why

Currently, editing an incoming (`db::update_incoming`) performs a destructive in-place `UPDATE incoming SET ...`, overwriting the previous salary/income amount and payday without preserving history. To accurately track income over long periods of time (for Time Travel `as_of` and `/historicSpent` multi-cycle income history), editing an incoming must archive the previous active row and insert a new active row in one atomic transaction — matching how Monthly Pots work.

## Goals, Non-Goals & Anti-Goals

- **Goals:**
  - `db::update_incoming` atomically archives the active incoming row (`archived = TRUE, archived_at = now_ms, updated_at = now_ms`) and inserts a new active incoming row (`created_at = now_ms, updated_at = now_ms, archived = FALSE`) in a single PostgreSQL transaction.
  - Preserve `type` from the archived row and fall back to the archived row's `grossvalue` when `UpdateIncoming.gross_value` is `None`.
  - Keep existing `history_json` intact on the archived row (do not duplicate it onto the new row).
  - Fix payday lookups in `management.rs:590` and `management.rs:777` to exclude archived incoming rows (`AND (archived IS NULL OR archived = false) ORDER BY id ASC LIMIT 1`).
  - Add real-infrastructure system tests against Dockerized PostgreSQL + `monmon-rs` verifying archive-and-recreate lifecycle, access control, Waste Bin visibility, payday updates, Time Travel (`monmon_time_travel`), and `/historicSpent` historical income continuity.
- **Non-Goals:**
  - Do not change outgoing edits (`update_outgoing`) or account edits.
  - Do not add new schema columns or change `/wasteBin` filtering for superseded incomings (they appear in `/wasteBin` for 30 days, consistent with replaced Monthly Pots).
- **Anti-Goals:**
  - Allowing a stale or replayed edit against an already-archived incoming ID to spawn a duplicate active incoming.
  - Double-counting old + new incoming rows in live SPOG, Transfers, Savings Plan, `/myIncomings`, Time Travel, or `/historicSpent`.
  - Allowing a cross-user edit attempt to archive or clone another user's incoming.

## Constraints

- Atomic PostgreSQL transaction (`sqlx::PgPool::begin()`): if archiving fails or finds 0 active owned rows, return `sqlx::Error::RowNotFound` without inserting anything.
- System tests must run against real Dockerized PostgreSQL + real `monmon-rs` container (per `AGENTS.md`).
- Commit messages prefixed with `[agy]`. Never run deploy scripts.

## Approaches Considered

1. **Atomic archive-and-recreate in `db::update_incoming` — Selected**
   - Archives the active row with `archived_at = now_ms` and inserts a new active row with `created_at = now_ms` inside one transaction.
   - *Why picked:* Contiguous `created_at` → `archived_at` row chain matches `list_incomings_as_of` and `historic_spent.rs` natively, aligns with Monthly Pots' immutable history model, and requires zero schema changes.
2. **In-place `UPDATE` appending to `history_json` — Rejected**
   - Keeps only one mutable row, splits the history mechanism from Monthly Pots, and complicates Waste Bin recovery and clean row-level auditing.

## Design Summary

- **`db::update_incoming(pool, id, user_id, inc)` (`modules/monmon-rs/src/db.rs`):**
  - Start transaction (`let mut tx = pool.begin().await?;`), compute `now_epoch = Utc::now().timestamp_millis()`.
  - Execute `UPDATE incoming SET archived = TRUE, archived_at = $1, updated_at = $1 WHERE id = $2 AND COALESCE(user_id, userid, "userId") = $3 AND (archived IS FALSE OR archived IS NULL) RETURNING type, grossvalue`.
  - If `fetch_optional` returns `None`, roll back and return `Err(sqlx::Error::RowNotFound)`.
  - Insert new active row (`nextval('hibernate_sequence')`, `archived = FALSE`, `created_at = $now_epoch`, `updated_at = $now_epoch`, `type = old_type`, `grossvalue = inc.gross_value.or(old_gross)`), returning the new `Incoming` with `account_name` joined (or `NULL` fallback matching existing `RETURNING` shape).
  - Commit transaction and return the new `Incoming`.
- **Payday lookup fix (`modules/monmon-rs/src/management.rs`):**
  - Update lines 590 and 777 to add `AND (archived IS NULL OR archived = false) ORDER BY id ASC LIMIT 1`.
- **System tests (`modules/tests-contract/incoming-archive-recreate.contract.test.mjs`):**
  - Test 1: Full edit lifecycle via `PUT /internal/incomings/:id` and `POST /updateIncoming/:id` (archive old + create new ID, no double-count across SPOG/Transfers/Savings Plan/`/myIncomings`, payday shift reflected everywhere, old row in `/api/waste-bin`, stale archived-ID edit rejected with `403`, cross-user edit rejected with `403`).
  - Test 2: Long-term historical income continuity (backdated original salary edited to new salary; Time Travel `monmon_time_travel` before edit and prior `/historicSpent` cycle reflect original salary, while live SPOG/`/myIncomings`/current cycle reflect new salary with zero double-counting).

## Left to the Builder
- Exact unit tests in `db.rs` / `historic_spent.rs` for contiguous `created_at == archived_at` chain selection.
- Local rebuild/restart of the `monmon-rs` Docker container (never prod deploy scripts) so the real-PostgreSQL contract tests exercise the newly compiled binary.

# `/historicSpent` Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Redesign `/historicSpent` into two cleanly separated, hash-addressed views (`#pots` for SPOG-parity Monthly Pots history and `#all-accounts` for post-payday Start → pre-next-payday End total spend across all active non-mortgage/non-pension accounts including loans).

**Architecture:** Refactor `modules/monmon-rs/src/historic_spent.rs` around pure, unit-tested calculation helpers for (1) multi-pot outflow windows & `Pence`-precision SPOG daily pace parity, (2) signed All-Accounts balance aggregation with `LOAN` liability negation and a payday-morning snapshot guard, and (3) independent KPI summaries per view. Render both views inside a `spog-deck`-compatible two-card deck in `historic_spent.html` paired with a small plain-JS switcher (`public/javascripts/historic-spent.js`) and responsive Stacked Action Card mobile tables in `main.css`.

**Tech Stack:** Rust (`axum`, `sqlx`, `askama`, `chrono`, `crate::money::Pence`), PostgreSQL, plain JavaScript (`.js`), CSS, `cargo test`, Node `node:test`.

**Spec:** `docs/superpowers/specs/2026-11-21-historic-spent-redesign-design.md`

## Global Constraints

- Plain JavaScript (`.js`) only — no CoffeeScript.
- Sync all static edits from `public/` to `modules/monmon-rs/public/` (`cp -ru public/* modules/monmon-rs/public/`).
- Preserve all `HistoricCycleDto` fields consumed by `spog::get_spending_pace_page` (`cycle_start_date`, `cycle_end_date`, `is_current_cycle`, `days_in_cycle`, `days_elapsed`, `pot_allowance`, `cycle_date_range`, `pot_account_ids`).
- Always format currency amounts to 2 decimal places (`£X.XX` via `Pence`).
- Follow MonMon UX primitives: `spog-deck` swipe deck with dot headings on mobile (`<= 768px`), Stacked Action Card layout for mobile tables, "At a Glance" upfront hierarchy.
- Prefix all git commit messages with `[agy]`.

## Review Focus

1. **Boundary snapshot double-count on pot replacement timestamp `T`:** A balance snapshot recorded at exact timestamp `T` between Pot 1 and Pot 2 must be counted once (in Pot 2), never twice and never dropped — pinned by `test_multi_pot_window_sum_matches_spog_across_boundary_and_gap`.
2. **Current cycle with archived/deleted monthly pot or empty `pot_accounts`:** Must show `£0.00` matching SPOG rather than leaking `debit_account_ids` or `ledger_account_ids` — pinned by `test_current_cycle_without_active_pot_matches_spog_zero`.
3. **Payday morning before salary snapshot syncs:** Adding synthetic `payday_inflow` to `Start` without mirroring it in `End` would show an entire salary as spent — pinned by `test_payday_morning_guard_before_snapshot_shows_zero_spend`.
4. **`LOAN` balance sign vs `CREDIT` balance sign:** `LOAN` stores positive owed debt while `CREDIT` stores available credit; paying £200 from Debit to a Loan must net to `£0.00`, while £50 interest must net to `+£50.00` spent — pinned by `test_loan_liability_repayment_neutral_and_interest_counts_as_spend`.
5. **Clicking `/spendingPace` icon inside `<summary>`:** Must navigate without toggling `<details>`, and desktop/mobile pill clicks must stay in sync with URL hash (`#pots` / `#all-accounts`) — pinned by `modules/tests-contract/historic-spent.unit.test.mjs`.

---

### Task 1: Backend Monthly Pots Outflow Windows & SPOG Pace Parity

**Files:**
- Modify: `modules/monmon-rs/src/historic_spent.rs`

**Interfaces:**
- Consumes: `crate::money::Pence`, `crate::spog::compute_daily_overspend`, `ActivePotInterval`, `PotRecord`.
- Produces:
  - `pub fn calculate_account_spent_window(balances: &[(i64, f32)], start_sec: i64, end_sec: i64, inclusive_end: bool) -> f32`
  - `pub fn compute_cycle_pot_snapshots(intervals: &[ActivePotInterval], balance_map: &HashMap<i64, Vec<(i64, f32)>>, account_names_map: &HashMap<i64, (String, String)>, debit_account_ids: &[i64], start_sec: i64, end_sec: i64, effective_end_sec: i64, is_current: bool) -> (Pence, Vec<HistoricPotSnapshotDto>)`
  - `pub struct PotPaceMetrics { pub pot_daily_pace: Pence, pub pot_max_per_day: Pence, pub is_pot_overspent_daily: bool, pub absolute_pot_daily_overspend: Pence, pub is_over_pot_allowance: bool, pub absolute_pot_overspend: Pence, pub budget_utilization_percent: f32 }`
  - `pub fn compute_pot_pace_metrics(pot_spent: Pence, pot_allowance: Pence, days_elapsed: i32, days_in_cycle: i32, max_per_day_override: Option<Pence>) -> PotPaceMetrics`

- [ ] **Step 1: Write failing Rust unit tests in `modules/monmon-rs/src/historic_spent.rs`**

Add unit tests:
- `test_calculate_account_spent_window_half_open_prevents_boundary_double_count`: balances `[(100, 500.0), (200, 450.0), (300, 400.0)]`, window 1 `[100, 200)` = `0.0`, window 2 `[200, 300]` = `100.0`, sum = `100.0 == calculate_account_spent(&balances, 100, 300)`.
- `test_multi_pot_window_sum_matches_spog_across_boundary_and_gap`: Pot 1 archived at `T_arch = 2000`, Pot 2 created at `T_create = 3000` (with a balance drop at `2500` in the gap and another at `3000` on the boundary), same accounts -> total `pot_spent` equals `calculate_account_spent(bals, start_sec, end_sec)` to the penny; also test different accounts across Pot 1 and Pot 2 summing each pot's own window.
- `test_current_cycle_without_active_pot_matches_spog_zero`: current cycle with no pots, or only an unreplaced archived pot, or active pot with empty `pot_accounts` -> `pot_spent == Pence::ZERO`.
- `test_past_cycle_unreplaced_archived_pot_stops_at_archive_and_empty_falls_back_to_debit`: past cycle with unreplaced archived pot ignores drops after `interval.end`; past cycle with empty `pot_accounts` uses `debit_account_ids`.
- `test_pot_pace_metrics_matches_spog_screenshot_values`: `pot_spent = £504.87`, `pot_allowance = £366.72`, `days_elapsed = 22`, `days_in_cycle = 31`, `max_per_day_override = Some(£11.83)` -> `pot_daily_pace == £22.95`, `is_pot_overspent_daily == true`, `absolute_pot_daily_overspend == £11.12`.

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd modules/monmon-rs && cargo test historic_spent::tests::test_calculate_account_spent_window`
Expected: FAIL (functions not defined yet).

- [ ] **Step 3: Implement pot windowing, archived pot-account loading, and `PotPaceMetrics` in `modules/monmon-rs/src/historic_spent.rs`**

- Implement `calculate_account_spent_window` (`t_curr >= start_sec && (if inclusive_end { t_curr <= end_sec } else { t_curr < end_sec })`).
- Implement `compute_cycle_pot_snapshots`:
  - First pot `win_start = start_sec`.
  - Non-final pot `i < n - 1`: `win_end = intervals[i + 1].start.min(effective_end_sec)` with `inclusive_end = false` (bridging archive-to-create gaps).
  - Final pot `i == n - 1`: if `interval.end < effective_end_sec` (unreplaced archived pot), past cycle uses `win_end = interval.end` (`inclusive_end = true`), current cycle yields `Pence::ZERO` if no active unarchived pot exists; otherwise `win_end = effective_end_sec` (`inclusive_end = true`).
  - Account resolution: if `p.accounts` non-empty use `p.accounts`; else if `is_current` use `&[]` (matching SPOG); else use `debit_account_ids`.
  - When `intervals` is empty: if `is_current` return `Pence::ZERO`; else sum `calculate_account_spent_window(..., start_sec, effective_end_sec, true)` across `debit_account_ids`.
- In `calculate_historic_cycles`, also query `account` (without `archived = false` filter) for any `account_id` referenced in `pot_accounts_map` that is missing from `account_names_map` and load its balances into `balance_map`.
- Implement `compute_pot_pace_metrics` using `Pence::checked_div_days` and `crate::spog::compute_daily_overspend`.

- [ ] **Step 4: Run Rust tests to verify they pass**

Run: `cd modules/monmon-rs && cargo test historic_spent`
Expected: PASS (0 failures).

---

### Task 2: Backend All-Accounts Math (`LOAN` Liability, Payday-Morning Guard, Signed Net Spend) & Separate KPI Summaries

**Files:**
- Modify: `modules/monmon-rs/src/historic_spent.rs`

**Interfaces:**
- Consumes: `get_account_start_balance_with_inflow`, `get_account_end_balance`, `crate::money::Pence`, Task 1's `compute_cycle_pot_snapshots` and `compute_pot_pace_metrics`.
- Produces:
  - `pub fn compute_account_cycle_net(acc_type: &str, balances: &[(i64, f32)], start_sec: i64, end_sec: i64, is_current: bool, effective_end_sec: i64, payday_inflow: f32) -> (Pence, Pence, Pence, bool)` (returns `(signed_start, signed_end, net_spent, is_liability)`)
  - `pub struct HistoricPotsSummaryDto { pub avg_pot_spend: Pence, pub avg_daily_pace: Pence, pub cycles_within_max: usize, pub total_completed_cycles: usize, pub within_max_percent: i32 }`
  - `pub struct HistoricAllAccountsSummaryDto { pub avg_net_spend: Pence, pub avg_is_net_inflow: bool, pub avg_abs_net_change: Pence, pub highest_spent: Option<Pence>, pub highest_spent_range: Option<String>, pub lowest_spent: Option<Pence>, pub lowest_is_net_inflow: bool, pub lowest_abs_net_change: Option<Pence>, pub lowest_spent_range: Option<String> }`
  - `pub fn compute_historic_summaries(cycles: &[HistoricCycleDto]) -> (HistoricPotsSummaryDto, HistoricAllAccountsSummaryDto)`
  - Updated `HistoricCycleDto`, `AccountSpentDto`, `AccountBalanceSnapshotDto`, `HistoricPotSnapshotDto`, and `HistoricSpentTemplate` with `Pence` fields for exact 2-decimal display while retaining existing `spog::get_spending_pace_page` fields.

- [ ] **Step 1: Write failing Rust unit tests in `modules/monmon-rs/src/historic_spent.rs`**

Add unit tests:
- `test_loan_liability_repayment_neutral_and_interest_counts_as_spend`:
  - Debit `2000 -> 1800` (`net_spent = £200.00`) + Loan `5000 -> 4800` (`signed_start = -£5000.00`, `signed_end = -£4800.00`, `net_spent = -£200.00`, `is_liability = true`) -> combined `£0.00`.
  - Debit `2000 -> 1800` (`+£200.00`) + Loan `5000 -> 4850` (`-£150.00`, £50 interest) -> combined `+£50.00` spent.
- `test_all_accounts_house_purchase_and_unclamped_net_inflow`:
  - House month: Savings `50000 -> 10000` + Debit `1000 -> 1000` -> `Start £51000.00`, `End £11000.00`, `net_spent = £40000.00`.
  - Windfall month: `Start £1500.00`, `End £2100.00` -> `net_spent = -£600.00` (not clamped to `£0.00`).
- `test_payday_morning_guard_before_snapshot_shows_zero_spend`:
  - Current cycle, `payday_inflow = 1000.0`, only balance snapshot is before `start_sec` (`500.0`) -> `start = £1500.00`, `end = £1500.00`, `net_spent = £0.00`.
  - Once a snapshot arrives at `start_sec + 3600` (`1400.0`) -> `start = £1400.00`, `end = £1400.00`, `net_spent = £0.00`; after spend at `start_sec + 7200` (`1100.0`) -> `start = £1400.00`, `end = £1100.00`, `net_spent = £300.00`.
- `test_compute_historic_summaries_separates_pots_and_all_accounts_kpis`:
  - 1 current cycle + 2 completed cycles (one under MAX & net inflow, one over MAX & large house spend) -> verifies Pots KPI strip ignores current cycle and counts `1 / 2 (50%)` within MAX, while All-Accounts KPI strip reports accurate signed average, highest spend, and lowest/net-inflow cycle.

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd modules/monmon-rs && cargo test historic_spent::tests::test_loan_liability`
Expected: FAIL (functions/structs not defined yet).

- [ ] **Step 3: Implement All-Accounts math, `LOAN` inclusion, payday-morning guard, and separate KPI summaries in `modules/monmon-rs/src/historic_spent.rs`**

- Change active account filter from `t_upper != "LOAN" && t_upper != "MORTGAGE" && t_upper != "PENSION"` to `t_upper != "MORTGAGE" && t_upper != "PENSION"`.
- Implement `compute_account_cycle_net` with `LOAN` negation and payday-morning guard (`is_current && payday_inflow > 0.0 && !balances.iter().any(|&(t, _)| t >= start_sec)`).
- Wire `calculate_historic_cycles` to use `compute_cycle_pot_snapshots`, `compute_pot_pace_metrics`, and `compute_account_cycle_net` (removing the old `tbl_spent_by_account` override and `(raw_pot_spent - total_savings_inflow).max(0.0)` formula, and removing `.max(0.0)` clamp on `all_accounts_net_spent`).
- Implement `compute_historic_summaries` and wire `get_historic_spent_page` to pass `pots_summary` and `all_accounts_summary` to `HistoricSpentTemplate`.
- Keep all existing `HistoricCycleDto` fields used by `spog::get_spending_pace_page` populated accurately.

- [ ] **Step 4: Run full Rust test suite to verify Task 1 & Task 2 pass without breaking `/spog` or `/spendingPace`**

Run: `cd modules/monmon-rs && cargo test`
Expected: All Rust unit tests PASS.

- [ ] **Step 5: Commit backend changes**

```bash
git add docs/superpowers/specs/2026-11-21-historic-spent-redesign-design.md docs/superpowers/plans/2026-11-21-historic-spent-redesign.md modules/monmon-rs/src/historic_spent.rs
git commit -m "[agy] Refactor historic_spent backend for SPOG-parity pots and all-accounts view"
```

---

### Task 3: Frontend Template, Plain JS Switcher, CSS, SW Precache & JS Unit Tests

**Files:**
- Modify: `modules/monmon-rs/templates/historic_spent.html`
- Create: `public/javascripts/historic-spent.js`
- Modify: `public/stylesheets/main.css`
- Modify: `public/javascripts/sw.js`
- Create: `modules/tests-contract/historic-spent.unit.test.mjs`
- Sync: `modules/monmon-rs/public/` from `public/`

**Interfaces:**
- Consumes: `HistoricSpentTemplate` (`has_history`, `pots_summary`, `all_accounts_summary`, `cycles`), `spog-deck.js` (`.deck-nav`, `.deck-nav-pill`, `.deck-layout-grid`, `.deck-card`).
- Produces:
  - DOM IDs: `#historic-spent-nav`, `#historic-spent-grid`, `#historic-card-pots`, `#historic-card-all-accounts`.
  - URL hashes: `#pots` (default), `#all-accounts`.
  - Script: `/assets/javascripts/historic-spent.{{ crate::BUILD_HASH }}.js`.

- [ ] **Step 1: Write failing Node unit tests in `modules/tests-contract/historic-spent.unit.test.mjs`**

Test cases:
- Defaults to `#pots` (`historic-card-pots` has `.historic-tab-active`, `pots` pill active) when URL hash is empty or unknown.
- Initializes directly to `#all-accounts` when `window.location.hash === '#all-accounts'`.
- Clicking the `All Accounts` pill on desktop switches `.historic-tab-active` to `#historic-card-all-accounts`, updates `aria-selected`, and updates hash via `history.replaceState` to `#all-accounts`.
- Responding to `hashchange` (`#all-accounts` -> `#pots`) switches active card and pill back.
- Clicking a `.historic-pace-link` inside `<summary>` calls `stopPropagation()` so `<details>` does not toggle.
- Mobile swipe settle (when `.deck-nav-pill.active` changes on `<= 768px`) syncs the URL hash via `history.replaceState`.

- [ ] **Step 2: Run Node test to verify it fails**

Run: `node --test modules/tests-contract/historic-spent.unit.test.mjs`
Expected: FAIL (`historic-spent.js` does not exist yet).

- [ ] **Step 3: Implement `public/javascripts/historic-spent.js`, update `public/stylesheets/main.css`, `public/javascripts/sw.js`, and `modules/monmon-rs/templates/historic_spent.html`**

- `public/javascripts/historic-spent.js`: plain JS IIFE handling `#pots` / `#all-accounts` hash init, desktop `.historic-tab-active` card toggle, pill clicks, `hashchange`, mobile swipe-settle hash sync (via `MutationObserver` on pill `class` attribute or scroll listener), and `.historic-pace-link` click `stopPropagation()`.
- `public/stylesheets/main.css`:
  - `.historic-spent-deck-nav` visible on both mobile and desktop (`display: flex` on desktop too, compact pill bar style).
  - On desktop (`@media (min-width: 769px)`), `.historic-spent-deck-grid .deck-card` is `display: none`, and `.historic-spent-deck-grid .deck-card.historic-tab-active` is `display: block`.
  - Responsive `.historic-desktop-table` / `.historic-mobile-cards` toggle at `768px` (using Stacked Action Card `.item-card` + `.item-card-header` + `.item-card-stats-grid` on mobile).
- `public/javascripts/sw.js`: add `'historic-spent'` to `JS_PRECACHE_NAMES`.
- `modules/monmon-rs/templates/historic_spent.html`:
  - Clean header + `.deck-nav.historic-spent-deck-nav` (`Monthly Pots` / `All Accounts`).
  - Card 1 (`#historic-card-pots`): Monthly Pots 3-box KPI strip + SPOG-matched Spent This Month cycle cards + expandable per-pot window breakdown (desktop table + mobile Stacked Action Cards).
  - Card 2 (`#historic-card-all-accounts`): All Accounts 3-box KPI strip + Start → End signed cycle cards (`£X.XX spent` or `+£X.XX net inflow`) + expandable per-account breakdown (desktop table + mobile Stacked Action Cards with `LOAN` liability & `Pot Account` badges and reconciled total).
  - Include `<script src="/assets/javascripts/historic-spent.{{ crate::BUILD_HASH }}.js" type="text/javascript"></script>`.
- Sync static assets: `cp -ru public/* modules/monmon-rs/public/`.

- [ ] **Step 4: Run Node tests and Rust compile + tests**

Run:
```bash
NODE_BIN="$HOME/.nvm/versions/node/$(ls -1 $HOME/.nvm/versions/node | tail -n 1)/bin/node"
"$NODE_BIN" --test modules/tests-contract/historic-spent.unit.test.mjs modules/tests-contract/spog-deck-scroll.unit.test.mjs modules/tests-contract/offline-mode.unit.test.mjs modules/tests-contract/sw.unit.test.mjs
cd modules/monmon-rs && "$HOME/.cargo/bin/cargo" test
```
Expected: All Node unit tests and all Rust tests (including Askama template compilation of `historic_spent.html`) PASS.

- [ ] **Step 5: Commit frontend changes**

```bash
git add modules/monmon-rs/templates/historic_spent.html public/ modules/monmon-rs/public/ modules/tests-contract/historic-spent.unit.test.mjs
git commit -m "[agy] Redesign /historicSpent into Monthly Pots and All Accounts deck views"
```

---

### Task 4: Whole-Branch Review, Push & Deploy (`./ship/monmon.sh`)

- [ ] **Step 1: Inspect `git diff` and verify spec checklist**
  - Verify SPOG parity math, multi-pot windowing, `LOAN` liability sign, payday-morning guard, unclamped net inflow, mobile Stacked Action Cards, `public/` sync, and `sw.js` precache.
- [ ] **Step 2: Push to remote**
  - Run `git push`.
- [ ] **Step 3: Deploy via `./ship/monmon.sh` (explicitly authorized by user)**
  - Run `./ship/monmon.sh` and confirm clean ARM64 build, sync, and remote container startup.

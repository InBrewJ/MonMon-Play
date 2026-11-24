# Unified Monthly Pot Spend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Unify SPOG's **Spent This Month** and `/historicSpent#pots` onto a single shared synthesized pot spend calculation (final pot's accounts scanned full-cycle + earlier-only pot accounts scanned within their active windows) and display a reconciling synthesized breakdown above pot history on `/historicSpent#pots`.

**Architecture:** Extract `load_user_monthly_pots`, `resolve_cycle_pot_intervals`, and `compute_cycle_synthesized_pot_spend` in `modules/monmon-rs/src/historic_spent.rs`. Both `historic_spent::calculate_historic_cycles` and `spog::calculate_spog_as_of` call these shared functions so `spent_this_month` and `/historicSpent#pots` match to the exact penny while keeping SPOG Card 1's `pot_balance` strictly on the currently active pot. `modules/monmon-rs/templates/historic_spent.html` renders a reconciling Synthesized Pot Breakdown (desktop table + mobile Stacked Action Cards) whenever a cycle has multiple pots or synthesized spend differs from individual pot windows.

**Tech Stack:** Rust (`axum`, `sqlx`, `askama`, `chrono`, `crate::money::Pence`), Askama HTML template (`historic_spent.html`), `cargo test`.

**Spec:** `docs/superpowers/specs/2026-11-24-synthesized-pot-spend-design.md`

## Global Constraints

- Pure Rust backend logic + existing Askama template (`historic_spent.html`); no CoffeeScript or new dependencies.
- Exact 2-decimal currency formatting using `crate::money::Pence`.
- Mobile tables use MonMon's **Stacked Action Card** pattern (`.historic-desktop-table` / `.historic-mobile-cards` + `.item-card`).
- Preserve SPOG Card 1 (`pot_balance` / "Left per day") strictly on the currently active pot's accounts.
- Preserve SPOG time-travel (`as_of`) correctness: pots created after `as_of` must not affect historical views.
- Keep `HistoricCycleDto` compatible with `/spendingPace` (no regressions).
- Commit messages prefixed with `[agy]`. Never run deploy scripts.

## Review Focus

- **Duplicate `account_id` rows in `pot_accounts` for the same pot:** must deduplicate so an account's spend is never double-counted (pinned in Task 1).
- **Account removed mid-cycle and re-added in the final pot (`[1, 2]` → `[1]` → `[1, 2]`):** must be scanned full-cycle once as a final-pot account, never full-cycle plus an earlier window (pinned in Task 1).
- **SPOG time-travel (`as_of`) with a pot created after `as_of` or archived after `as_of`:** `resolve_cycle_pot_intervals` must exclude post-cutoff pots and treat pots archived strictly after `cutoff` as active at `cutoff` (pinned in Task 2).
- **Transient `<1h` £0-spend pot hidden from UI leaving 1 visible pot card whose window missed pre-pot spend:** `show_synthesized_breakdown` must still be `true` so the UI reconciles cleanly to the headline (pinned in Task 3).
- **Earlier-only account with £0.00 spend during its brief temporary window (e.g. Natwest in a 54-min plan preview):** must be excluded from `synthesized_pot_accounts` and contribute `£0.00` (pinned in Task 1).

---

### Task 1: Shared Synthesized Pot Spend Engine in `historic_spent.rs`

**Files:**
- Modify: `modules/monmon-rs/src/historic_spent.rs:365-690`, `modules/monmon-rs/src/historic_spent.rs:1263-1325`, `modules/monmon-rs/src/historic_spent.rs:1482-1555`, `modules/monmon-rs/src/historic_spent.rs:1687-1785`
- Test: `modules/monmon-rs/src/historic_spent.rs` (`#[cfg(test)] mod tests`)

**Interfaces:**
- Produces:
  - `pub struct SynthesizedPotAccountDto { pub account_id: i64, pub account_name: String, pub account_type: String, pub amount_spent_pence: Pence, pub is_full_cycle: bool }`
  - `pub async fn load_user_monthly_pots(pool: &PgPool, user_id: &str) -> Result<Vec<PotRecord>, sqlx::Error>`
  - `pub fn resolve_cycle_pot_intervals(all_pots: &[PotRecord], cycle_start_sec: i64, cycle_end_sec: i64, is_current: bool, as_of_cutoff_sec: Option<i64>) -> Vec<ActivePotInterval>`
  - `pub fn compute_cycle_synthesized_pot_spend(intervals: &[ActivePotInterval], balance_map: &HashMap<i64, Vec<(i64, f32)>>, account_names_map: &HashMap<i64, (String, String)>, start_sec: i64, end_sec: i64, effective_end_sec: i64, is_current: bool) -> (Pence, Vec<SynthesizedPotAccountDto>, Vec<HistoricPotSnapshotDto>)`
  - `HistoricCycleDto` fields: `pub synthesized_pot_accounts: Vec<SynthesizedPotAccountDto>`, `pub show_synthesized_breakdown: bool`

- [ ] **Step 1: Write the failing unit tests in `modules/monmon-rs/src/historic_spent.rs`**

Add unit tests covering all spec & Review Focus cases:
- `test_synthesized_pot_spend_catches_pre_addition_card_and_ignores_brief_hub_pot`:
  - Simulates Jason's exact live scenario: Pot 1 `{94 (Revolut), 8638 (Monzo)}` (Oct 29 → Oct 30 13:04), Pot 2 `{8638}` (Oct 30 13:04 → Oct 31 12:09; Revolut drops `-33.41` at Oct 30 13:09 outside its window), Pot 3 `{95 (CASH), 8638}` (Oct 31 → Nov 22 11:28; Flex drops `-15.45` on Nov 21 18:00), temporary Pot 4 `{2 (Natwest), 94, 8638}` for 54 min (Natwest has `-2893.18` earlier in cycle, `0` during those 54 min), final Pot 5 `{95, 8638, 8787 (Flex)}` (Nov 22 11:28 → end; Flex drops `-31.72` and `-49.05`).
  - Asserts `total == Pence::from_pounds_f64(602.09)` (using Monzo `-481.87`, CASH `-24.00`, Flex `-96.22`).
  - Asserts `synthesized_accounts` has exactly 3 entries (`95`, `8638`, `8787`), all `is_full_cycle == true`, and excludes `Natwest (2)` and `Revolut (94)` since both had `£0.00` during their windows.
- `test_synthesized_pot_spend_rotation_123_to_1234_to_45_captures_all_five`:
  - Pot A `[1, 2, 3]` (Days 1–20: `1` spends £40, `2` spends £30, `3` spends £20; `4` spends £15 on Day 18 before Pot B), Pot B `[1, 2, 3, 4]` (Days 20–28: `1` spends £10, `4` spends £25), Pot C `[4, 5]` (Days 28–30: `1` spends £100 after removal -> must be excluded; `5` spends £50 on Day 29; `5` also spent £12 on Day 5 -> included via full cycle).
  - Asserts `total == £40+£30+£20+£10 + (£15+£25) + (£12+£50) = £202.00`.
  - Asserts `4` and `5` have `is_full_cycle == true`, and `1`, `2`, `3` have `is_full_cycle == false` with `£50.00`, `£30.00`, `£20.00` respectively.
- `test_synthesized_pot_spend_deduplicates_accounts_and_readded_account`:
  - Pot 1 `[10, 10, 20]` → Pot 2 `[10]` → Pot 3 `[10, 20, 20]`; account `10` spends £20 in window 1, £15 in window 2; account `20` spends £30 in window 1 and £25 in window 3.
  - Asserts `total == £90.00` (no double-counting from duplicate `pot_accounts` rows or re-added account `20`), and `synthesized_accounts.len() == 2`, both `is_full_cycle == true`.

- [ ] **Step 2: Run tests to verify they fail**

Run: `cargo test --manifest-path modules/monmon-rs/Cargo.toml historic_spent::tests::test_synthesized_pot_spend`
Expected: FAIL to compile (`compute_cycle_synthesized_pot_spend` / `SynthesizedPotAccountDto` not defined).

- [ ] **Step 3: Implement `SynthesizedPotAccountDto`, `load_user_monthly_pots`, `resolve_cycle_pot_intervals`, and `compute_cycle_synthesized_pot_spend` in `modules/monmon-rs/src/historic_spent.rs`**

Implementation notes:
- `compute_cycle_synthesized_pot_spend`:
  - Returns `(Pence::ZERO, Vec::new(), Vec::new())` when `intervals.is_empty()` or (`is_current && !intervals.iter().any(|i| !i.pot.archived)`).
  - Computes `pots_in_period` (same per-window display snapshots and transient `<1h` £0 omission as current `compute_cycle_pot_snapshots`).
  - Final interval `final_int = intervals.last().unwrap()`:
    - `final_win_end = if final_int.end < effective_end_sec { final_int.end } else { effective_end_sec }`.
    - Deduplicate `final_int.pot.accounts` preserving order into `final_ids` + `HashSet<i64>`.
    - Scan each final account over `[start_sec, final_win_end]` (`inclusive_end = true`) via `calculate_account_spent_window`.
  - Earlier intervals `0..intervals.len() - 1`:
    - `win_start = if idx == 0 { start_sec } else { interval.start.max(start_sec) }`, `win_end = intervals[idx + 1].start.min(effective_end_sec)`.
    - Deduplicate `interval.pot.accounts` per interval; for each `acc_id` not in `final_set`, add `Pence::from_pounds_f32(calculate_account_spent_window(bals, win_start, win_end, false))` into an order-preserving accumulator.
    - Emit earlier-only accounts with `amount_spent_pence > Pence::ZERO` with `is_full_cycle: false`.
  - Sum `synthesized_accounts` into `total_pot_spent`.
- Update `compute_cycle_pot_snapshots` to delegate to `compute_cycle_synthesized_pot_spend` (returning `(total, pots_in_period)`) so all existing tests stay valid.
- Wire `calculate_historic_cycles` to call `load_user_monthly_pots`, `resolve_cycle_pot_intervals`, and `compute_cycle_synthesized_pot_spend`, populating `synthesized_pot_accounts` and `show_synthesized_breakdown = has_multiple_pots || synthesized_pot_accounts.iter().any(|a| !a.is_full_cycle) || pots_in_period.iter().map(|p| p.pot_spent_pence).sum::<Pence>() != pot_spent_pence`.
- Update `test_compute_historic_summaries_separates_pots_and_all_accounts_kpis` struct literal with the two new `HistoricCycleDto` fields.

- [ ] **Step 4: Run `historic_spent` unit tests to verify they pass**

Run: `cargo test --manifest-path modules/monmon-rs/Cargo.toml historic_spent::tests`
Expected: PASS (all existing + 3 new synthesized tests green).

- [ ] **Step 5: Commit Task 1**

```bash
git add docs/superpowers/specs/2026-11-24-synthesized-pot-spend-design.md docs/superpowers/plans/2026-11-24-synthesized-pot-spend.md modules/monmon-rs/src/historic_spent.rs
git commit -m "[agy] Add shared synthesized monthly pot spend calculation to historic_spent"
```

---

### Task 2: Wire SPOG `spent_this_month` to the Shared Synthesized Pot Spend Engine

**Files:**
- Modify: `modules/monmon-rs/src/spog.rs:693-810`
- Test: `modules/monmon-rs/src/spog.rs` (`#[cfg(test)] mod tests`) and `modules/monmon-rs/src/historic_spent.rs` (time-travel interval test)

**Interfaces:**
- Consumes from Task 1:
  - `crate::historic_spent::load_user_monthly_pots(pool: &PgPool, user_id: &str) -> Result<Vec<PotRecord>, sqlx::Error>`
  - `crate::historic_spent::resolve_cycle_pot_intervals(all_pots: &[PotRecord], cycle_start_sec: i64, cycle_end_sec: i64, is_current: bool, as_of_cutoff_sec: Option<i64>) -> Vec<ActivePotInterval>`
  - `crate::historic_spent::compute_cycle_synthesized_pot_spend(...) -> (Pence, Vec<SynthesizedPotAccountDto>, Vec<HistoricPotSnapshotDto>)`
- Produces:
  - `spog::calculate_spog_as_of` computes `spent_this_month` using `compute_cycle_synthesized_pot_spend` while preserving `pot_balance` strictly on the currently active pot's `pot_account_ids`.

- [ ] **Step 1: Write failing unit tests for SPOG parity & time-travel `as_of` cutoff**

Add tests in `modules/monmon-rs/src/spog.rs`:
- `test_spog_and_historic_spent_share_exact_synthesized_pot_spend`:
  - Constructs a cycle with Pot 1 `[1, 2]` (Days 1–15, `1` spends £40, `2` spends £30) → Pot 2 `[2, 3]` (Days 15–25, `3` spent £15.45 on Day 14 before Pot 2 and £31.72 on Day 16; `1` spent £50 on Day 20 after removal).
  - Calls `resolve_cycle_pot_intervals` + `compute_cycle_synthesized_pot_spend` with both SPOG parameters (`is_current = true, as_of_cutoff_sec = None`) and historic completed-cycle parameters (`is_current = false, as_of_cutoff_sec = None`).
  - Asserts both yield the exact same `Pence::from_pounds_f64(117.17)` (`40 + 30 + 15.45 + 31.72`, excluding `1`'s post-removal £50).
- `test_resolve_cycle_pot_intervals_respects_as_of_time_travel_cutoff`:
  - Pot 1 `[10]` created Day 1, archived Day 20 (last_updated = Day 20); Pot 2 `[10, 20]` created Day 20, unarchived.
  - With `as_of_cutoff_sec = Some(Day 10)`: asserts only Pot 1 is returned and `pot.archived == false` as of Day 10 (so `compute_cycle_synthesized_pot_spend` with `is_current = true` does NOT zero it out, and does NOT include Pot 2's account `20`).

- [ ] **Step 2: Run tests to verify failure (or verify `as_of` behavior)**

Run: `cargo test --manifest-path modules/monmon-rs/Cargo.toml spog::tests::test_spog_and_historic_spent_share_exact_synthesized_pot_spend spog::tests::test_resolve_cycle_pot_intervals_respects_as_of_time_travel_cutoff`
Expected: FAIL before `spog.rs` wiring / test additions complete.

- [ ] **Step 3: Wire `spog::calculate_spog_as_of` in `modules/monmon-rs/src/spog.rs:693-810`**

- Compute `cycle_end_sec` (`cycle_end` at `00:00:00 UTC`).
- Load all user `MONTHLY` pots via `crate::historic_spent::load_user_monthly_pots(pool, &user_id).await.unwrap_or_default()`.
- Resolve `cycle_intervals = crate::historic_spent::resolve_cycle_pot_intervals(&monthly_pots, cycle_start_sec, cycle_end_sec, true, as_of_epoch_end)`.
- Keep `pot_account_ids` for Card 1 `pot_balance` strictly from the active pot (`active_pot_id_row`, or when `as_of_epoch_end.is_some()`, from `cycle_intervals.last()`'s accounts if unarchived).
- Add every `account_id` referenced in `cycle_intervals` into `balance_acc_ids` before `load_account_balances(pool, &balance_acc_ids)` so archived/earlier pot accounts have their balances loaded.
- Replace lines 796–803 with:
  ```rust
  let empty_names = std::collections::HashMap::new();
  let (spent_this_month, _, _) = crate::historic_spent::compute_cycle_synthesized_pot_spend(
      &cycle_intervals,
      &balance_map,
      &empty_names,
      cycle_start_sec,
      cycle_end_sec,
      now_sec,
      true,
  );
  ```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cargo test --manifest-path modules/monmon-rs/Cargo.toml spog::tests`
Expected: PASS (all SPOG unit tests pass).

- [ ] **Step 5: Commit Task 2**

```bash
git add modules/monmon-rs/src/spog.rs
git commit -m "[agy] Use shared synthesized monthly pot spend logic in SPOG"
```

---

### Task 3: Reconciling Synthesized Pot Breakdown UI on `/historicSpent#pots`

**Files:**
- Modify: `modules/monmon-rs/templates/historic_spent.html:148-252`
- Test: `modules/monmon-rs/src/historic_spent.rs` (template render & `show_synthesized_breakdown` unit tests)

**Interfaces:**
- Consumes from Task 1:
  - `HistoricCycleDto.synthesized_pot_accounts: Vec<SynthesizedPotAccountDto>` (`account_name`, `account_type`, `amount_spent_pence`, `is_full_cycle`)
  - `HistoricCycleDto.show_synthesized_breakdown: bool`
  - `HistoricCycleDto.pot_spent_pence: Pence`

- [ ] **Step 1: Write failing unit tests in `modules/monmon-rs/src/historic_spent.rs`**

Add two tests:
- `test_show_synthesized_breakdown_true_when_transient_pot_hidden_leaves_one_card_with_pre_pot_spend`:
  - Pot 1 (`[10]`, lasted 30 min, £0 spend → omitted from `pots_in_period`) → Pot 2 (`[10, 20]`; account `20` had `-£15.45` before Pot 2 was created, `£0` after).
  - Asserts `pots_in_period.len() == 1`, `pots_in_period[0].pot_spent_pence == Pence::ZERO`, `total_spent == Pence::from_pounds_f64(15.45)`, and `show_synthesized_breakdown` evaluates to `true`.
- `test_historic_spent_template_renders_synthesized_breakdown_and_pot_history`:
  - Renders `HistoricSpentTemplate` with a multi-pot cycle containing 1 `Full cycle` account (`Flex`, `£96.22`) and 1 `Earlier pot window` account (`Card A`, `£40.00`).
  - Asserts rendered HTML contains `"Synthesized Pot Breakdown"`, `"Full cycle"`, `"Earlier pot window"`, `"£96.22"`, `"£40.00"`, and `"£136.22"`, and does NOT show `"pots summed"` when `show_synthesized_breakdown` is true.

- [ ] **Step 2: Run tests to verify they fail**

Run: `cargo test --manifest-path modules/monmon-rs/Cargo.toml historic_spent::tests::test_historic_spent_template_renders_synthesized_breakdown_and_pot_history`
Expected: FAIL (template does not yet render `"Synthesized Pot Breakdown"`).

- [ ] **Step 3: Update `modules/monmon-rs/templates/historic_spent.html:148-252`**

- When `c.show_synthesized_breakdown && !c.synthesized_pot_accounts.is_empty()`:
  - Render a **Synthesized Pot Breakdown** card above the **Pot History** cards:
    - Desktop (`.historic-desktop-table`): table with columns `Account`, `Scope` (`Full cycle` primary pill / `Earlier pot window` subtle pill), `Outflow`, plus bold `Total Pot Spend` footer row showing `£{{ c.pot_spent_pence }}`.
    - Mobile (`.historic-mobile-cards`): Stacked Action Cards (`.item-card`) with account name left, type + scope badge right, outflow stat cell below, plus compact total card.
- Change the multi-pot badge inside `<details>` from `{{ c.pots_in_period.len() }} pots summed` to `{{ c.pots_in_period.len() }} pots in cycle` when `c.show_synthesized_breakdown` is true so the copy matches the synthesized math.
- Keep the existing **Pot History** cards underneath unchanged so every pot's creation/archive date and account membership remains inspectable.

- [ ] **Step 4: Run full Rust test suite and verify clean build**

Run: `cargo test --manifest-path modules/monmon-rs/Cargo.toml`
Expected: All unit tests compile and pass with 0 failures.

- [ ] **Step 5: Commit Task 3**

```bash
git add modules/monmon-rs/templates/historic_spent.html modules/monmon-rs/src/historic_spent.rs
git commit -m "[agy] Show reconciling synthesized pot breakdown above pot history on /historicSpent#pots"
```

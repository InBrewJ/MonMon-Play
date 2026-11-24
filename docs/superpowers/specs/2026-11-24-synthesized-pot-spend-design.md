# Unified Monthly Pot Spend: Final-Pot Full-Cycle + Earlier-Pot Windows

**Date:** 2026-11-24
**Status:** Approved
**Size:** Small change
**Target files:**
- `modules/monmon-rs/src/historic_spent.rs`
- `modules/monmon-rs/src/spog.rs`
- `modules/monmon-rs/templates/historic_spent.html`

---

## Intent & Why

When you spend on a card and add that card to your Monthly Pot shortly afterward (e.g., `£15.45` on Flex 17 hours before adding Flex to the pot), `/historicSpent#pots` misses that spend because it time-slices each pot strictly from its `createdat` timestamp, causing it to disagree with SPOG (`£586.64` vs `£602.09`). Meanwhile, when you swap accounts across a month (`[1, 2, 3]` → `[1, 2, 3, 4]` → `[4, 5]`), you want the cycle's spend against budget to capture all accounts you genuinely used in pots that cycle — and you want **SPOG** and **`/historicSpent#pots`** to always produce the exact same figure.

## Goals, Non-Goals & Anti-Goals

- **Goals:**
  - Single shared calculation used by both **SPOG (`spent_this_month`)** and **`/historicSpent#pots`** so the two pages always match to the exact penny.
  - Capture full-cycle spend for every account in the cycle's final (active) pot, catching purchases made shortly before adding a card to the pot.
  - Preserve spending on accounts that were in earlier pots during that cycle (`[1, 2, 3]` before switching to `[4, 5]`) for the windows when those pots were active.
  - Keep pot history visible when expanding a cycle on `/historicSpent#pots`, alongside a breakdown that reconciles cleanly to the cycle headline.
- **Non-Goals:**
  - Do not change SPOG Card 1 (`pot_balance` / "Left per day") — remaining cash stays strictly on the currently active pot's accounts.
  - Do not change `/historicSpent#all-accounts`.
  - Incoming edit-as-archive-and-recreate is tracked separately as a follow-up task.
- **Anti-Goals:**
  - Pulling full-cycle outflows from non-pot accounts (e.g. salary/bills hubs like Natwest `£2,893.18` or First Direct `£531.15`) that only briefly appeared in a switched/temporary pot.
  - Double-counting any balance snapshot at pot boundaries or across overlapping accounts.
  - Current-cycle spend changing retroactively when payday rolls over and the cycle becomes completed.

## Constraints

- Pure Rust backend logic + existing Askama template (`historic_spent.html`), no CoffeeScript or new dependencies.
- Preserve SPOG time-travel (`as_of`) correctness: when viewing a historical date, only pots created on or before `as_of` are considered.
- Keep `HistoricCycleDto` compatible with `/spendingPace` (no regressions).

## Approaches Considered

1. **Final-pot full-cycle + earlier-pot active windows, shared by SPOG and `/historicSpent#pots` — Selected**
   - Every account in the cycle's **final pot** is scanned across the **full cycle** (`cycle_start → effective_end`).
   - Every account present **only in earlier pots** of that cycle is scanned **only across those earlier pots' active windows** (`[win_start, win_end)`, half-open so boundaries never double-count).
   - *Why picked:* Handles `[1, 2, 3]` → `[1, 2, 3, 4]` → `[4, 5]` cleanly, fixes the `£15.45` Flex gap (`£602.09` on both SPOG and `/historicSpent#pots`), keeps October's Flex spend when a new pot is pre-created before payday, and prevents brief plan-switches (`Natwest`, `First Direct`, `Revolut`) from inflating the cycle to `£4,059.85`.
2. **Pure union of all pots in the cycle, full-cycle scan — Rejected**
   - Pulling full-cycle negative deltas from every account ever touched by a pot in the cycle blows up the current cycle to `£4,059.85` due to brief switches through plans containing `Natwest Reward Debit` (`£2,893.18`), `First Direct Current` (`£531.15`), and removed `Revolut` (`£33.41`).
3. **Final pot only, full-cycle scan — Rejected**
   - Drops spending from earlier cards in the cycle when you intentionally rotate out of `[1, 2, 3]` into `[4, 5]` or pre-create next month's pot the evening before payday.

## Design Summary

### Shared Calculation (`historic_spent.rs`, reused by `spog.rs`)
- Extract a shared helper (e.g. `compute_cycle_synthesized_pot_spend`) taking the cycle's active `ActivePotInterval`s, `balance_map`, `account_names_map`, `start_sec`, `end_sec`, `effective_end_sec`, and `is_current`:
  - **No active pot (or current cycle with no unarchived pot):** return `£0.00` (same as today).
  - **Final pot accounts (`final_acc_ids`):**
    - Final window end is `effective_end_sec` (or `final_interval.end` if a past-cycle pot was archived unreplaced before cycle end).
    - For each unique `acc_id` in `final_acc_ids`, compute `calculate_account_spent(bals, start_sec, final_end)` once across the full cycle.
  - **Earlier-only pot accounts (`acc_id` not in `final_acc_ids`):**
    - For each earlier interval `i < n - 1` with half-open window `[win_start, intervals[i+1].start.min(effective_end_sec))`, add `calculate_account_spent_window(bals, win_start, win_end, false)` into that account's synthesized total.
  - **Total cycle pot spend** = sum of all synthesized per-account totals (exact `Pence` arithmetic).
- **SPOG wiring (`spog.rs`):**
  - Load the user's `MONTHLY` pots (respecting `as_of` cutoff when time-traveling) and compute `spent_this_month` via the exact same shared helper.
  - Keep `pot_balance` (Card 1 remaining balance) strictly on the active pot's `pot_account_ids`.

### UI Breakdown (`historic_spent.html` — builder proposal)
- When a cycle has multiple pots (or accounts contributed from earlier pots), show a compact **Synthesized Pot Breakdown** table (desktop) / **Stacked Action Cards** (mobile) at the top of the expanded `<details>` showing each contributing account, its scope badge (`Full cycle` vs `Earlier pot window`), and its outflow — summing cleanly to the cycle headline.
- Keep the **Pot History** cards underneath so you can still inspect when pots were created/replaced and which accounts each pot held. (Single-pot cycles remain uncluttered with just the single pot breakdown.)

## Left to the Builder
- Exact helper/DTO struct and field names in `historic_spent.rs` and `spog.rs`.
- Query optimization for loading `MONTHLY` pots + `pot_accounts` in `spog.rs` (including archived accounts referenced by earlier pots in the current cycle).
- Unit tests in `historic_spent.rs` and `spog.rs` covering `[1,2,3] → [1,2,3,4] → [4,5]`, pre-addition card spend (`£15.45` Flex case), brief temporary-pot immunity (`Natwest`/`Revolut` case), SPOG parity, and time-travel (`as_of`).

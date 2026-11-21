# `/historicSpent` Redesign: Monthly Pots History & All-Accounts Wider Picture

**Date:** 2026-11-21
**Status:** Ready for Approval
**Target files:**
- `modules/monmon-rs/src/historic_spent.rs`
- `modules/monmon-rs/templates/historic_spent.html`
- `public/javascripts/historic-spent.js` (synced to `modules/monmon-rs/public/javascripts/`)
- `public/stylesheets/main.css` (synced to `modules/monmon-rs/public/stylesheets/`)

---

## 1. Intent & Why

`/historicSpent` currently mixes two different financial questions into the same collapsed accordion rows: the top KPIs and row headlines show all-accounts spend against monthly income, while the status badge and progress bar right beside them show monthly-pot spend against allowance. Worse, the monthly-pot spend formula in `historic_spent.rs` (net change on the last pot minus savings inflows) disagrees with SPOG's **Spent This Month** card even for the current cycle, and the all-accounts spend figure clamps negative months to `£0.00`.

The user wants `/historicSpent` to clearly separate and answer two distinct questions:

1. **Historic Monthly Pots (`#pots`)** — a month-by-month version of SPOG's **Spent This Month** card (`£spent / £monthly pot`, daily pace `£X/day`, over/under `£Y/day` vs `MAX (£Z)`), accurately handling pay cycles where more than one monthly pot existed because pots were deleted/archived and recreated mid-month, and matching SPOG's numbers to the exact penny for the current cycle.
2. **All-Accounts Wider Picture (`#all-accounts`)** — total spending across all included accounts in each pay cycle (**post-payday Start → pre-next-payday End**), summed as if all accounts were one big account, so major out-of-pot events (such as buying a house from savings) are immediately visible.

---

## 2. Goals, Non-Goals & Anti-Goals

### Goals
- **SPOG penny parity:** In the current pay cycle, `#pots` displays the exact same `spent`, `monthly pot` allowance, `days_elapsed / days_in_cycle`, `daily pace`, `MAX / day`, and `overspent / under by £X/day` values as SPOG's **Spent This Month** card.
- **Multi-pot cycle accuracy:** When a cycle had multiple monthly pots (deleted/replaced mid-cycle), the cycle's headline pot spend sums outflow spending across each pot's active window and lists each pot window underneath on expand.
- **Truthful All-Accounts total spend picture:** Sum **post-payday Start → pre-next-payday End** balances across all active accounts except `MORTGAGE` and `PENSION` (explicitly **including `LOAN`** as a liability), with signed `Start − End` (positive = total spent across all accounts; negative = net inflow exceeding spend, shown clearly rather than clamped to `£0.00`).
- **Clean UX across mobile & desktop:** Separate KPI strips and timelines per view using MonMon's `spog-deck` swipe deck + dot headings on mobile, hash-addressed pill switching (`#pots`, `#all-accounts`) on both desktop and mobile, and Stacked Action Cards for mobile breakdown tables.
- **No regressions to `/spendingPace`:** Keep `HistoricCycleDto` fields used by `spog::get_spending_pace_page` intact.

### Non-Goals
- Do not include `MORTGAGE` or `PENSION` accounts in All-Accounts (explicitly deferred for now).
- Do not include archived accounts in All-Accounts queries (keep current active-account scope).
- Do not redesign `/spog` or `/spendingPace`.
- Do not use CoffeeScript or add new third-party frontend dependencies.

### Anti-Goals
- Showing a current-cycle Monthly Pot figure on `/historicSpent` that differs from SPOG's **Spent This Month** card.
- Double-counting a loan repayment from a debit account as spending (e.g. treating a positive `LOAN` owed balance as an asset).
- Blending Monthly Pot stats and All-Accounts stats in the same KPI strip or row headline.

---

## 3. Constraints

- **Plain JavaScript only** (`public/javascripts/*.js`), synced to `modules/monmon-rs/public/`.
- **MonMon UX primitives (`AGENTS.md`):**
  - `spog-deck` swipeable cards with dot headings on mobile (`<= 768px`).
  - **Stacked Action Card** layout for responsive mobile tables (account title left + type/status badge right, concise stat grid below).
  - **"At a Glance" upfront hierarchy:** KPI strip first, primary cycle headline visible without expanding, detailed pot/account breakdowns inside `<details>`.
- **Exact currency formatting:** Always format monetary amounts to 2 decimal places (`£0.00` / `£504.87`, using `crate::money::Pence` or explicit formatting) rather than raw Askama `f32` interpolation.

---

## 4. Approaches Considered

1. **Two focused views with `spog-deck` / pill switcher (`#pots` & `#all-accounts`) — Selected**
   - Top pill tabs (`Monthly Pots` | `All Accounts`) visible on both desktop and mobile, persisted in the URL hash (`#pots`, `#all-accounts`).
   - On mobile (`<= 768px`), uses `spog-deck` horizontal swipe deck with dot indicators.
   - On desktop (`>= 769px`), pills switch which view is displayed (one active card at a time) so tables and cards have full width.
   - *Why picked:* Gives each mental model its own truthful KPI strip and clean "At a Glance" hierarchy, eliminates mobile clutter, and matches MonMon's existing deck/hash-tab primitives (`/netWorth`, `/savingsPlan`, `/myOutgoings`).
2. **Side-by-side two columns on desktop, swipe deck on mobile — Rejected**
   - Two ~500px columns cramp expandable account/pot tables on laptop screens, and expanding a cycle in one column knocks the two columns out of vertical alignment.
3. **Single timeline with split two-panel cycle cards — Rejected**
   - Doubles card height on mobile across a year of cycles and forces the top KPI strip to either double to 6 cards or favor one view.

---

## 5. Detailed Design

### 5.1 View 1: Monthly Pots (`#pots`, default)

#### Pot Window & Outflow Math (SPOG Parity)
- Reuse `calculate_account_spent(balances, win_start, win_end)` (sum of negative balance deltas) — the exact outflow helper used by `spog::calculate_spog_as_of`.
- Add a helper `calculate_account_spent_window(balances, win_start, win_end, inclusive_end: bool) -> f32` so consecutive pot windows within a cycle never double-count a balance snapshot at an exact boundary timestamp `T`:
  - **First pot in a cycle (`i == 0`):** `win_start = cycle_start_sec` (even if that pot's `created_at > cycle_start_sec`), matching SPOG's full-cycle scan from `cycle_start_sec`.
  - **[Builder Call] Archive-to-create gap bridging (`i < n - 1`):** When Pot `i` was archived at `T_arch` and Pot `i + 1` was created later at `T_create >= T_arch`, Pot `i`'s window extends across the gap to `[win_start, next_interval.start.min(effective_end_sec))` (`inclusive_end = false`), and Pot `i + 1` starts at `next_interval.start.max(cycle_start_sec)`. That way no spending in `[T_arch, T_create)` is silently dropped, and if Pot `i` and Pot `i + 1` share the same accounts, their window sum equals `calculate_account_spent(bals, cycle_start_sec, effective_end_sec)` = exact SPOG parity.
  - **[Builder Call] Final pot (`i == n - 1`):**
    - If active through the cycle end (`interval.end >= effective_end_sec`): window is `[win_start, effective_end_sec]` (`inclusive_end = true`).
    - If archived mid-cycle with **no replacement** (`interval.end < effective_end_sec`):
      - In **past cycles**, stop its window at `interval.end` (`inclusive_end = true`) so post-archive spending isn't attributed to a deleted pot.
      - In the **current cycle**, if there is no currently active (unarchived) monthly pot, match SPOG (`pot_spent = £0.00`).
- **[Builder Call] Archived accounts referenced by historical pots:** Load balances and account names/types for **all** `account_id`s present in `pot_accounts` even if an account was later archived (while All-Accounts still queries active accounts only), so historical pot cycles retain accurate names and outflow totals.
- **[Builder Call] Empty `pot_accounts` / pot-less cycles:**
  - In the **current cycle**, match SPOG strictly (`[]` → `£0.00` if no active pot or no `pot_accounts` rows exist).
  - In **past completed cycles**, if a pot has empty `pot_accounts` (or no pot existed in that cycle), fall back consistently to `debit_account_ids` (never `ledger_account_ids`, which includes savings and loans).
- Per-pot snapshot spend (`pot_snap.pot_spent`) and per-account snapshot spend (`acc_snap.amount_spent`) use the exact same `calculate_account_spent_window` helper so the individual pot rows sum cleanly to the cycle's `pot_spent`.

#### SPOG Pace & Allowance Math (`Pence` precision)
- `pot_spent_pence = Pence::from_pounds_f32(cycle_pot_spent)`
- `pot_allowance_pence = if is_current { spog.surplus } else { Pence::from_pounds_f32(historical_allowance) }` (preserving existing fallback order: `cycle_allowance` → pot plan sum → starting pot budget → monthly income).
- `pot_days_elapsed = if is_current { ((today - cycle_start).num_days() as i32).min(days_in_cycle).max(1) } else { days_in_cycle }` (exact match to `spog.rs:1107-1109`).
- `pot_daily_pace = pot_spent_pence.checked_div_days(pot_days_elapsed as i64)`
- `pot_max_per_day = if is_current { spog.max_per_day } else { pot_allowance_pence.checked_div_days(days_in_cycle as i64) }`
- `(pot_daily_overspend, is_pot_overspent_daily, absolute_pot_daily_overspend) = crate::spog::compute_daily_overspend(pot_daily_pace, pot_max_per_day)`
- `pot_total_diff = pot_spent_pence - pot_allowance_pence` (`is_over_pot_allowance = pot_total_diff.is_positive()`, `absolute_pot_overspend = pot_total_diff.abs()`)
- Progress bar utilization: `(pot_spent / pot_allowance * 100.0).clamp(0.0, 100.0)` for bar width, colored red when `is_pot_overspent_daily` (current cycle) or `is_over_pot_allowance` (completed cycle), green otherwise.

#### View 1 UI Structure
- **At-a-Glance KPI Strip (3 boxes, computed over completed cycles; falls back to current cycle if no completed cycles exist):**
  1. **Avg Pot Spend** — average `pot_spent` per completed cycle.
  2. **Avg Daily Pace** — average `pot_daily_pace` across completed cycles (`£X.XX / day`).
  3. **Within MAX Pace** — `N / M` completed cycles where `!is_pot_overspent_daily` (with percentage subtitle).
- **Cycle Cards (newest first, current cycle highlighted with primary left border):**
  - Summary header mirrors SPOG's **Spent This Month** card:
    - Date range (`30 Oct 2026 – 30 Nov 2026`) + pill badge (`Day 22 of 31` for current cycle, `31 days` for completed cycles, colored red/green by `is_pot_overspent_daily`), plus a subtle `N pots` pill if `has_multiple_pots`, plus a compact chart icon link to `/spendingPace?cycleOffset={{ loop.index0 }}`.
    - Headline figure: **`£504.87`** `/ £366.72 monthly pot`
    - Daily pace line: `Spending pace: £22.95 / day`
    - SPOG-style status banner (red/green):
      - If overspent: `Overspent by £11.12 / day based on MAX (£11.83)`
      - If under/on pace: `£X.XX / day under MAX (£11.83)`
    - Compact utilization bar underneath.
  - Expanded `<details>` body:
    - If `pots_in_period` is non-empty, renders each pot window card (plan name & category summary, created/replaced dates, First / Intermediary / Last badges, `Spent in window: £X.XX`, and per-account table on desktop / Stacked Action Card rows on mobile).
    - If no custom pot was configured for a past cycle, shows a concise note that active debit accounts were used and lists their outflow spend for the cycle.

---

### 5.2 View 2: All Accounts (`#all-accounts`)

#### Account Scope & Liability Math
- Query active (non-archived) accounts for the user and exclude **only** `MORTGAGE` and `PENSION` (`t_upper != "MORTGAGE" && t_upper != "PENSION"`).
  - Included: `DEBIT`, `CURRENT`, `DEBIT_SHARED_BILLS`, `CREDIT`, `SAVINGS`, `SHORT_TERM_SAVINGS`, `LONG_TERM_SAVINGS`, `INVESTMENT`, **`LOAN`**, and any other non-mortgage/non-pension account.
- **Liability sign rule (`LOAN`):**
  - `LOAN` balance records in MonMon store positive amount owed (`net_worth.rs:352-357`).
  - For All-Accounts aggregation, compute each account's signed contribution:
    - If `t_upper == "LOAN"`: `signed_start = -start_bal`, `signed_end = -end_bal` (displayed clearly with a `LOAN` liability badge and signed balance `-£X.XX`).
    - Otherwise (`DEBIT`, `CREDIT`, `SAVINGS`, etc.): `signed_start = start_bal`, `signed_end = end_bal`.
    - Note: `CREDIT` balances already store *available credit* (`limit - owed`), so spending decreases the balance and paying off the card increases it — naturally matching `signed_start - signed_end`.

#### Post-Payday Start → Pre-Next-Payday End Math (Strictly Reconciled)
- Each cycle measures **total money spent across all included accounts** from **on-payday Start** (including that payday's salary inflow) to **pre-next-payday End** (before the next payday's salary arrives):
  - Example: Salary £1,000 arrives on payday → `Start £1,000 → End £500` = **`£500.00 spent`** (and next payday starts the new cycle at `Start £1,500`).
  - Example: House deposit £40,000 paid from savings → `Start £51,000 → End £11,000` = **`£40,000.00 spent`**.
- Keep `get_account_start_balance_with_inflow` on salary accounts so `Start` reflects the post-payday balance.
- **[Builder Call] Payday-morning snapshot guard:** On the current cycle, if a salary account has `payday_inflow > 0.0` and has **no recorded balance snapshot at or after `cycle_start_sec` yet** (meaning `Start` was computed synthetically as `latest_before + payday_inflow`), add that same synthetic `payday_inflow` to `End` (i.e. `end_bal = start_bal`) so payday morning shows `£0.00 spent` instead of a phantom full-salary spend before the bank updates. Once any balance snapshot exists at `t >= cycle_start_sec`, real balances are used directly.
- Per-account net spent: `acc_net_spent = round2(signed_start - signed_end)` (do **not** swap in `tbl_spent_by_account` for the current cycle, so `Start − End = Spent` always reconciles to the exact penny across every cycle).
- Cycle totals:
  - `starting_total_balance = round2(sum(signed_start))`
  - `ending_total_balance = round2(sum(signed_end))`
  - `all_accounts_net_spent = round2(starting_total_balance - ending_total_balance)` (signed — **not** clamped at `0.0`):
    - `> 0.0`: **`£X.XX spent`** (total spent across all accounts).
    - `< 0.0`: **`+£X.XX net inflow`** (styled green — non-payday inflows exceeded all spending).
    - `== 0.0`: **`£0.00 spent`**.

#### View 2 UI Structure
- **At-a-Glance KPI Strip (3 boxes, computed over completed cycles; falls back to current cycle if none):**
  1. **Avg All-Accounts Spend** — average signed `all_accounts_net_spent` per completed cycle (`£X.XX` or `+£X.XX net inflow`).
  2. **Highest Spend Cycle** — cycle with the largest `all_accounts_net_spent` (amount + date range).
  3. **Lowest Spend Cycle** — cycle with the lowest `all_accounts_net_spent` (`£X.XX`, or `+£X.XX net inflow` if negative, + date range).
- **Cycle Cards (newest first):**
  - Summary header:
    - Date range + `Day X of Y` (current) or `Y days` (completed) + muted `Income: £X.XX` context label.
    - Primary signed headline: **`£35,120.00 spent`** (or **`+£640.50 net inflow`** in green if negative).
    - Compact balance line: `Start £52,400.00 → End £17,280.00` (with `Start − End` immediately readable at a glance).
  - Expanded `<details>` body:
    - **Desktop (`>= 769px`):** Full-width account breakdown table (`Account`, `Start`, `End`, `Net Spend`, with `Pot Account` and `LOAN` liability badges + bold `Total Across Accounts` footer row reconciling `Start`, `End`, and `Net Spend`).
    - **Mobile (`<= 768px`):** **Stacked Action Card** pattern (`.mobile-cards-view` / `.item-card`): account name left + type/pot badge right, 3-cell stat grid (`Start`, `End`, `Net`) below, plus a compact summary total card at the bottom.

---

### 5.3 Navigation, Deck & URL Hash Behavior

- Page uses a `.deck-nav.historic-spent-deck-nav` bar with two `.deck-nav-pill` buttons:
  1. `Monthly Pots` (`data-target="historic-card-pots"`, hash `#pots`)
  2. `All Accounts` (`data-target="historic-card-all-accounts"`, hash `#all-accounts`)
- **Mobile (`<= 768px`):** Standard `spog-deck.js` handles horizontal swipe snapping, sticky pill bar with active dot indicator, and dynamic active-card height sync.
- **Desktop (`>= 769px`):** `.historic-spent-deck-nav` remains visible (`display: flex`), and only the active `.deck-card` is shown (`display: block`; inactive card hidden via CSS class toggled by `public/javascripts/historic-spent.js`).
- **Hash persistence (`public/javascripts/historic-spent.js`):**
  - Reads `window.location.hash` on `DOMContentLoaded` (`#all-accounts` selects All Accounts; `#pots` or empty defaults to Monthly Pots).
  - Updates `history.replaceState` when clicking a pill or finishing a mobile swipe between cards.
  - Responds to `hashchange` so browser Back/Forward switches views cleanly.
  - Clicking the `/spendingPace` icon link inside a `<summary>` stops propagation so it navigates instead of toggling `<details>`.

---

## 6. What Is Left to the Builder

- Exact Rust struct field naming (`Pence` vs formatted strings) and helper decomposition inside `historic_spent.rs`.
- Exact CSS class names and breakpoints inside `main.css` (extending existing `.deck-nav`, `.desktop-view`, `.mobile-cards-view`, `.item-card` conventions).
- Comprehensive unit tests in Rust (multi-pot window summing & gap bridging, SPOG current-cycle parity, archived pot-account lookup, `LOAN` liability sign & transfer neutrality, payday-morning guard, unclamped net inflow cycles, KPI aggregation) and Node unit tests for `historic-spent.js` hash/tab switching.
- Syncing `public/*` → `modules/monmon-rs/public/` and running `cargo test` + Node unit tests.

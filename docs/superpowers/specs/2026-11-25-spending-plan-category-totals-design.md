# Spending Plan Category Section Totals — Design

**Date:** 2026-11-25
**Status:** Approved

## Intent & Why

When creating, editing, or reading a spending plan on `/myMonthlyPot` (and anywhere else a spending plan is displayed in MonMon), the user needs to see the total of all category sections compared against their current **max monthly allowance** (`surplus` from SPOG). Without a running total and comparison, users have to sum category amounts mentally to know how much unallocated allowance remains or whether they have exceeded their monthly allowance.

## Goals

- Display a **static total** of category sections and its comparison to the current max monthly allowance everywhere spending plans are visible:
  1. `/myMonthlyPot` — Active Monthly Pot spending plan summary table.
  2. `/myMonthlyPot` — Saved Spending Plan Presets list.
  3. `/myMonthlyPot` — Previous Pot Configs (`archived_pots`) when an archived pot has a spending plan.
  4. `/spog` — Mini Spending Plan card.
  5. `/myPlans` — Monthly Pot & Spending Plan card.
  *(Note: `/savingsPlan` already sums and displays `Monthly Pot Budget: £X/mo` alongside `MAX / mo`.)*
- Display a **dynamic (live) total** and allowance comparison while creating or editing a spending plan on `/myMonthlyPot` (updating on amount input, section add/remove, and preset selection, including the `Gruelling` preset).
- Treat exceeding the max monthly allowance as a **visual warning only** (red highlight showing `Over max allowance by £X.XX`) — never block saving a plan or preset.

## Non-Goals & Anti-Goals

- Do not change how `surplus` / max monthly allowance is calculated in `calculate_spog`.
- Do not change how SPOG calculates daily pace, `remaining_essentials`, or `adjusted_fun`.
- Do not block saving a pot or preset when planned categories exceed the current allowance.
- Do not introduce CoffeeScript or external frontend frameworks.

## Constraints

- **Plain JavaScript only** (per `AGENTS.md`): implement `/myMonthlyPot` interactive logic in `modules/monmon-rs/public/javascripts/monthly-pot.js` and mirror it to `public/javascripts/monthly-pot.js`.
- **Exact money math**: compute all server-side totals in Rust with `crate::money::Pence` and client-side live totals in integer pence to prevent floating-point drift (e.g. `£0.10 + £0.20 = £0.30`).
- **Offline/SSR compatibility**: static totals must be rendered server-side in Askama templates so read-only / cached offline views display accurate totals without requiring JS execution.
- **Formatting**: display all monetary figures to two decimal places (`£X.XX`).

## Approaches

1. **Hybrid Server `Pence` + Plain JS Live Editor (Chosen):**
   Compute static totals and allowance comparisons server-side in Rust (`pot.rs`, `spog.rs`, `plans.rs`) using `Pence` and render them directly in Askama templates (`pots.html`, `spog.html`, `plans.html`). Compute the dynamic editor total client-side in plain JS (`monthly-pot.js`) using integer pence as the user types, adds/removes sections, or switches presets.
   - *Trade-off:* Small calculation helper exists in both Rust and JS, kept trivial (~15 lines) and pinned by unit tests on both sides.
   - *Why chosen:* Static views work identically in SSR and offline mode, while editing gives zero-latency live feedback.

2. **Client-Side JS Only (Rejected):**
   Would require JS to compute static totals across `/myMonthlyPot`, `/spog`, and `/myPlans`, breaking pure-SSR rendering, risking FOUC, and duplicating DOM scraping logic across three pages.

3. **Server-Side Round-Trips While Editing (Rejected):**
   Cannot provide instant per-keystroke feedback when typing category amounts and fails when offline.

## Decided UX & Copy

- **Comparison figures:**
  - `Planned total` = sum of all category section amounts (clamping negative/invalid inputs to `£0.00`).
  - `Max monthly allowance` = current SPOG `surplus`.
  - `Difference` = `max_allowance - planned_total`.
  - Under or equal (`difference >= 0`): green indicator showing remaining amount (e.g. `Remaining for fun: £210.00` in Active Pot table, `£210.00 left` in compact cards, `Left unallocated: £210.00` in editor).
  - Over (`difference < 0`): red warning showing `Over max allowance` / `Over by £X.XX` (still allows saving).
- **Live editor layout (`/myMonthlyPot`):**
  - Placed directly beneath the Category Sections list inside `#custom-plan-fields`:
    - `Planned categories: £X.XX` | `Max allowance: £Y.YY`
    - Status line: `Left unallocated: £Z.ZZ` (green) or `Over max allowance by £Z.ZZ` (red warning).
  - When the built-in `Gruelling` preset (£216.67/mo) is selected, show a compact allowance comparison line as well.

## Left to the Builder

- Exact CSS spacing, icons, and responsive wrapping consistent with existing `pots.html`, `spog.html`, and `plans.html` cards and CSS variables (`--primary`, `--success`, `--danger`, `--text-muted`, `--border-color`, `--card-bg-subtle`).
- Unit tests (Rust `cargo test` + Node unit tests for `monthly-pot.js`) and integration/E2E verification.

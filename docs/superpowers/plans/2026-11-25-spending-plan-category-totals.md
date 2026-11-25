# Spending Plan Category Section Totals Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show exact category section totals compared against the current max monthly allowance statically everywhere spending plans are visible (`/myMonthlyPot`, `/spog`, `/myPlans`) and dynamically while creating or editing a plan on `/myMonthlyPot`.

**Architecture:** Introduce a shared `AllowanceComparison` value type and `Pence`-based section-totalling helpers in `modules/monmon-rs/src/pot.rs`, reused by `pot.rs`, `spog.rs`, and `plans.rs` to render static totals server-side in Askama templates (`pots.html`, `spog.html`, `plans.html`). Extract `/myMonthlyPot`'s editor script into plain JS (`monthly-pot.js`, mirrored in `modules/monmon-rs/public/javascripts/` and `public/javascripts/`) with a pure integer-pence `calculateSectionsSummary` helper driving the live total bar.

**Tech Stack:** Rust (`axum`, `askama`, `sqlx`, `serde`), `crate::money::Pence`, plain JavaScript (ES5/ES6, no CoffeeScript), Node built-in test runner (`node --test`), `cargo test`.

**Spec:** `docs/superpowers/specs/2026-11-25-spending-plan-category-totals-design.md`

## Global Constraints

- Plain JavaScript only (`.js`); never use CoffeeScript.
- Mirror new static JS asset in both `modules/monmon-rs/public/javascripts/monthly-pot.js` and `public/javascripts/monthly-pot.js`.
- All server-side totals use `crate::money::Pence`; client-side live totals use integer pence (`Math.round(val * 100)`).
- Format all displayed money amounts to 2 decimal places (`£X.XX`).
- Going over the max monthly allowance is a visual warning only — never disable or block form submission.
- Commit messages must be prefixed with `[agy]`. Do not modify `NEXT_PROMPT_AGY.md`, `NEXT_PROMPT_AGY_UX.md`, or unrelated dirty files (e.g. `bank-sync.js`).

## Review Focus

1. **Exact match (`planned_total == max_allowance`)**: must report `is_over_allowance == false`, `difference == £0.00`, no negative zero, and green/neutral styling (pinned in Tasks 1 & 2).
2. **Zero or negative `max_allowance` (`surplus <= 0`)**: e.g., outgoings exceed income (`surplus = -£50.00`) with `£100.00` planned must report `is_over_allowance == true`, `absolute_difference == £150.00` (pinned in Tasks 1 & 2).
3. **Fractional pence / IEEE-754 precision (`0.10 + 0.20`, `216.67 + 33.33`)**: exact integer-pence arithmetic in both Rust and JS so totals format cleanly as `£0.30` / `£250.00` (pinned in Tasks 1 & 2).
4. **Empty, non-numeric, or negative section inputs while editing**: `""`, `"abc"`, `null`, `NaN`, or `-25` must be treated as `£0.00` without producing `NaN` or reducing the total (pinned in Tasks 1 & 2).
5. **Legacy pots without `sections_json` and malformed `sections_json` in saved presets**: fall back to `spending_plan_food`/`fuel`/`transport` when `sections_json` is `None`/empty, and return `Pence::ZERO` on invalid JSON without crashing page render (pinned in Task 1).

---

### Task 1: Shared Rust `AllowanceComparison` & Section Totalling Helpers (`pot.rs`)

**Files:**
- Modify: `modules/monmon-rs/src/pot.rs:13-68,361-588,954-1038`
- Test: `modules/monmon-rs/src/pot.rs` (`#[cfg(test)] mod tests`)

**Interfaces:**
- Consumes: `crate::money::Pence`
- Produces:
  - `pub struct AllowanceComparison { pub planned_total: Pence, pub max_allowance: Pence, pub difference: Pence, pub is_over_allowance: bool, pub absolute_difference: Pence }`
  - `pub fn parse_sections_json(sections_json: Option<&str>) -> Vec<(String, Pence, Option<i64>)>`
  - `pub fn parse_sections_total_pence(sections_json: Option<&str>, legacy_food: Option<f32>, legacy_fuel: Option<f32>, legacy_transport: Option<f32>) -> Pence`
  - `pub fn compare_to_allowance(planned_total: Pence, max_allowance: Pence) -> AllowanceComparison`
  - Updated DTOs in `pot.rs`:
    - `SpendingPlanSectionDto.amount: Pence`
    - `PotDto.sections_total: Pence`, `PotDto.allowance_comparison: AllowanceComparison`
    - `CustomPlanDto.sections_count: usize`, `CustomPlanDto.sections_total: Pence`, `CustomPlanDto.allowance_comparison: AllowanceComparison`
    - `MonthlyPotTemplate.surplus: Pence`

- [ ] **Step 1: Write failing unit tests in `modules/monmon-rs/src/pot.rs`**

Add unit tests inside `mod tests`:
```rust
#[test]
fn test_compare_to_allowance_under_exact_over_and_negative_surplus() {
    let under = compare_to_allowance(Pence(29000), Pence(50000));
    assert!(!under.is_over_allowance);
    assert_eq!(under.difference, Pence(21000));
    assert_eq!(under.absolute_difference, Pence(21000));

    let exact = compare_to_allowance(Pence(50000), Pence(50000));
    assert!(!exact.is_over_allowance);
    assert_eq!(exact.difference, Pence::ZERO);
    assert_eq!(exact.absolute_difference, Pence::ZERO);

    let over = compare_to_allowance(Pence(62550), Pence(50000));
    assert!(over.is_over_allowance);
    assert_eq!(over.difference, Pence(-12550));
    assert_eq!(over.absolute_difference, Pence(12550));

    let neg_surplus = compare_to_allowance(Pence(10000), Pence(-5000));
    assert!(neg_surplus.is_over_allowance);
    assert_eq!(neg_surplus.difference, Pence(-15000));
    assert_eq!(neg_surplus.absolute_difference, Pence(15000));
}

#[test]
fn test_parse_sections_total_pence_precision_clamping_fallback_and_malformed() {
    // Fractional pence precision (0.10 + 0.20 + 216.67 + 33.33 = 250.30)
    let json = r#"[
        {"heading":"A","amount":0.10},
        {"heading":"B","amount":0.20},
        {"heading":"C","amount":216.67},
        {"heading":"D","amount":"33.33"}
    ]"#;
    assert_eq!(parse_sections_total_pence(Some(json), None, None, None), Pence(25030));

    // Negative / invalid amounts clamped to 0
    let weird = r#"[{"heading":"Neg","amount":-25},{"heading":"Bad","amount":"nope"},{"heading":"Ok","amount":40.5}]"#;
    assert_eq!(parse_sections_total_pence(Some(weird), None, None, None), Pence(4050));

    // Legacy fallback when sections_json is None or empty array
    assert_eq!(
        parse_sections_total_pence(None, Some(200.0), Some(50.0), Some(40.25)),
        Pence(29025)
    );
    assert_eq!(
        parse_sections_total_pence(Some("[]"), Some(216.67), Some(0.0), None),
        Pence(21667)
    );

    // Malformed JSON without legacy columns safely returns 0
    assert_eq!(parse_sections_total_pence(Some("{not-valid"), None, None, None), Pence::ZERO);
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cargo test --manifest-path modules/monmon-rs/Cargo.toml pot::tests`
Expected: FAIL — `compare_to_allowance` and `parse_sections_total_pence` not found.

- [ ] **Step 3: Implement `AllowanceComparison`, helpers, and DTO updates in `modules/monmon-rs/src/pot.rs`**

1. Import `crate::money::Pence`.
2. Define `AllowanceComparison` (`#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]`, `#[serde(rename_all = "camelCase")]`).
3. Implement `parse_sections_json(sections_json: Option<&str>) -> Vec<(String, Pence, Option<i64>)>`:
   - Parses JSON array; for each item extracts `heading`, parses `amount` from JSON number (`as_f64()`) or numeric string (`as_str().and_then(|s| s.trim().parse::<f64>().ok())`), filters `is_finite()`, clamps `< 0.0` to `0.0`, converts via `Pence::from_pounds_f64`, and extracts optional `accountId`.
   - Returns empty `Vec` on `None` or parse error.
4. Implement `parse_sections_total_pence(sections_json: Option<&str>, legacy_food: Option<f32>, legacy_fuel: Option<f32>, legacy_transport: Option<f32>) -> Pence`:
   - Uses `parse_sections_json`; if non-empty, returns sum of section `Pence` values.
   - Otherwise sums positive finite legacy `food`, `fuel`, `transport` values converted via `Pence::from_pounds_f32`.
5. Implement `compare_to_allowance(planned_total: Pence, max_allowance: Pence) -> AllowanceComparison`.
6. In `get_monthly_pot_page`:
   - Compute `spog_metrics` **before** building `PotDto` and `CustomPlanDto` (currently `spog_metrics` is computed after — move it above pot/preset DTO assembly so `surplus_pence = spog_metrics.surplus` is available for every pot and saved preset).
   - Populate `sections` using `parse_sections_json` (with legacy fallback when empty), compute `sections_total: Pence = sections.iter().map(|s| s.amount).sum()`, and `allowance_comparison = compare_to_allowance(sections_total, surplus_pence)`.
   - Populate `CustomPlanDto` with `sections_count`, `sections_total`, and `allowance_comparison`.
   - Set `MonthlyPotTemplate.surplus = surplus_pence` (type `Pence`).

- [ ] **Step 4: Run `cargo test` to verify compilation and tests pass**

Run: `cargo test --manifest-path modules/monmon-rs/Cargo.toml pot::tests`
Expected: PASS (all existing and new `pot::tests` pass).

- [ ] **Step 5: Commit**

```bash
git add modules/monmon-rs/src/pot.rs
git commit -m "[agy] Add AllowanceComparison and Pence section total helpers to pot.rs"
```

---

### Task 2: Plain JS Live Calculator & `/myMonthlyPot` Template (`monthly-pot.js` + `pots.html`)

**Files:**
- Create: `modules/monmon-rs/public/javascripts/monthly-pot.js`
- Create: `public/javascripts/monthly-pot.js` (exact copy)
- Modify: `modules/monmon-rs/templates/pots.html`
- Create: `modules/tests-contract/monthly-pot.unit.test.mjs`

**Interfaces:**
- Consumes:
  - From Task 1 (`MonthlyPotTemplate`, `PotDto`, `CustomPlanDto`, `AllowanceComparison`): `surplus` (`Pence`, `surplus.as_pence()`), `p.sections_total`, `p.allowance_comparison`, `sp.sections_count`, `sp.sections_total`, `sp.allowance_comparison`.
- Produces:
  - `window.MonMonMonthlyPot.calculateSectionsSummary(sections, maxAllowancePence)` returning `{ plannedPence, maxAllowancePence, diffPence, isOver, absDiffPence, plannedFormatted, maxFormatted, absDiffFormatted }`
  - `window.MonMonMonthlyPot.GRUELLING_TOTAL_PENCE` (`21667`)
  - DOM hooks in `pots.html`:
    - Active pot: `#active-plan-sections-total`, `#active-plan-allowance-comparison`
    - Saved presets: `.preset-total-summary`
    - Archived pots: `.archived-plan-total`
    - Live editor: `#plan-live-summary`, `#live-sections-total`, `#live-max-allowance`, `#live-allowance-status`, `#gruelling-allowance-summary`
    - Form attributes: `#pot-config-form[data-max-allowance-pence]`

- [ ] **Step 1: Write failing Node unit tests in `modules/tests-contract/monthly-pot.unit.test.mjs`**

Test `calculateSectionsSummary` and DOM live updates via `node:vm` loading `modules/monmon-rs/public/javascripts/monthly-pot.js`:
```js
import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';

// Verify:
// 1. calculateSectionsSummary([{ amount: 200 }, { amount: 50 }, { amount: 40 }], 50000)
//    -> plannedPence === 29000, diffPence === 21000, isOver === false, plannedFormatted === '290.00', absDiffFormatted === '210.00'
// 2. Exact match: [{ amount: 500 }], 50000
//    -> isOver === false, diffPence === 0, absDiffFormatted === '0.00'
// 3. Over allowance: [{ amount: 300 }, { amount: 250.5 }], 50000
//    -> plannedPence === 55050, isOver === true, diffPence === -5050, absDiffFormatted === '50.50'
// 4. Negative maxAllowancePence: [{ amount: 100 }], -5000
//    -> isOver === true, diffPence === -15000, absDiffFormatted === '150.00'
// 5. Fractional pence precision: [{ amount: 0.1 }, { amount: 0.2 }, { amount: 216.67 }, { amount: 33.33 }], 30000
//    -> plannedPence === 25030, plannedFormatted === '250.30'
// 6. Empty / invalid / negative amounts: [{ amount: '' }, { amount: 'abc' }, { amount: -25 }, { amount: null }, { amount: 40.5 }], 10000
//    -> plannedPence === 4050, isOver === false, absDiffFormatted === '59.50'
// 7. Empty sections array: [], 50000 -> plannedPence === 0, isOver === false, absDiffFormatted === '500.00'
// 8. Mirror parity check: modules/monmon-rs/public/javascripts/monthly-pot.js === public/javascripts/monthly-pot.js
```

- [ ] **Step 2: Run test to verify it fails**

Run: `node --test modules/tests-contract/monthly-pot.unit.test.mjs`
Expected: FAIL — `ENOENT` for `monthly-pot.js`.

- [ ] **Step 3: Create `modules/monmon-rs/public/javascripts/monthly-pot.js` and mirror to `public/javascripts/monthly-pot.js`**

1. Implement pure helpers:
   - `toPence(raw)` — parses numeric or string values safely, returns `0` for non-finite / `NaN` / `<= 0`, else `Math.round(num * 100)`.
   - `formatPence(pence)` — formats `Math.abs(pence) / 100` (with leading `-` only if negative) to 2 decimal places (`(pence / 100).toFixed(2)`, avoiding `"-0.00"`).
   - `calculateSectionsSummary(sections, maxAllowancePence)` — sums `toPence(sec.amount)` across `sections`, computes `diffPence = maxAllowancePence - plannedPence`, `isOver = diffPence < 0`, `absDiffPence = Math.abs(diffPence)`, returns `{ plannedPence, maxAllowancePence, diffPence, isOver, absDiffPence, plannedFormatted, maxFormatted, absDiffFormatted }`.
2. Expose `window.MonMonMonthlyPot = { GRUELLING_TOTAL_PENCE: 21667, toPence, formatPence, calculateSectionsSummary }`.
3. Migrate all existing interactive behavior from `pots.html`'s inline `<script>` into an `initMonthlyPotPage()` function guarded on `#pot-config-form`:
   - Preserves `autoSelectNominatedAccounts()`, preset selection (`none`, `gruelling`, `custom`, `saved_*`), `Add Section`, `Save Preset`, `Delete Preset` (including offline guard and confirm), and `.btn-edit-preset` card buttons.
   - Reads account options from `<template id="pot-section-account-options">` (or falls back to empty).
   - Sets `.sec-heading-inp` value via `.value` property (safe against quotes) and `.sec-amount-inp` via `.value`.
   - Implements `updateLiveSummary()`: reads `data-max-allowance-pence` from `#pot-config-form`, computes `calculateSectionsSummary(sectionsState, maxAllowancePence)`, updates `#live-sections-total` (`£X.XX`), `#live-max-allowance` (`£Y.YY`), and `#live-allowance-status` (green `Left unallocated: £Z.ZZ` or red warning `Over max allowance by £Z.ZZ` without disabling submit). Also toggles `#gruelling-allowance-summary` with `calculateSectionsSummary([{ amount: 216.67 }], maxAllowancePence)` when `presetSelect.value === 'gruelling'`.
   - Calls `updateLiveSummary()` on every amount `input`, section add, section delete, preset `change`, and initial load.
4. Copy identical file to `public/javascripts/monthly-pot.js`.

- [ ] **Step 4: Update `modules/monmon-rs/templates/pots.html`**

1. **Active Monthly Pot spending plan table**:
   - After `{% for sec in p.sections %}`, add:
     - `Total planned categories` -> `<td id="active-plan-sections-total">£{{ p.sections_total }}</td>`
     - Comparison row (`id="active-plan-allowance-comparison"`): if `p.allowance_comparison.is_over_allowance`, red `Over max allowance` / `−£{{ p.allowance_comparison.absolute_difference }}`; else green `Remaining for fun` / `£{{ p.allowance_comparison.difference }}`.
2. **Saved Spending Plan Presets card**:
   - Under `{{ sp.name }}`, add `<div class="preset-total-summary">` displaying `{{ sp.sections_count }} section{% if sp.sections_count != 1 %}s{% endif %} · £{{ sp.sections_total }} / £{{ surplus }} max` plus green `£{{ sp.allowance_comparison.difference }} left` or red `Over by £{{ sp.allowance_comparison.absolute_difference }}`.
3. **Previous Pot Configs (`archived_pots`)**:
   - If `let Some(sp_name) = p.spending_plan_name`, add `<div class="archived-plan-total">` displaying `Plan: {{ sp_name }} · £{{ p.sections_total }} / £{{ surplus }} max` plus green `(£{{ p.allowance_comparison.difference }} left)` or red `(Over by £{{ p.allowance_comparison.absolute_difference }})`.
4. **Configure Monthly Pot form (`#pot-config-form`)**:
   - Add `data-max-allowance-pence="{{ surplus.as_pence() }}"` to `#pot-config-form`.
   - Add `<template id="pot-section-account-options">` containing `<option value="0">-- No Account --</option>` and each account `<option value="{{ a.id }}">{{ a.name }}</option>`.
   - Add `#gruelling-allowance-summary` (hidden by default) right under `#pot-preset` for when `Gruelling` is selected.
   - Inside `#custom-plan-fields`, directly below `#sections-container`, add `#plan-live-summary` showing:
     - `Planned categories:` `<span id="live-sections-total">£0.00</span>`
     - `Max allowance:` `<span id="live-max-allowance">£{{ surplus }}</span>`
     - `<div id="live-allowance-status"></div>`
   - Replace the inline `<script>...</script>` with `<script src="/assets/javascripts/monthly-pot.{{ crate::BUILD_HASH }}.js" type="text/javascript"></script>`.

- [ ] **Step 5: Run tests to verify both JS unit tests and Rust Askama template compilation pass**

Run: `node --test modules/tests-contract/monthly-pot.unit.test.mjs && cargo test --manifest-path modules/monmon-rs/Cargo.toml pot::tests`
Expected: PASS for both suites.

- [ ] **Step 6: Commit**

```bash
git add modules/monmon-rs/public/javascripts/monthly-pot.js public/javascripts/monthly-pot.js modules/monmon-rs/templates/pots.html modules/tests-contract/monthly-pot.unit.test.mjs
git commit -m "[agy] Add static and dynamic category section totals on /myMonthlyPot"
```

---

### Task 3: Static Spending Plan Totals on `/spog` and `/myPlans` (`spog.rs`, `spog.html`, `plans.rs`, `plans.html`)

**Files:**
- Modify: `modules/monmon-rs/src/spog.rs:18-34,1018-1045`
- Modify: `modules/monmon-rs/templates/spog.html:302-335`
- Modify: `modules/monmon-rs/src/plans.rs:32-40,134-180`
- Modify: `modules/monmon-rs/templates/plans.html:72-86`

**Interfaces:**
- Consumes: `crate::pot::{AllowanceComparison, compare_to_allowance, parse_sections_total_pence}`, `crate::spog::calculate_spog`
- Produces:
  - `SpogSpendingPlanDto.planned_sections_total: Pence`, `SpogSpendingPlanDto.allowance_comparison: AllowanceComparison`
  - `PotSummaryDto.planned_sections_total: Pence`, `PotSummaryDto.allowance_comparison: AllowanceComparison`
  - DOM hooks: `#spog-plan-sections-total` in `spog.html`, `#plans-hub-spending-total` in `plans.html`

- [ ] **Step 1: Write failing unit tests in `modules/monmon-rs/src/spog.rs` (and verify `plans.rs`)**

Add a unit test in `spog.rs` (`mod tests`) verifying `SpogSpendingPlanDto` serialization includes `plannedSectionsTotal` and `allowanceComparison` and matches `compare_to_allowance(sum(planned_amount), max_monthly_allowance)`.

- [ ] **Step 2: Run test to verify it fails**

Run: `cargo test --manifest-path modules/monmon-rs/Cargo.toml spog::tests::test_spog_spending_plan_dto_totals`
Expected: FAIL — fields `planned_sections_total` and `allowance_comparison` do not exist on `SpogSpendingPlanDto`.

- [ ] **Step 3: Implement static totals in `spog.rs`, `spog.html`, `plans.rs`, and `plans.html`**

1. In `modules/monmon-rs/src/spog.rs`:
   - Add `pub planned_sections_total: Pence` and `pub allowance_comparison: crate::pot::AllowanceComparison` to `SpogSpendingPlanDto`.
   - Compute `let planned_sections_total: Pence = sections.iter().map(|s| s.planned_amount).sum();` and `let allowance_comparison = crate::pot::compare_to_allowance(planned_sections_total, surplus);`.
2. In `modules/monmon-rs/templates/spog.html`:
   - In the `.spog-mini-spending-plan` table, right after `{% for sec in sp.sections %}`, add a row with `id="spog-plan-sections-total"`:
     - Label: `Planned categories total`
     - Value: `£{{ sp.planned_sections_total }} / £{{ sp.max_monthly_allowance }}` plus compact inline status (`£{{ sp.allowance_comparison.difference }} left` in muted/green, or `Over by £{{ sp.allowance_comparison.absolute_difference }}` in red).
3. In `modules/monmon-rs/src/plans.rs`:
   - Extend `PotSummaryDto` with `pub planned_sections_total: Pence` and `pub allowance_comparison: crate::pot::AllowanceComparison`.
   - Update the active pot query in `get_plans_page` to also select `spending_plan_sections_json, spending_plan_food, spending_plan_fuel, spending_plan_transport`.
   - Fetch `max_allowance = crate::spog::calculate_spog(&state.pool, &user_id).await.map(|m| m.surplus).unwrap_or(Pence::ZERO)`.
   - Compute `planned_sections_total = crate::pot::parse_sections_total_pence(...)` and `allowance_comparison = crate::pot::compare_to_allowance(planned_sections_total, max_allowance)`.
4. In `modules/monmon-rs/templates/plans.html`:
   - In Card 2 (`Monthly Pot & Spending Plan`), when `pot.spending_plan_name.is_some()`, render `<div id="plans-hub-spending-total">` showing `Planned: £{{ pot.planned_sections_total }} / £{{ pot.allowance_comparison.max_allowance }} max` with green `(£{{ pot.allowance_comparison.difference }} left)` or red `(Over by £{{ pot.allowance_comparison.absolute_difference }})`.

- [ ] **Step 4: Run full Rust test suite and Node unit tests**

Run: `cargo test --manifest-path modules/monmon-rs/Cargo.toml && node --test modules/tests-contract/monthly-pot.unit.test.mjs`
Expected: PASS (0 failures, clean build including all Askama templates).

- [ ] **Step 5: Commit**

```bash
git add modules/monmon-rs/src/spog.rs modules/monmon-rs/templates/spog.html modules/monmon-rs/src/plans.rs modules/monmon-rs/templates/plans.html
git commit -m "[agy] Show static spending plan category totals on /spog and /myPlans"
```

---

### Task 4: Final Verification

**Files:**
- Verify: all modified files (`git diff --stat`, `git status`)

- [ ] **Step 1: Verify Rust unit tests, clippy/check, and Node unit tests**

Run: `cargo test --manifest-path modules/monmon-rs/Cargo.toml && node --test modules/tests-contract/monthly-pot.unit.test.mjs && diff -u modules/monmon-rs/public/javascripts/monthly-pot.js public/javascripts/monthly-pot.js`
Expected: All tests pass, zero diff between mirrored JS files.

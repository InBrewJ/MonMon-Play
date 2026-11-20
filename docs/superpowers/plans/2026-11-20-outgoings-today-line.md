# Outgoings Today Horizontal Line Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a horizontal "Today" divider line directly above items due today (or between past and upcoming payments) in the Outgoings desktop table and mobile stacked cards view.

**Architecture:** Pass the server's effective `today_day`, `today_month`, and formatted `today_label` (e.g. `"20 Nov — Day 20"`) from `management.rs` into `OutgoingsTemplate` (`outgoings.html`), add `data-effective-day` to each row and card, and update `filterTab()` in `outgoings.html` (and `public/javascripts/outgoings.js`) to position a dedicated `#today-divider-row` (`<tr>`) and `#today-divider-card` (`<div>`) directly above the first visible item on or after today.

**Tech Stack:** Rust (Axum, Askama templates), Plain JavaScript, CSS3, Playwright E2E.

**Spec:** `docs/superpowers/specs/2026-11-20-outgoings-today-line-design.md`

## Global Constraints

- Use Plain JavaScript (`.js`) only; no CoffeeScript.
- Maintain both desktop table view and mobile Stacked Action Card view parity.
- Respect Time Travel (`get_effective_date(&headers)`).

## Review Focus

- Empty outgoings list or a tab with 0 visible items: the Today divider must be hidden (`display: none`) rather than appearing inside an empty table.
- All visible monthly outgoings have `effective_day < today_day`: the Today divider must appear at the bottom of the monthly items (after the last past outgoing, and before any trailing non-monthly yearly rows in the `all` tab).
- All visible monthly outgoings have `effective_day >= today_day`: the Today divider must appear at the very top of the table body, directly above the first row.
- Multiple items due today (`effective_day == today_day`): the Today divider must appear once, directly above the *first* item due today.
- Yearly Bills tab (`tab === 'yearly'`): items are sorted by `(outgoing_month, outgoing_day)`, so the divider must compare `(month, day) >= (today_month, today_day)`.

---

### Task 1: Expose `today_day`, `today_month`, and `today_label` in `OutgoingsTemplate`

**Files:**
- Modify: `modules/monmon-rs/src/management.rs`

**Interfaces:**
- Produces:
  - `pub fn format_today_divider_label(today: chrono::NaiveDate) -> String`
  - `OutgoingsTemplate` fields: `pub today_day: u32`, `pub today_month: u32`, `pub today_label: String`

- [ ] **Step 1: Write the failing unit test in `modules/monmon-rs/src/management.rs`**

```rust
    #[test]
    fn test_format_today_divider_label() {
        let date = chrono::NaiveDate::from_ymd_opt(2026, 11, 20).unwrap();
        assert_eq!(format_today_divider_label(date), "Today — 20 Nov (Day 20)");
    }
```

- [ ] **Step 2: Run cargo test to verify it fails**

Run: `cargo test --manifest-path modules/monmon-rs/Cargo.toml test_format_today_divider_label`
Expected: FAIL with `cannot find function format_today_divider_label`

- [ ] **Step 3: Implement `format_today_divider_label` and add `today_day`, `today_month`, `today_label` to `OutgoingsTemplate` in `modules/monmon-rs/src/management.rs` and `modules/monmon-rs/templates/outgoings.html`**

- [ ] **Step 4: Run cargo test to verify it passes**

Run: `cargo test --manifest-path modules/monmon-rs/Cargo.toml test_format_today_divider_label`
Expected: PASS

---

### Task 2: Render and Position the Today Horizontal Divider in Desktop Table & Mobile Cards

**Files:**
- Modify: `modules/monmon-rs/templates/outgoings.html`
- Modify: `public/javascripts/outgoings.js`
- Modify: `modules/tests-e2e/specs/authenticated-outgoings.spec.js`

**Interfaces:**
- Consumes: `today_day`, `today_month`, `today_label` from `OutgoingsTemplate`
- Produces: `#today-divider-row` (`<tr class="today-divider-row">`) in `#outgoings-table tbody`, `#today-divider-card` (`<div class="today-divider-mobile">`) in `.mobile-cards-view`, positioned by `positionTodayDivider(tab)` on load and tab switch.

- [ ] **Step 1: Add CSS, HTML divider elements, `data-effective-day` attributes, and `positionTodayDivider(tab)` in `modules/monmon-rs/templates/outgoings.html` and `public/javascripts/outgoings.js`**

- [ ] **Step 2: Add E2E assertion in `modules/tests-e2e/specs/authenticated-outgoings.spec.js` and unit test in `modules/monmon-rs/src/management.rs` verifying divider rendering and placement**

- [ ] **Step 3: Run cargo tests to verify compilation and unit tests pass**

Run: `cargo test --manifest-path modules/monmon-rs/Cargo.toml`
Expected: PASS

- [ ] **Step 4: Commit changes**

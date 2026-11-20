# Outgoings "Today" Horizontal Divider Line — Design Spec

## Intent and Why
On the Outgoings page (`/myOutgoings`), items due today already receive an amber highlight (`monthly-due-today`). However, when scrolling down the chronologically ordered list of outgoings—especially on days when no item is due on today's exact date—it takes mental effort to find where today sits in the month. Adding a horizontal "Today" line directly above any items due today (or between the last payment that already went out and the next upcoming payment) allows the user to gauge at a glance which payments have already gone out and which are due today or soon to go out.

## Goals & Non-Goals
- **Goals:**
  - Display a clear horizontal "Today" marker line in the desktop Outgoings table (`#outgoings-table`) and in the mobile Stacked Action Cards view (`.mobile-cards-view`).
  - Position the line **above** any items due today (`effective_day >= today.day()`), or after all monthly items if every item's day has already passed (`effective_day < today.day()`).
  - Respect Time Travel (`crate::time_travel::get_effective_date(&headers)`) by driving `today_day`, `today_month`, and `today_label` from the backend's effective `today` date.
  - Handle tab switching cleanly across `Monthly and due now` (`active`), `Yearly Bills` (`yearly`), `Paused` (`paused`), and `All Outgoings` (`all`).
- **Non-Goals:**
  - Changing the existing `.monthly-due-today` row highlight or `DUE TODAY` badge.
  - Modifying database schemas.

## Approaches Considered
1. **Dynamic JS Divider Row & Mobile Divider seeded with Server Effective Date (Selected):**
   - Pass `today_day: u32`, `today_month: u32`, and `today_label: String` (e.g. `"Today — 20 Nov (Day 20)"`) from `management.rs` into `OutgoingsTemplate`.
   - Render `data-effective-day="{{ o.effective_day }}"` on each desktop `<tr>` and mobile `.item-card`.
   - In `filterTab(tab)`, dynamically position a `<tr id="today-divider-row" class="today-divider-row">` in `#outgoings-table tbody` and `<div id="today-divider-card" class="today-divider-mobile">` in `.mobile-cards-view` right before the first visible row/card that is due today or in the future (or at the bottom of the visible monthly items if all visible items have `effective_day < today_day`; hidden if there are 0 visible items).
   - On the `Yearly Bills` tab (where items are sorted by `outgoing_month, outgoing_day`), compare `(month, day) >= (today_month, today_day)` so the "Today" line also works naturally when browsing yearly bills.
2. **Static Server-Only Row in Askama Loop:**
   - Rejected because the 4 tabs filter the same `<tbody>` in-place via JavaScript, which could leave a static divider stranded next to hidden rows.

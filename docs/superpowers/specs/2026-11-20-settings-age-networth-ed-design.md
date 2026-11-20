# User Age in Settings & UK Pension Access Law in Net Worth Ed — Design Spec

## Intent and Why
On the Net Worth page (`/netWorth`), Ed's AI advisor ("Ed Says") evaluates the user's combined investable portfolio (Cash + Pensions) and estimates whether they can retire. Without knowing the user's age, Ed treats pension pots as immediately accessible and tells users under 57 that they can retire today—even though UK pension legislation locks private and workplace pensions until the Normal Minimum Pension Age (rising to **57** from April 2028; 55 prior). Adding an **Age** setting in `/settings` and feeding it into Net Worth Ed's prompt ensures Ed distinguishes between locked pension funds (accessible only from age 57+) and liquid cash/savings that must bridge any early retirement years before age 57.

## Goals & Non-Goals
- **Goals:**
  - Add an `age INTEGER` column to `user_access` via `run_schema_migrations` in `modules/monmon-rs/src/db.rs`.
  - Allow viewing and updating `age` on `/settings` (`modules/monmon-rs/templates/settings.html` and `modules/monmon-rs/src/admin.rs`).
  - Clear Ed's cached advice (`crate::ed::clear_ed_cache(&user_id)`) whenever `/settings` is saved so any updated age takes effect immediately.
  - Update `build_networth_system_prompt()` and `build_networth_context_prompt(&nw, user_age)` in `modules/monmon-rs/src/ed.rs` so Ed knows:
    - The user's current age (if configured) and how many years remain until UK minimum pension access age (**57**).
    - The split between **Immediately Accessible Liquid Cash & Savings** (`£{nw.cash_total}`) and **Locked Pensions (inaccessible until age 57 under UK law)** (`£{nw.pension_total}`).
    - That a user under 57 **cannot** draw from their pension today, so retiring before 57 requires a liquid non-pension bridge covering living expenses for `57 - age` years.
- **Non-Goals:**
  - Requiring age on initial sign-up (remains an optional field in `/settings`).

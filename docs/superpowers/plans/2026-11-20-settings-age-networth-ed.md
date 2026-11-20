# User Age in Settings & Net Worth Ed UK Pension Law Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a user `age` setting on `/settings` and incorporate the user's age and the UK minimum pension access age (57) into Net Worth "Ed Says" advice.

**Architecture:** Add `ALTER TABLE user_access ADD COLUMN IF NOT EXISTS age INTEGER` in `db.rs`, extend `SettingsTemplate` and `SettingsForm` in `admin.rs` / `settings.html` to read and update `age` (invalidating Ed's cache on save), and update `build_networth_system_prompt` and `build_networth_context_prompt(&nw, user_age)` in `ed.rs` to pass the user's age, years until 57, and locked-vs-accessible portfolio breakdown to Mistral.

**Tech Stack:** Rust (Axum, SQLx, Askama), PostgreSQL, Mistral AI prompt engineering.

**Spec:** `docs/superpowers/specs/2026-11-20-settings-age-networth-ed-design.md`

---

### Task 1: Add `age` to `db.rs`, `admin.rs`, and `settings.html`

**Files:**
- Modify: `modules/monmon-rs/src/db.rs`
- Modify: `modules/monmon-rs/src/admin.rs`
- Modify: `modules/monmon-rs/templates/settings.html`

- [ ] **Step 1: Add `parse_age_input` helper and unit test in `modules/monmon-rs/src/admin.rs`**
- [ ] **Step 2: Add `ALTER TABLE user_access ADD COLUMN IF NOT EXISTS age INTEGER` in `modules/monmon-rs/src/db.rs`**
- [ ] **Step 3: Load and save `age` in `get_settings` / `post_settings` in `modules/monmon-rs/src/admin.rs`, call `crate::ed::clear_ed_cache(&user_id)`, and render the age input in `modules/monmon-rs/templates/settings.html`**

---

### Task 2: Pass `user_age` and UK Minimum Pension Age (57) Rules to Net Worth Ed in `ed.rs`

**Files:**
- Modify: `modules/monmon-rs/src/ed.rs`

- [ ] **Step 1: Write failing unit tests in `modules/monmon-rs/src/ed.rs` for `build_networth_context_prompt` with `Some(42)`, `Some(60)`, and `None`**
- [ ] **Step 2: Update `build_networth_system_prompt`, `build_networth_context_prompt`, and `ed_says_networth_api` in `modules/monmon-rs/src/ed.rs`**
- [ ] **Step 3: Run `cargo test --manifest-path modules/monmon-rs/Cargo.toml` and verify all tests pass**

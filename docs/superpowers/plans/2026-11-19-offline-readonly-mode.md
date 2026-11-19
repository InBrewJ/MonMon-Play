# MonMon Offline Read-Only Mode Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enable read-only offline browsing of MonMon (including NomMon in Sainsbury's "Lie-Fi" and general browsing on the Tube) via a root-scoped Service Worker, self-hosted assets, background pre-warming with a 🔥→✓ chip indicator, a persistent offline banner, `DELETE`/`PUT` route hardening, and automatic `BUILD_HASH` version checks.

**Architecture:** `monmon-rs` serves self-hosted vendor assets, a `/api/version` endpoint, a `/sw.js` Service Worker stamped with `BUILD_HASH`, and an `/offline` fallback page. The Service Worker uses Cache-First for immutable/self-hosted assets and Network-First with a 2,500ms Lie-Fi timeout for HTML pages and fast JSON (with special non-aborting fallback handling for slow Mistral AI endpoints), while `offline-mode.js` handles version checks, idle background pre-warming, the sticky offline banner, read-only UI locks (keeping NomMon and Transfer Window `localStorage` checklists interactive), and logout cache cleanup.

**Tech Stack:** Rust (`axum 0.7`, `askama 0.12`, `sqlx 0.8`), Plain JavaScript (`.js`, zero CoffeeScript), Service Workers / Cache Storage API, CSS, Node `node:test`, Playwright E2E.

**Spec:** [docs/superpowers/specs/2026-11-19-offline-readonly-mode-design.md](../specs/2026-11-19-offline-readonly-mode-design.md)

## Global Constraints

- **Plain JavaScript only:** All frontend scripts MUST be plain `.js` under `public/javascripts/` — zero CoffeeScript (`.coffee`).
- **Never run deploy scripts:** Do not execute any production deploy scripts; local builds (`./build-rust.sh`) and local tests only.
- **Do not touch scratchpad files:** Never read or edit `NEXT_PROMPT_AGY.md` or `NEXT_PROMPT_AGY_UX.md`.
- **Commit message prefix:** Prefix every git commit message with `[agy]`.
- **Cache versioning:** All Service Worker cache names MUST be keyed by `BUILD_HASH` (`monmon-static-<BUILD_HASH>`, `monmon-pages-<BUILD_HASH>`).
- **Lie-Fi timeout:** HTML navigations and fast JSON reads MUST fall back to cache after `2500` ms.
- **No accidental Mistral calls:** Background pre-warming MUST use `/api/nommon/plan?lockedOnly=true` and MUST NEVER invoke Mistral AI.
- **Zero external CDNs:** Authenticated and landing pages MUST load W3.CSS, Font Awesome 5.15.2, Inter/Plus Jakarta Sans fonts, and Leaflet 1.9.4 exclusively from `/assets/vendor/...`.
- **Local checklists stay interactive offline:** NomMon shopping checkboxes + tabs (`localStorage`) and Transfer Window checkboxes + Clear Checks + Copy + breakdown + tabs (`localStorage`) MUST remain usable while `body.is-offline` is active.

## Review Focus

1. **Version-mismatch infinite reload loop:** If a proxy keeps serving stale HTML with an old `BUILD_HASH` even after cache purge, `offline-mode.js` must reload at most once per target hash (`sessionStorage` guard `monmon_updating_to_hash`) rather than looping forever. *(Pinned in Task 5)*
2. **Slow online Mistral call falsely activating Offline Mode or aborting:** An uncached online `/api/nommon/plan` or `/api/ed-says*` taking 15–40s must never abort at 2.5s/10s and must never trigger `#monmon-offline-banner`. *(Pinned in Task 4)*
3. **Auth redirect poisoning offline HTML cache:** If a session expires mid-pre-warm and `/spog` redirects to `/login` (returning 200 HTML), the SW must never cache the `/login` HTML under `/spog` and must abort remaining pre-warm URLs. *(Pinned in Task 4)*
4. **`lockedOnly=true` pre-warm overwriting an unlocked NomMon plan:** If a user generated an unlocked plan online, a later pre-warm response `{ plan: null }` from `/api/nommon/plan?lockedOnly=true` must never overwrite the cached plan. *(Pinned in Task 4)*
5. **Time Travel cookie mislabeling live cached balances offline:** Navigating offline while `monmon_time_travel` is set must suppress `#monmon-time-travel-banner` and show live dates + `"Live snapshot — Time Travel paused offline"` so today's balances are never shown under a historical date. *(Pinned in Tasks 2 & 5)*

---

## File Structure

- **Create:**
  - `public/vendor/w3css/w3.css` — Self-hosted W3.CSS v4
  - `public/vendor/fontawesome/css/all.min.css` & `public/vendor/fontawesome/webfonts/*` — Self-hosted Font Awesome 5.15.2
  - `public/vendor/fonts/fonts.css` & `public/vendor/fonts/*.woff2` — Self-hosted Inter & Plus Jakarta Sans webfonts
  - `public/vendor/leaflet/leaflet.css`, `leaflet.js`, `images/*` — Self-hosted Leaflet 1.9.4
  - `modules/monmon-rs/templates/offline.html` — Minimal branded offline fallback page
  - `public/javascripts/sw.js` — Root-scoped Service Worker template (stamped with `BUILD_HASH` by Axum)
  - `public/javascripts/offline-mode.js` — Client offline controller, version checker, pre-warmer, 🔥→✓ indicator, read-only UI guard, logout cache clearer
  - `modules/tests-contract/sw.unit.test.mjs` — Node unit tests for `sw.js` fetch routing, timeouts, AI rules, pre-warm & purge
  - `modules/tests-contract/offline-mode.unit.test.mjs` — Node unit tests for `offline-mode.js` version check, warming indicator, Time Travel suppression, read-only guards
  - `modules/tests-e2e/specs/offline-mode.spec.js` — Playwright E2E suite for offline mode, Lie-Fi, pre-warming, version check, logout purge, and self-hosted assets
- **Modify:**
  - `modules/monmon-rs/src/main.rs` — Register `/api/version`, `/sw.js`, `/offline`, convert 13 legacy `GET` mutating routes to `DELETE`/`PUT`, add `Clear-Site-Data` on prod logout, verify asset MIME types
  - `modules/monmon-rs/src/landing.rs` — Add `get_version_api` and `get_offline_page`
  - `modules/monmon-rs/src/nommon.rs` — Add `locked_only` (`lockedOnly`) support to `NomMonQuery` / `get_nommon_plan_api`
  - `modules/monmon-rs/src/time_travel.rs` — Attach `X-MonMon-Time-Travel: 1` header when Time Travel cookie is active
  - `modules/monmon-rs/src/management.rs`, `plans.rs`, `pot.rs`, `savings_plan.rs`, `net_worth.rs` — Support JSON `{ ok: true, redirect }` when `Accept: application/json` on converted `DELETE`/`PUT` handlers
  - `modules/monmon-rs/templates/nav_head.html`, `landing.html`, `dig.html` — Point to self-hosted `/assets/vendor/...` and add `<meta name="monmon-build-hash">`
  - `modules/monmon-rs/templates/nav_header.html` — Add `#monmon-offline-banner`, `#monmon-update-banner`, `#offline-warm-indicator` inside `#nav-user-chip`, `#monmon-offline-toast`, and offline-aware ticker/loader hooks
  - `modules/monmon-rs/templates/nav_scripts.html` — Load `offline-mode.{{ crate::BUILD_HASH }}.js`
  - `modules/monmon-rs/templates/outgoings.html`, `accounts.html`, `incomings.html`, `balances.html`, `plans.html`, `pots.html`, `savings_plan.html`, `net_worth.html` — Convert delete/unpause controls to `data-method="DELETE"` / `data-method="PUT"` + `data-action`
  - `public/stylesheets/main.css` — Styling for offline banner, update banner, 🔥→✓ indicator, offline toast, and `body.is-offline` read-only dimming + Time Travel suppression
  - `public/javascripts/common.js`, `index.js` — Delegated `data-method="DELETE"|"PUT"` handler and avoid double-confirm on `.btn-delete`
  - `public/javascripts/waitingListModal.js`, `global-search.js`, `nommon.js`, `transfers.js` — Offline-safe behavior and preserving local checklists
  - `modules/tests-contract/net-worth.contract.test.mjs`, `modules/tests-e2e/specs/smoke-e2e.spec.js`, `parity-features.spec.js`, `crud-lifecycle.spec.js` — Update for `DELETE`/`PUT` routes & selectors

---

### Task 1: Self-Host Third-Party Vendor Assets & Add Build Hash Meta Tag

**Files:**
- Create: `public/vendor/w3css/w3.css`
- Create: `public/vendor/fontawesome/css/all.min.css` and `public/vendor/fontawesome/webfonts/*`
- Create: `public/vendor/fonts/fonts.css` and `public/vendor/fonts/*.woff2`
- Create: `public/vendor/leaflet/leaflet.css`, `public/vendor/leaflet/leaflet.js`, `public/vendor/leaflet/images/*`
- Modify: `modules/monmon-rs/templates/nav_head.html`
- Modify: `modules/monmon-rs/templates/landing.html`
- Modify: `modules/monmon-rs/templates/dig.html`
- Modify: `modules/monmon-rs/src/main.rs` (`serve_asset_handler` MIME types: add `"eot" => "application/vnd.ms-fontobject"`)
- Test: `modules/monmon-rs/src/landing.rs` (unit test checking templates contain zero external CDN URLs and include `monmon-build-hash` meta tag)

**Interfaces:**
- Consumes: `crate::BUILD_HASH` from `modules/monmon-rs/build.rs`, `/assets/*path` route in `modules/monmon-rs/src/main.rs`
- Produces:
  - Local asset URLs:
    - `/assets/vendor/w3css/w3.css?v={{ crate::BUILD_HASH }}`
    - `/assets/vendor/fontawesome/css/all.min.css?v={{ crate::BUILD_HASH }}`
    - `/assets/vendor/fonts/fonts.css?v={{ crate::BUILD_HASH }}`
    - `/assets/vendor/leaflet/leaflet.css?v={{ crate::BUILD_HASH }}`
    - `/assets/vendor/leaflet/leaflet.js?v={{ crate::BUILD_HASH }}`
  - Meta tag in `nav_head.html` & `landing.html`: `<meta name="monmon-build-hash" content="{{ crate::BUILD_HASH }}">`

- [ ] **Step 1: Write failing unit tests in `modules/monmon-rs/src/landing.rs`**

Add `#[cfg(test)] mod tests` asserting:
- `test_templates_have_no_external_cdn_links`: reads `templates/nav_head.html`, `templates/landing.html`, `templates/dig.html` via `include_str!` and asserts none contain `w3schools.com`, `cdnjs.cloudflare.com`, `fonts.googleapis.com`, `fonts.gstatic.com`, or `unpkg.com`.
- `test_templates_include_build_hash_meta`: asserts `nav_head.html` and `landing.html` contain `name="monmon-build-hash"`.
- `test_vendor_assets_exist_on_disk`: asserts `public/vendor/w3css/w3.css`, `public/vendor/fontawesome/css/all.min.css`, `public/vendor/fonts/fonts.css`, `public/vendor/leaflet/leaflet.css`, and `public/vendor/leaflet/leaflet.js` exist and are non-empty (checking `public/` or `../../public/`).

- [ ] **Step 2: Run test to verify it fails**

Run: `cargo test --manifest-path modules/monmon-rs/Cargo.toml landing::tests`
Expected: FAIL (templates still reference external CDNs and `public/vendor/` does not exist yet).

- [ ] **Step 3: Download and vendor all third-party assets into `public/vendor/`**

Download exact versions matching current templates:
- W3.CSS v4 from `https://www.w3schools.com/w3css/4/w3.css` → `public/vendor/w3css/w3.css`
- Font Awesome 5.15.2 `all.min.css` and all referenced `fa-solid-900`, `fa-regular-400`, `fa-brands-400` webfont files (`.woff2`, `.woff`, `.ttf`, `.svg`, `.eot`) into `public/vendor/fontawesome/css/all.min.css` and `public/vendor/fontawesome/webfonts/` (keep relative `../webfonts/...` paths intact so `/assets/vendor/fontawesome/webfonts/...` resolves automatically).
- Google Fonts `Inter` (weights 300, 400, 500, 600, 700) and `Plus Jakarta Sans` (weights 500, 600, 700, 800) Latin `.woff2` files into `public/vendor/fonts/` with a local `public/vendor/fonts/fonts.css` using relative `url('./<file>.woff2')` and `font-display: swap`.
- Leaflet 1.9.4 `leaflet.css`, `leaflet.js`, and `images/` (`layers.png`, `layers-2x.png`, `marker-icon.png`, `marker-icon-2x.png`, `marker-shadow.png`) into `public/vendor/leaflet/`.
- Sync `public/vendor/` to `modules/monmon-rs/public/vendor/`.

- [ ] **Step 4: Update templates & `serve_asset_handler`**

- In `modules/monmon-rs/templates/nav_head.html`: add `<meta name="monmon-build-hash" content="{{ crate::BUILD_HASH }}">`; replace Google Fonts, W3.CSS, and Font Awesome CDN links with the `/assets/vendor/...?v={{ crate::BUILD_HASH }}` links.
- In `modules/monmon-rs/templates/landing.html`: add `<meta name="monmon-build-hash" content="{{ crate::BUILD_HASH }}">`; replace the same 3 CDN links with `/assets/vendor/...?v={{ crate::BUILD_HASH }}`.
- In `modules/monmon-rs/templates/dig.html`: replace `unpkg.com/leaflet@1.9.4/...` CSS and JS with `/assets/vendor/leaflet/leaflet.css?v={{ crate::BUILD_HASH }}` and `/assets/vendor/leaflet/leaflet.js?v={{ crate::BUILD_HASH }}`.
- In `modules/monmon-rs/src/main.rs` (`serve_asset_handler`): add `"eot" => "application/vnd.ms-fontobject"` to the `content_type` match.

- [ ] **Step 5: Run tests to verify pass**

Run: `cargo test --manifest-path modules/monmon-rs/Cargo.toml landing::tests`
Expected: PASS (all 3 tests green).

- [ ] **Step 6: Commit**

```bash
git add public/vendor modules/monmon-rs/public/vendor modules/monmon-rs/templates/nav_head.html modules/monmon-rs/templates/landing.html modules/monmon-rs/templates/dig.html modules/monmon-rs/src/main.rs modules/monmon-rs/src/landing.rs
git commit -m "[agy] feat: self-host vendor CSS, fonts, Font Awesome, and Leaflet assets"
```

---

### Task 2: Backend Offline & Versioning Endpoints (`/api/version`, `/sw.js`, `/offline`, `lockedOnly`, `X-MonMon-Time-Travel`, Logout `Clear-Site-Data`)

**Files:**
- Create: `modules/monmon-rs/templates/offline.html`
- Create: `public/javascripts/sw.js` (minimal placeholder containing `__MONMON_BUILD_HASH__` token for `/sw.js` route testing; full SW logic added in Task 4)
- Modify: `modules/monmon-rs/src/landing.rs` — add `get_version_api`, `OfflineTemplate`, `get_offline_page`
- Modify: `modules/monmon-rs/src/main.rs` — wire `/api/version`, `/sw.js`, `/offline`; add `serve_service_worker_handler`; add `Clear-Site-Data: "cache"` on production HTTPS in `logout_handler`
- Modify: `modules/monmon-rs/src/nommon.rs` — add `locked_only: Option<bool>` (`#[serde(alias = "lockedOnly")]`) to `NomMonQuery` and short-circuit before Mistral when `locked_only == Some(true)`
- Modify: `modules/monmon-rs/src/time_travel.rs` — attach `X-MonMon-Time-Travel: 1` response header whenever `get_time_travel_date` is `Some`
- Test: `modules/monmon-rs/src/landing.rs`, `modules/monmon-rs/src/nommon.rs`, `modules/monmon-rs/src/time_travel.rs`, `modules/monmon-rs/src/main.rs`

**Interfaces:**
- Consumes: `crate::BUILD_HASH`, `crate::BUILD_COMMIT`, `crate::BUILD_NUMBER`, `crate::BUILD_TIMESTAMP`; self-hosted `/assets/vendor/...` from Task 1
- Produces:
  - `GET /api/version` → `200 OK` JSON `{ "buildHash", "buildCommit", "buildNumber", "buildTimestamp" }` with `Cache-Control: no-cache, no-store, must-revalidate, max-age=0`
  - `GET /sw.js` → `200 OK` `application/javascript; charset=utf-8`, `Service-Worker-Allowed: /`, `Cache-Control: no-cache, no-store, must-revalidate, max-age=0`, `__MONMON_BUILD_HASH__` replaced with `crate::BUILD_HASH`
  - `GET /offline` → `200 OK` HTML branded fallback page using only local assets
  - `GET /api/nommon/plan?lockedOnly=true` → returns locked plan or `{ "configured": true, "locked": false, "plan": null }` without calling Mistral
  - Response header `X-MonMon-Time-Travel: 1` on all responses served under an active `monmon_time_travel` cookie
  - Response header `Clear-Site-Data: "cache"` on `logout_handler` when host contains `dryark.uk`

- [ ] **Step 1: Write failing Rust unit tests**

Add tests:
1. `landing::tests::test_version_api_returns_build_metadata_and_no_store`: calls `get_version_api().await`, asserts HTTP 200, JSON `buildHash == crate::BUILD_HASH`, `buildCommit == crate::BUILD_COMMIT`, `buildNumber == crate::BUILD_NUMBER`, `buildTimestamp == crate::BUILD_TIMESTAMP`, and `Cache-Control` contains `no-store`.
2. `landing::tests::test_offline_page_renders_with_local_assets`: calls `get_offline_page().await`, asserts HTTP 200 HTML containing `/spog`, `/nommon`, `Retry`, and zero `https://` CDN URLs.
3. `nommon::tests::test_nommon_query_deserializes_locked_only_alias`: deserializes `lockedOnly=true` and `locked_only=true` into `NomMonQuery` and asserts `locked_only == Some(true)`. Also unit-test a pure helper `build_unlocked_empty_response() -> serde_json::Value` asserting `configured == true`, `locked == false`, `plan.is_null()`.
4. `time_travel::tests::test_time_travel_header_helper_sets_header_when_cookie_present`: tests `apply_time_travel_response_header(&mut response, is_active)` (called by `enforce_time_travel_read_only`), asserting `X-MonMon-Time-Travel` is `"1"` when `is_active == true` and absent when `false`.
5. `main::tests::test_render_service_worker_injects_build_hash`: tests `render_service_worker_script(template: &str, build_hash: &str) -> String`, asserting `__MONMON_BUILD_HASH__` is replaced by `build_hash`, and tests `build_sw_response(body)` headers (`Content-Type: application/javascript; charset=utf-8`, `Cache-Control` contains `no-store`, `Service-Worker-Allowed: /`).

- [ ] **Step 2: Run tests to verify they fail**

Run: `cargo test --manifest-path modules/monmon-rs/Cargo.toml`
Expected: FAIL (new handlers/helpers do not exist yet).

- [ ] **Step 3: Implement endpoints, template, and header helpers**

- `modules/monmon-rs/templates/offline.html`: Create minimal dark/light-theme-aware Askama page using `/assets/vendor/w3css/w3.css`, `/assets/vendor/fonts/fonts.css`, `/assets/stylesheets/main.{{ crate::BUILD_HASH }}.css`, inline SVG offline icon, links to `/spog` and `/nommon`, and a `Retry connection` button (`window.location.reload()`).
- `public/javascripts/sw.js`: Create initial stub containing `const MONMON_BUILD_HASH = '__MONMON_BUILD_HASH__';` (synced to `modules/monmon-rs/public/javascripts/sw.js`).
- `modules/monmon-rs/src/landing.rs`: Implement `pub async fn get_version_api() -> Response` and `pub async fn get_offline_page() -> Result<Html<String>, (StatusCode, String)>`.
- `modules/monmon-rs/src/main.rs`:
  - Wire `.route("/api/version", get(landing::get_version_api))`, `.route("/sw.js", get(serve_service_worker_handler))`, `.route("/offline", get(landing::get_offline_page))`.
  - Implement `render_service_worker_script`, `build_sw_response`, and `serve_service_worker_handler` (reads `public/javascripts/sw.js` or `../../public/javascripts/sw.js`).
  - In `logout_handler`: when `host.contains("dryark.uk")`, append `Clear-Site-Data: "cache"`.
- `modules/monmon-rs/src/nommon.rs`: Add `#[serde(alias = "lockedOnly")] pub locked_only: Option<bool>` to `NomMonQuery`. In `get_nommon_plan_api`, immediately after the locked-plan check (when no locked row was found or `!force` didn't match), if `query.locked_only.unwrap_or(false)`, return `Ok(Json(build_unlocked_empty_response()))` before calling Mistral or fallback generation.
- `modules/monmon-rs/src/time_travel.rs`: In `enforce_time_travel_read_only`, record `let is_tt = get_time_travel_date(req.headers()).is_some();` before passing `req` to `next.run(req).await`, and call `apply_time_travel_response_header(&mut resp, is_tt)` on the returned `Response`.

- [ ] **Step 4: Run tests to verify pass**

Run: `cargo test --manifest-path modules/monmon-rs/Cargo.toml`
Expected: PASS (all existing + 5 new tests green).

- [ ] **Step 5: Commit**

```bash
git add modules/monmon-rs/templates/offline.html public/javascripts/sw.js modules/monmon-rs/public/javascripts/sw.js modules/monmon-rs/src/landing.rs modules/monmon-rs/src/main.rs modules/monmon-rs/src/nommon.rs modules/monmon-rs/src/time_travel.rs
git commit -m "[agy] feat: add /api/version, /sw.js, /offline, NomMon lockedOnly, and Time Travel cache header"
```

---

### Task 3: Convert Legacy `GET` Mutating Routes to `DELETE` and `PUT`

**Files:**
- Modify: `modules/monmon-rs/src/main.rs` — Change routes + add shared `mutation_redirect_or_json(headers: &HeaderMap, redirect_to: &str) -> Response` helper
- Modify: `modules/monmon-rs/src/management.rs` (`get_bin_outgoing`, `get_bin_account`, `get_bin_incoming`, `post_delete_balance`, `unpause_outgoing_web`)
- Modify: `modules/monmon-rs/src/plans.rs` (`get_bin_plan`, `get_update_savings_payoff_percentage`)
- Modify: `modules/monmon-rs/src/pot.rs` (`get_bin_pot`, `get_bin_preset`)
- Modify: `modules/monmon-rs/src/savings_plan.rs` (`bin_item_handler`, `bin_plan_handler`)
- Modify: `modules/monmon-rs/src/net_worth.rs` (`bin_asset_endpoint`)
- Modify: `public/javascripts/common.js` — Add delegated `[data-method="DELETE"]` / `[data-method="PUT"]` click handler
- Modify: `public/javascripts/index.js` — Update `#savings-slider-val-target` to set `data-action` (with `data-method="PUT"`) and exclude `[data-method]` from legacy `.btn-delete` confirm listener
- Modify: `modules/monmon-rs/templates/outgoings.html`, `accounts.html`, `incomings.html`, `balances.html`, `plans.html`, `pots.html`, `savings_plan.html`, `net_worth.html`
- Modify: `modules/tests-contract/net-worth.contract.test.mjs`, `modules/tests-e2e/specs/smoke-e2e.spec.js`, `modules/tests-e2e/specs/parity-features.spec.js`, `modules/tests-e2e/specs/crud-lifecycle.spec.js`
- Test: `modules/monmon-rs/src/main.rs` (`tests::test_mutation_redirect_or_json_formats`)

**Interfaces:**
- Consumes: `window.showMonmonLoader`, `window.hideMonmonLoader` from `nav_header.html`
- Produces:
  - Backend routes (strictly non-`GET`):
    - `DELETE`: `/binOutgoing/:id`, `/binAccount/:id`, `/binIncoming/:id`, `/balance/:id/delete`, `/binPlan/:id`, `/binPot/:id`, `/binPreset/:id`, `/pot/preset/delete/:id`, `/binSavingsPlanItem/:id`, `/binSavingsPlan/:id`, `/netWorth/binAsset/:id`
    - `PUT`: `/unpauseOutgoing/:id`, `/plans/updateSavingsPayoffPercentage`
  - Shared Rust helper: `pub fn mutation_redirect_or_json(headers: &HeaderMap, redirect_to: &str) -> Response` (returns `200 OK` `{ "ok": true, "redirect": redirect_to }` when `Accept` contains `application/json`, else `303 See Other` `Location: redirect_to`).
  - Shared HTML button contract: `<button type="button" data-method="DELETE|PUT" data-action="<url>" data-confirm="<optional msg>" ...>` handled globally by `common.js`.

- [ ] **Step 1: Write failing Rust unit test for `mutation_redirect_or_json` & route method audit**

In `modules/monmon-rs/src/main.rs` `#[cfg(test)] mod tests`:
- `test_mutation_redirect_or_json_returns_json_when_requested`: passes `Accept: application/json`, asserts `200 OK` and body `{ "ok": true, "redirect": "/myOutgoings" }`.
- `test_mutation_redirect_or_json_returns_see_other_by_default`: passes empty headers, asserts `303 See Other` and `Location: /myOutgoings`.
- `test_no_get_mutating_routes_in_main_rs`: scans `include_str!("main.rs")` (router section) asserting none of `/binOutgoing/:id`, `/binAccount/:id`, `/binIncoming/:id`, `/balance/:id/delete`, `/unpauseOutgoing/:id`, `/binPlan/:id`, `/binPot/:id`, `/binPreset/:id`, `/binSavingsPlanItem/:id`, `/binSavingsPlan/:id`, `/netWorth/binAsset/:id`, `/plans/updateSavingsPayoffPercentage` are wired with `get(`.

- [ ] **Step 2: Run test to verify it fails**

Run: `cargo test --manifest-path modules/monmon-rs/Cargo.toml main::tests`
Expected: FAIL (`mutation_redirect_or_json` missing and legacy `get(...)` routes still present).

- [ ] **Step 3: Convert backend routes and handlers**

- Implement `pub fn mutation_redirect_or_json(headers: &HeaderMap, redirect_to: &str) -> Response` in `modules/monmon-rs/src/main.rs`.
- Change the 11 delete routes in `main.rs` to `delete(...)` only and the 2 state-update routes (`/unpauseOutgoing/:id`, `/plans/updateSavingsPayoffPercentage`) to `put(...)` only.
- Update the 12 handler functions across `management.rs`, `plans.rs`, `pot.rs`, `savings_plan.rs`, and `net_worth.rs` to return `Result<Response, (StatusCode, String)>` using `crate::mutation_redirect_or_json(&headers, "<redirect_target>")` on success (preserving all auth/ownership/error checks).

- [ ] **Step 4: Add delegated `[data-method]` handler in `common.js` and update templates & `index.js`**

- In `public/javascripts/common.js`: add a single capture/bubble delegated click listener on `document` matching `button[data-method][data-action], a[data-method][data-action]`:
  - Prevents default navigation.
  - If `document.body.classList.contains('is-offline')`: call `window.monmonShowOfflineToast && window.monmonShowOfflineToast()` and return immediately.
  - If `data-confirm` is present and `!window.confirm(confirmMsg)`: return.
  - Show `window.showMonmonLoader && window.showMonmonLoader('Saving changes...', 'Updating MonMon')`.
  - Send `fetch(action, { method, credentials: 'same-origin', headers: { 'Accept': 'application/json' } })`.
  - On `res.ok`, parse JSON and navigate to `(data && data.redirect) ? data.redirect : window.location.href` (if same URL with hash or no change, call `window.location.reload()`).
  - On non-OK / error, call `window.hideMonmonLoader && window.hideMonmonLoader()` and `alert(errText || 'Action failed')`.
- In `public/javascripts/index.js`:
  - Update `#savings-slider-val-target` to update `data-action` instead of `href`.
  - Update the legacy deletion confirm handler selector to skip elements with `[data-method]` (`:not([data-method])`).
- Update all 8 templates (`outgoings.html`, `accounts.html`, `incomings.html`, `balances.html` including inline JS erratic jump delete button, `plans.html`, `pots.html` including both preset delete form at line 154 and inline JS form submit to `/pot/preset/delete/` at line 592, `savings_plan.html`, `net_worth.html`) so every converted action uses `<button type="button" data-method="DELETE|PUT" data-action="..." data-confirm="...">` (or `fetch(..., { method: 'DELETE' })` for the inline JS preset delete helper) preserving existing visual classes (`.btn-action`, `.btn-delete`, etc.).
- Sync updated `public/javascripts/` to `modules/monmon-rs/public/javascripts/`.

- [ ] **Step 5: Update existing contract & E2E tests for `DELETE`/`PUT`**

- `modules/tests-contract/net-worth.contract.test.mjs`: change line 63 `method: 'GET'` → `method: 'DELETE'`.
- `modules/tests-e2e/specs/smoke-e2e.spec.js`: ensure delete button locator matches `[data-method="DELETE"][data-action*="/binAccount"], .btn-delete`.
- `modules/tests-e2e/specs/parity-features.spec.js`: update balance delete locator to `[data-method="DELETE"][data-action*="/balance/"][data-action*="/delete"]:visible`.
- `modules/tests-e2e/specs/crud-lifecycle.spec.js`: update unpause locator to `[data-method="PUT"][data-action*="/unpauseOutgoing/"]`.
- Grep `modules/tests-contract/` and `modules/tests-e2e/` for any remaining old route references and update them.

- [ ] **Step 6: Run Rust tests to verify pass**

Run: `cargo test --manifest-path modules/monmon-rs/Cargo.toml`
Expected: PASS (all Rust unit tests green).

- [ ] **Step 7: Commit**

```bash
git add modules/monmon-rs/src/ modules/monmon-rs/templates/ public/javascripts/common.js public/javascripts/index.js modules/monmon-rs/public/javascripts/ modules/tests-contract/net-worth.contract.test.mjs modules/tests-e2e/specs/
git commit -m "[agy] refactor: convert legacy GET mutating routes to DELETE and PUT"
```

---

### Task 4: Service Worker (`public/javascripts/sw.js`) — Caching, Lie-Fi Timeout, AI Rules, Pre-Warming & Purge

**Files:**
- Modify: `public/javascripts/sw.js` (and sync to `modules/monmon-rs/public/javascripts/sw.js`)
- Test: `modules/tests-contract/sw.unit.test.mjs`

**Interfaces:**
- Consumes:
  - `__MONMON_BUILD_HASH__` token injected by `serve_service_worker_handler` (Task 2)
  - `/offline`, `/api/version`, `/api/nommon/plan?lockedOnly=true`, `X-MonMon-Time-Travel: 1` (Task 2)
  - Self-hosted `/assets/vendor/...` URLs (Task 1)
- Produces:
  - Cache names: `monmon-static-<BUILD_HASH>`, `monmon-pages-<BUILD_HASH>`
  - Response headers added on cache fallback:
    - `X-MonMon-Offline: 1`, `X-MonMon-Cached-At: <ISO-8601>` (HTML & fast JSON offline/Lie-Fi fallback)
    - `X-MonMon-Cached-Fallback: 1`, `X-MonMon-Cached-At: <ISO-8601>` (slow AI endpoint cached fallback — does NOT emit `MONMON_OFFLINE_SERVED`)
  - SW → Client messages:
    - `{ type: 'MONMON_OFFLINE_SERVED', url, cachedAt }`
    - `{ type: 'MONMON_PREWARM_PROGRESS', completed, total }`
    - `{ type: 'MONMON_PREWARM_DONE', total, warmedAt }`
    - `{ type: 'MONMON_PREWARM_ABORTED', reason }`
    - `{ type: 'MONMON_CACHES_PURGED' }`
    - `{ type: 'MONMON_USER_CACHES_CLEARED' }`
  - Client → SW messages:
    - `{ type: 'MONMON_PREWARM', urls: string[] }`
    - `{ type: 'MONMON_PURGE_CACHES' }`
    - `{ type: 'MONMON_CLEAR_USER_CACHES' }`

- [ ] **Step 1: Write failing Node unit tests in `modules/tests-contract/sw.unit.test.mjs`**

Load `public/javascripts/sw.js` inside a `node:vm` sandbox mocking `self`, `caches`, `fetch`, `Response`, `Headers`, `Request`, `URL`, `clients`, and `AbortController` (substituting `__MONMON_BUILD_HASH__` with `'abc1234'`). Write tests covering:
1. `install` precaches static vendor assets + hashed JS/CSS + `/offline` into `monmon-static-abc1234` and calls `skipWaiting()`.
2. `activate` deletes stale `monmon-static-old0000` and `monmon-pages-old0000` caches, keeps `*-abc1234`, and calls `clients.claim()`.
3. Non-GET (`POST`, `PUT`, `DELETE`) when `fetch` rejects returns synthetic `503` JSON `{ offline: true, ... }`.
4. Static `/assets/...` uses Cache-First (hits cache without calling `fetch`).
5. HTML navigation Lie-Fi timeout: when `fetch` hangs >2500ms (use configurable `LIE_FI_TIMEOUT_MS` / short test override or fake timers), returns cached HTML with `X-MonMon-Offline: 1` and `X-MonMon-Cached-At`, and posts `MONMON_OFFLINE_SERVED`.
6. HTML navigation cache miss when offline returns cached `/offline`.
7. HTML response with `X-MonMon-Time-Travel: 1` is returned live but NEVER stored in `monmon-pages-abc1234`.
8. **[Review Focus #3]** HTML response redirected to `/login` (`response.url` ending `/login` or `response.redirected` to `/login`) is NEVER stored in `monmon-pages-abc1234`, and aborts `MONMON_PREWARM` with `MONMON_PREWARM_ABORTED`.
9. **[Review Focus #2]** Mistral AI endpoint `/api/ed-says` or `/api/nommon/plan`:
   - On cache miss with slow response (>2.5s), does NOT abort and resolves with the live response; does NOT post `MONMON_OFFLINE_SERVED`.
   - On cache hit with slow response (>2.5s), returns cached copy with `X-MonMon-Cached-Fallback: 1`, does NOT post `MONMON_OFFLINE_SERVED`, and updates cache when live fetch eventually finishes.
   - With `?force=true`, bypasses cache completely.
10. **[Review Focus #4]** `/api/nommon/plan` canonicalizes cache key to `/api/nommon/plan` across differing `?budget=50&people=1` vs `?budget=60&people=2`; and a `{ configured: true, locked: false, plan: null }` response from `?lockedOnly=true` NEVER overwrites an existing cached NomMon plan.
11. `MONMON_PREWARM` warms provided URLs with concurrency 2 and posts `MONMON_PREWARM_DONE`; `MONMON_CLEAR_USER_CACHES` deletes only `monmon-pages-*` (preserving `monmon-static-*`); `MONMON_PURGE_CACHES` deletes all `monmon-*` caches.

- [ ] **Step 2: Run tests to verify they fail**

Run: `node --test modules/tests-contract/sw.unit.test.mjs`
Expected: FAIL (stub `sw.js` has no listeners).

- [ ] **Step 3: Implement `public/javascripts/sw.js`**

Implement the complete Service Worker per Spec §5.3 & §5.4:
- Constants: `MONMON_BUILD_HASH = '__MONMON_BUILD_HASH__'`, `STATIC_CACHE = 'monmon-static-' + MONMON_BUILD_HASH`, `PAGES_CACHE = 'monmon-pages-' + MONMON_BUILD_HASH`, `LIE_FI_TIMEOUT_MS = (typeof self.__MONMON_TIMEOUT_MS === 'number') ? self.__MONMON_TIMEOUT_MS : 2500`, `BG_ABORT_MS = 10000`.
- `PRECACHE_URLS`: `/offline`, `/assets/vendor/w3css/w3.css?v=...`, `/assets/vendor/fontawesome/css/all.min.css?v=...`, `/assets/vendor/fonts/fonts.css?v=...`, `/assets/vendor/leaflet/leaflet.css?v=...`, `/assets/vendor/leaflet/leaflet.js?v=...`, `/assets/stylesheets/main.<hash>.css`, all shared + page JS (`jquery-2.2.0.min`, `common`, `index`, `disco`, `global-search`, `modal-scroll-lock`, `waitingListModal`, `nav-drawer`, `spog-deck`, `offline-mode`, `nommon`, `ed-says`, `edOutgoings`, `edNetWorth`, `networth-chart`, `spending-pace-chart`, `savingsPlan`, `transfers`, `dig`, `leaflet-heat`, `bank-sync`, `legmon`), `/assets/images/monmon-monster.svg`, `/assets/images/favicon.svg`, `/assets/images/favicon.png`, `/assets/site.webmanifest`. (Use resilient `Promise.allSettled` or individual cache puts so one missing optional icon never breaks install, while requiring core shell + `/offline`.)
- Route classifiers: `isNetworkOnly(url)`, `isStaticAsset(url)`, `isAiJsonEndpoint(url)`, `isFastJsonEndpoint(url)`, `isCacheableHtmlResponse(response, requestUrl)` (checks status 200, `text/html`, no `X-MonMon-Time-Travel: 1`, final URL path not `/login`, `/signup`, `/waitinglist`, `/offline`, `/callback`).
- Cache-write helper `putWithTimestamp(cache, key, response)` cloning response with `X-MonMon-Cached-At: new Date().toISOString()`.
- NomMon canonical key helper `canonicalCacheKey(url)`: for `/api/nommon/plan` (without `force=true`), use `new URL('/api/nommon/plan', self.location.origin).href`; skip caching if parsed JSON has `plan === null` or `!plan`.
- Message handlers for `MONMON_PREWARM`, `MONMON_PURGE_CACHES`, `MONMON_CLEAR_USER_CACHES`.
- Sync `public/javascripts/sw.js` to `modules/monmon-rs/public/javascripts/sw.js`.

- [ ] **Step 4: Run tests to verify pass**

Run: `node --test modules/tests-contract/sw.unit.test.mjs`
Expected: PASS (all 11 test cases green).

- [ ] **Step 5: Commit**

```bash
git add public/javascripts/sw.js modules/monmon-rs/public/javascripts/sw.js modules/tests-contract/sw.unit.test.mjs
git commit -m "[agy] feat: implement Service Worker with Lie-Fi timeout, AI endpoint rules, and pre-warming"
```

---

### Task 5: Persistent Offline Banner, 🔥→✓ Warming Indicator & Client Controller (`offline-mode.js`)

**Files:**
- Create: `public/javascripts/offline-mode.js` (and sync to `modules/monmon-rs/public/javascripts/offline-mode.js`)
- Modify: `modules/monmon-rs/templates/nav_header.html` — Add `#monmon-offline-banner`, `#monmon-update-banner`, `#offline-warm-indicator` inside `#nav-user-chip`, `#monmon-offline-toast`, and offline-aware ticker check
- Modify: `modules/monmon-rs/templates/nav_scripts.html` — Include `/assets/javascripts/offline-mode.{{ crate::BUILD_HASH }}.js`
- Modify: `public/stylesheets/main.css` (and sync to `modules/monmon-rs/public/stylesheets/main.css`) — Offline banner, update banner, warming indicator, toast, `body.is-offline` read-only dimming & Time Travel suppression
- Test: `modules/tests-contract/offline-mode.unit.test.mjs`

**Interfaces:**
- Consumes:
  - `<meta name="monmon-build-hash">` (Task 1)
  - `GET /api/version`, `GET /sw.js` (Task 2)
  - SW message protocol from Task 4 (`MONMON_PREWARM`, `MONMON_PREWARM_DONE`, `MONMON_PREWARM_ABORTED`, `MONMON_OFFLINE_SERVED`, `MONMON_PURGE_CACHES`, `MONMON_CLEAR_USER_CACHES`)
- Produces:
  - DOM elements in `nav_header.html`:
    - `#monmon-offline-banner` (contains `#offline-banner-subtext`, `#offline-snapshot-age`, `#btn-offline-retry`, inline SVG icon)
    - `#monmon-update-banner` (hidden until version mismatch detected)
    - `#offline-warm-indicator` inside `#nav-user-chip` (`data-state="idle|warming|done"`)
    - `#monmon-offline-toast`
  - Body class: `body.is-offline`
  - Window helpers:
    - `window.monmonIsOffline() -> boolean`
    - `window.monmonShowOfflineToast(customMsg?: string) -> void`
    - `window.monmonOfflineMode` (exposing `enterOffline(cachedAt?)`, `checkVersionAndSync()`, `triggerPrewarm(force?)`, `setWarmState(state)` for testing and page scripts)

- [ ] **Step 1: Write failing Node unit tests in `modules/tests-contract/offline-mode.unit.test.mjs`**

Test `public/javascripts/offline-mode.js` in a minimal DOM/browser mock (`window`, `document`, `navigator`, `localStorage`, `sessionStorage`, `fetch`, `caches`):
1. `checkVersionAndSync` when server `buildHash === meta buildHash` stays online, clears `monmon_updating_to_hash`, and schedules pre-warm.
2. **[Review Focus #1]** `checkVersionAndSync` when server `buildHash !== meta buildHash` purges caches, sets `sessionStorage['monmon_updating_to_hash'] = serverHash`, and calls `reload()` ONCE; calling it a second time with the same `serverHash` in `sessionStorage` does NOT call `reload()` again.
3. `checkVersionAndSync` when `/api/version` rejects or times out enters `body.is-offline`, shows `#monmon-offline-banner`, and displays relative snapshot age.
4. Warming indicator lifecycle: starting pre-warm sets `#offline-warm-indicator` visible with `🔥` (`data-state="warming"`); receiving `MONMON_PREWARM_DONE` switches it to `✓` (`data-state="done"`) and updates `localStorage['monmon_last_warm_at']`, then hides it after the fade delay; receiving `MONMON_PREWARM_ABORTED` hides it immediately without showing `✓`.
5. Pre-warm throttling: skips pre-warming if `monmon_last_warm_at` is < 15 minutes ago (unless `force=true` or version changed), and skips completely when `monmon_time_travel` cookie is present.
6. **[Review Focus #5]** Time Travel offline suppression: when `monmon_time_travel=2024-03-15` cookie is present and `enterOffline()` runs, `#monmon-offline-banner` shows `"Read-Only Mode (Live snapshot — Time Travel paused offline)"` and `#monmon-time-travel-banner` is hidden.
7. Read-only form/action blocking: when `body.is-offline`, submitting a normal `<form>` is prevented (`defaultPrevented === true`) and shows toast; a `<form data-offline-allowed="true">` is NOT blocked.
8. Logout cache clearing: clicking `a[href="/centralLogout"]` while online posts `MONMON_CLEAR_USER_CACHES` and removes `monmon_last_warm_at` before navigating; clicking while offline is blocked.

- [ ] **Step 2: Run tests to verify they fail**

Run: `node --test modules/tests-contract/offline-mode.unit.test.mjs`
Expected: FAIL (`public/javascripts/offline-mode.js` does not exist yet).

- [ ] **Step 3: Add markup in `nav_header.html`, script include in `nav_scripts.html`, and CSS in `main.css`**

- `modules/monmon-rs/templates/nav_header.html`:
  - Add `#monmon-update-banner` (hidden top bar for `"Updating MonMon to the latest version…"`).
  - Add `#monmon-offline-banner` right above `#monmon-time-travel-banner`: sticky `top: 0`, `z-index: 99999`, inline SVG cloud-off icon, `OFFLINE` badge, `<span id="offline-banner-subtext">Read-Only Mode</span>`, `<span id="offline-snapshot-age"></span>`, and `<button type="button" id="btn-offline-retry">Retry</button>`.
  - Inside `#nav-user-chip` (top right corner), add `<span id="offline-warm-indicator" class="offline-warm-indicator" data-state="idle" style="display: none;" aria-live="polite"></span>`.
  - Add `#monmon-offline-toast` (compact bottom-center floating toast, hidden by default).
  - Update the inline `renderLiveTicker()` script in `nav_header.html` so if `document.body.classList.contains('is-offline')`, it ignores `monmon_time_travel` and renders today's live date.
- `modules/monmon-rs/templates/nav_scripts.html`:
  - Add `<script src="/assets/javascripts/offline-mode.{{ crate::BUILD_HASH }}.js" type="text/javascript"></script>`.
- `public/stylesheets/main.css`:
  - Style `#monmon-offline-banner` (cool slate-indigo `#1e293b` → `#334155`, `#38bdf8` bottom accent, compact responsive layout on `@media (max-width: 600px)`), `#monmon-update-banner`, `#offline-warm-indicator` (subtle fade transition, small font size fitting neatly in `#nav-user-chip`), `#monmon-offline-toast`.
  - Style `body.is-offline #monmon-time-travel-banner { display: none !important; }`.
  - Style `body.is-offline` read-only dimming for server-mutating controls (`button[type="submit"]:not([data-offline-allowed])`, `[data-method="DELETE"]`, `[data-method="PUT"]`, `.btn-delete`, `#btn-regenerate-nommon`, `#nommon-lock-wrapper button`, `#btn-save-postcode`, `#nav-drawer-time-travel-link`, `.drawer-logout a`, etc.) while NEVER dimming `.nommon-item-check`, `#btn-uncheck-all`, `.nommon-tab-btn`, `input[id^="chk-"]`, or `[data-offline-allowed]`.

- [ ] **Step 4: Implement `public/javascripts/offline-mode.js`**

Implement all responsibilities per Spec §5.4–§5.6 & §5.8:
- Register `/sw.js` with `{ scope: '/' }` (guarded on `'serviceWorker' in navigator`).
- `PREWARM_URLS` array in Priority 1 → 2 → 3 order matching Spec §5.4.
- `checkVersionAndSync()`: `fetch('/api/version', { cache: 'no-store' })` with 2,500ms `AbortController` timeout; handles match / mismatch (with `sessionStorage` loop guard, SW cache purge + `window.caches` fallback purge, `registration.update()`, reload) / failure (`enterOffline()`).
- `enterOffline(cachedAtIso)` & `exitOffline()`: toggles `body.is-offline`, shows/hides `#monmon-offline-banner`, updates `#offline-banner-subtext` (handling `monmon_time_travel` cookie note), refreshes `#offline-snapshot-age` every 30s, wires `#btn-offline-retry` to probe `/api/version` and reload on success or flash `"Still offline"`.
- `triggerPrewarm(force)`: checks authenticated (`#main-nav` present), online, not Time Travel, 15-min `monmon_last_warm_at` throttle; posts `MONMON_PREWARM` to active SW and sets `setWarmState('warming')` (🔥).
- `setWarmState('idle' | 'warming' | 'done')`: updates `#offline-warm-indicator` (`🔥` → `✓` → fade/hide after 2.5s).
- Capture-phase `submit` & `click` read-only guards (allowing `[data-offline-allowed]`, NomMon checklist/tabs, Transfer Window checklist/tabs/copy/breakdown; blocking forms, `[data-method]`, edit-only links `/editOutgoing/`, `/editAccount/`, `/editIncoming/`, `/editSavingsPlanItem/`, `/savingsPlan/editItem/`, and `/logout`/`/centralLogout` while offline).
- Online logout click interception: sends `MONMON_CLEAR_USER_CACHES` to SW (with 500ms ceiling) + removes `monmon_last_warm_at`, then navigates to logout href.
- Sync `public/javascripts/offline-mode.js` and `public/stylesheets/main.css` to `modules/monmon-rs/public/`.

- [ ] **Step 5: Run tests to verify pass**

Run: `node --test modules/tests-contract/offline-mode.unit.test.mjs`
Expected: PASS (all 8 test cases green).

- [ ] **Step 6: Commit**

```bash
git add public/javascripts/offline-mode.js modules/monmon-rs/public/javascripts/offline-mode.js public/stylesheets/main.css modules/monmon-rs/public/stylesheets/main.css modules/monmon-rs/templates/nav_header.html modules/monmon-rs/templates/nav_scripts.html modules/tests-contract/offline-mode.unit.test.mjs
git commit -m "[agy] feat: add persistent offline banner, version check, warming indicator, and read-only controller"
```

---

### Task 6: Page-Specific Offline Integration (`waitingListModal`, `global-search`, `nommon`, `transfers`, Global Loader)

**Files:**
- Modify: `public/javascripts/waitingListModal.js`
- Modify: `public/javascripts/global-search.js`
- Modify: `public/javascripts/nommon.js`
- Modify: `public/javascripts/transfers.js`
- Modify: `modules/monmon-rs/templates/transfers.html` (mark local Clear Checks / Copy / tab buttons `data-offline-allowed="true"` if inside any form or matching generic button selectors)
- Modify: `modules/monmon-rs/templates/nommon.html` (mark `#btn-uncheck-all` and `.nommon-tab-btn` `data-offline-allowed="true"`)
- Modify: `modules/monmon-rs/templates/nav_header.html` (prevent `#monmon-page-loader` from activating on offline-blocked clicks/submits)
- Test: `modules/tests-contract/offline-mode.unit.test.mjs` (extend with page integration checks)

**Interfaces:**
- Consumes: `window.monmonIsOffline()`, `body.is-offline`, custom events `monmon:offline-change` dispatched by `offline-mode.js` on `window` with `{ detail: { offline: boolean } }`
- Produces:
  - `waitingListModal.js`: silent skip when offline
  - `global-search.js`: disables `#global-search-input` with `"Search unavailable offline"` placeholder when offline
  - `nommon.js`: renders cached plan cleanly, displays subtle `"Cached snapshot"` badge when `X-MonMon-Offline` or `X-MonMon-Cached-Fallback` header is present, skips `api.postcodes.io` offline, locks regenerate/lock/budget/people/postcode save offline while keeping `.nommon-item-check`, `#btn-uncheck-all`, and tabs interactive
  - `transfers.js`: keeps all `localStorage` checkboxes, Clear Checks, Copy, breakdown toggle, and Next/Last tabs 100% interactive offline

- [ ] **Step 1: Add failing integration assertions to `modules/tests-contract/offline-mode.unit.test.mjs`**

Add tests verifying:
1. `offline-mode.js` dispatches `monmon:offline-change` custom event with `{ offline: true/false }` on state transitions.
2. `waitingListModal.js` does not call `fetch('/api/user/accessStatus')` when `navigator.onLine === false` or `body.is-offline`.
3. Clicking `#btn-uncheck-all`, `.nommon-tab-btn`, `.nommon-item-check` (NomMon) and `input[id^="chk-"]` / `[data-offline-allowed]` (Transfer Window) is NOT prevented while `body.is-offline`.

- [ ] **Step 2: Run tests to verify they fail**

Run: `node --test modules/tests-contract/offline-mode.unit.test.mjs`
Expected: FAIL (`monmon:offline-change` event / `waitingListModal` offline guard not added yet).

- [ ] **Step 3: Implement page-specific offline integrations**

- `public/javascripts/offline-mode.js`: dispatch `new CustomEvent('monmon:offline-change', { detail: { offline: isOffline } })` on `window` whenever offline state changes.
- `public/javascripts/waitingListModal.js`: at top of `checkAccessStatus()`, return early if `navigator.onLine === false` or `document.body.classList.contains('is-offline')`.
- `public/javascripts/global-search.js`: listen to `monmon:offline-change` (and check initial `body.is-offline`): set `#global-search-input.disabled = offline` and toggle placeholder between `"Search... (/)"` and `"Search unavailable offline"`.
- `public/javascripts/nommon.js`:
  - In `updateStoreLinks(postcode)`: skip `fetch('https://api.postcodes.io/...')` when offline (keep fallback URL).
  - In `fetchPlan(force)`: if offline and `force` is true, show `window.monmonShowOfflineToast && window.monmonShowOfflineToast()` and return early; read `res.headers.get('X-MonMon-Offline')` / `X-MonMon-Cached-Fallback` and show a subtle cached badge in `#nommon-basket-subtitle` if set; on offline cache-miss error, display a clean `"Offline — no cached meal plan found"` state instead of a broken spinner.
  - Listen to `monmon:offline-change`: toggle `disabled` on `#btn-regenerate-nommon`, `#nommon-budget-input`, `#nommon-people-select`, `#btn-save-postcode`, and lock/unlock buttons while keeping `.nommon-item-check`, `#btn-uncheck-all`, and `.nommon-tab-btn` enabled.
- `modules/monmon-rs/templates/nommon.html` & `transfers.html`: add `data-offline-allowed="true"` on local-only buttons (`#btn-uncheck-all`, `.nommon-tab-btn`, Transfer Window Clear Checks, Copy, breakdown toggle, Next/Last tabs).
- `modules/monmon-rs/templates/nav_header.html`: in the global click & submit loader listeners, return early if `e.defaultPrevented` or if `document.body.classList.contains('is-offline')` on a form/edit link.
- Sync modified JS files into `modules/monmon-rs/public/javascripts/`.

- [ ] **Step 4: Run unit tests to verify pass**

Run: `node --test modules/tests-contract/sw.unit.test.mjs modules/tests-contract/offline-mode.unit.test.mjs`
Expected: PASS (all tests green).

- [ ] **Step 5: Commit**

```bash
git add public/javascripts/ modules/monmon-rs/public/javascripts/ modules/monmon-rs/templates/nommon.html modules/monmon-rs/templates/transfers.html modules/monmon-rs/templates/nav_header.html modules/tests-contract/offline-mode.unit.test.mjs
git commit -m "[agy] feat: integrate offline read-only behavior across NomMon, Transfers, Search, and Waiting List"
```

---

### Task 7: Playwright E2E Offline & Version-Check Suite + Full Build & Regression Verification

**Files:**
- Create: `modules/tests-e2e/specs/offline-mode.spec.js`
- Test: `cargo test`, `./build-rust.sh`, `modules/tests-contract/`, `modules/tests-e2e/specs/offline-mode.spec.js`

**Interfaces:**
- Consumes: All endpoints, Service Worker, `offline-mode.js`, self-hosted vendor assets, and `DELETE`/`PUT` routes from Tasks 1–6
- Produces: End-to-end Playwright verification suite in `modules/tests-e2e/specs/offline-mode.spec.js`

- [ ] **Step 1: Write `modules/tests-e2e/specs/offline-mode.spec.js`**

Write Playwright tests (running against local `BASE_URL` with authenticated session cookies/login helper matching `spog-deck.spec.js`):
1. **Self-hosted assets check:** Record all network requests while loading `/spog`, `/nommon`, and `/dig`; assert zero requests target `w3schools.com`, `cdnjs.cloudflare.com`, `fonts.googleapis.com`, `fonts.gstatic.com`, or `unpkg.com`.
2. **SW registration, 🔥→✓ warming, and Tube offline browsing:** Load `/spog`, verify Service Worker activates, verify `#offline-warm-indicator` transitions to warmed state (and `monmon_last_warm_at` is set in `localStorage`), call `context.setOffline(true)`, navigate to `/nommon`, `/myOutgoings`, and `/transfers`; assert `#monmon-offline-banner` is visible with SVG icon and `OFFLINE` badge, content renders cleanly, forms/`DELETE` buttons are blocked, AND both NomMon shopping checkboxes + Transfer Window checkboxes toggle and persist in `localStorage` offline.
3. **Sainsbury's Lie-Fi 2.5s timeout fallback:** While online with warm cache, intercept `/nommon` navigation with `page.route('**/nommon', ...)` delaying 10 seconds; navigate to `/nommon` and assert cached NomMon renders within 4.5s and `#monmon-offline-banner` appears.
4. **Version mismatch auto-update (no reload loop):** Intercept `GET /api/version` to return `{ buildHash: 'newer999', buildCommit: 'newer999', buildNumber: '999', buildTimestamp: '2026-11-19' }` once, reload `/spog`, verify `monmon-*` caches are purged and reload triggers once (and stops on second load via `monmon_updating_to_hash`).
5. **Logout cache purge:** Verify `monmon-pages-*` cache exists while logged in, trigger logout click interception, and assert `monmon-pages-*` is removed from `caches.keys()`.

- [ ] **Step 2: Build Rust release & sync public assets**

Run: `./build-rust.sh`
Expected: Exit 0 (`monmon-rs`, `legmon-rs`, `memmon-rs` built and `public/*` synced to `modules/monmon-rs/public/`).

- [ ] **Step 3: Run all Rust unit tests and Node unit tests**

Run: `cargo test --manifest-path modules/monmon-rs/Cargo.toml && node --test modules/tests-contract/sw.unit.test.mjs modules/tests-contract/offline-mode.unit.test.mjs`
Expected: 100% PASS.

- [ ] **Step 4: Run Playwright E2E suite (offline-mode + regression specs) and contract tests**

Run against local running `monmon-rs` + PostgreSQL instance:
- `cd modules/tests-e2e && npx playwright test specs/offline-mode.spec.js specs/crud-lifecycle.spec.js specs/parity-features.spec.js --project=desktop-chromium`
- `cd modules/tests-e2e && npx playwright test specs/offline-mode.spec.js --project=mobile-chromium`
- `modules/tests-contract/run.sh` (or targeted `net-worth.contract.test.mjs`)
Expected: 100% PASS on both desktop and mobile viewports.

- [ ] **Step 5: Commit**

```bash
git add modules/tests-e2e/specs/offline-mode.spec.js
git commit -m "[agy] test: add Playwright E2E suite for offline read-only mode, Lie-Fi, and version updates"
```

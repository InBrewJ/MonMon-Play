# MonMon Offline Read-Only Mode — Design Specification

**Date:** 2026-11-19
**Status:** Approved
**Scope:** Full Project — `modules/monmon-rs`, `public/`, `build-rust.sh`, contract & E2E tests

---

## 1. Intent & Why

MonMon is frequently used in two real-world disconnected or degraded-connectivity scenarios:
1. **Supermarket "Lie-Fi" (e.g., Sainsbury's in Winchburgh):** Mobile signal drops to 1 hanging bar where `navigator.onLine` stays `true` and standard network requests stall for 20–30 seconds before failing. The user wants to open **NomMon**, browse the weekly shopping list / recipes / meal schedule, and tick items off in-store without staring at a spinner.
2. **Zero-signal commuting (e.g., on the Tube):** Complete loss of connectivity where the user wants to browse their MonMon overview, outgoings, accounts, balances, savings plan, net worth, and other financial views generally.

In offline mode, MonMon is intentionally **read-only**—there is no offline edit queue, no conflict resolution, and no background write sync. When disconnected, the UI must make the offline read-only state unmistakably clear via a persistent, always-visible top banner with a tasteful offline icon.

Finally, because Service Workers are notorious for trapping users on stale HTML/CSS/JS bundles after deployments, MonMon must **verify frontend vs. backend version parity on every online load** (they always deploy together from the same `BUILD_HASH`) and cleanly purge stale caches and update whenever the frontend is behind.

---

## 2. Goals, Non-Goals, and Anti-Goals

### Goals
- **Root-scoped Service Worker (`/sw.js`)** enabling offline read-only browsing across all core authenticated MonMon pages and cached read APIs (especially NomMon).
- **Fast Lie-Fi fallback (~2.5s network timeout)** on HTML navigations and cached read APIs so hanging mobile signal falls back rapidly to the cached snapshot instead of blocking on a spinner.
- **Background pre-warming** of core browsable pages and the user's locked NomMon plan while online, so pages are ready offline even if the user didn't visit every page right before losing signal.
- **Self-hosted third-party assets:** Download and serve W3.CSS, Font Awesome 5.15.2 (CSS + webfonts), Google Fonts (`Inter` and `Plus Jakarta Sans` `.woff2`), and Leaflet CSS/JS locally so zero external CDNs are required to render pages cleanly offline.
- **Zero-stale-bundle version check:** Expose `GET /api/version` (`Cache-Control: no-store`) returning `BUILD_HASH`, compare against the page's embedded `BUILD_HASH` on every online load, and automatically update the Service Worker, purge stale caches, and reload when behind.
- **Persistent offline banner:** Always-visible sticky top banner (consistent with MonMon's Time Travel banner pattern) featuring a tasteful inline SVG offline icon, clear read-only status, snapshot freshness ("Last synced …"), and a manual Retry button.
- **Strict read-only enforcement offline:**
  - Convert all legacy `GET`-based mutating routes to `DELETE` (for deletions/archivals) and `PUT` (for state updates).
  - Disable/dim server-mutating controls and block form submissions / non-GET requests in both the UI and Service Worker when offline.
  - Keep **NomMon's local `localStorage` shopping checklist** (ticking items off, Clear Checks, and switching between Shopping List / 7-Day Schedule / Recipes tabs) and **Transfer Window's local `localStorage` checklist** (ticking transfers done, Clear Checks, Copy amount, breakdown toggle, and Next/Last Payday tabs) fully interactive offline.
- **Privacy on logout:** Purge all cached personal financial HTML/JSON snapshots when the user logs out.

### Non-Goals
- Offline mutation queues, optimistic writes, or background write sync.
- Offline support for inherently live/external features: Global Search (`/api/search`), Ed Says AI generation (`?force=true`), NomMon AI plan regeneration/locking, Bank Sync / Monzo OAuth / LegMon statement uploads, Passkey registration/login, Time Travel activation/deactivation, postcode coordinate lookups (`api.postcodes.io`), or OpenStreetMap map tiles on `/dig`.
- Re-architecting MonMon from server-side Askama HTML into a client-side SPA or IndexedDB data store.
- Running or modifying deployment scripts (deployment remains strictly human-executed).

### Anti-Goals
- **Stale deployments trapped by the Service Worker:** Users must never have to manually clear site data or hard-refresh twice to pick up new HTML/CSS/JS.
- **Accidental Mistral AI calls during pre-warming:** Pre-warming NomMon must *never* trigger an AI generation request; it may only fetch an already-locked plan.
- **Stuck UI overlays offline:** The global navigation loader (`#monmon-page-loader`) and the Waiting List polling modal (`waitingListModal.js`) must never hang or falsely block the user when offline.
- **Time Travel polluting live offline caches:** Historical HTML rendered while `monmon_time_travel` is active must never overwrite live offline snapshots.
- **Financial data lingering after logout:** Cached HTML pages containing balances/outgoings must not survive logout.
- **CoffeeScript:** All new frontend scripts must be plain JavaScript (`.js`) per `AGENTS.md`.

---

## 3. Codebase Context & Constraints

- **Backend:** Rust Axum + Askama SSR server in `modules/monmon-rs/`.
- **Build identity:** `modules/monmon-rs/build.rs` already injects compile-time `BUILD_HASH`, `BUILD_COMMIT`, `BUILD_NUMBER`, and `BUILD_TIMESTAMP` into `crate::BUILD_HASH`, etc.
- **Asset pipeline:** Static assets live in `public/`, are synced to `modules/monmon-rs/public/` by `build-rust.sh`, and are mounted into Docker at `/app/public`. First-party CSS/JS URLs already include `.{{ crate::BUILD_HASH }}.` and are served by `serve_asset_handler` with `Cache-Control: public, max-age=31536000, immutable`.
- **HTML caching:** `apply_cache_control_headers` applies `Cache-Control: no-cache, no-store, must-revalidate, max-age=0` to all HTML and API responses by default.
- **Shared layout:** Every authenticated page includes `nav_head.html`, `nav_header.html`, and `nav_scripts.html`. `site.webmanifest` (`start_url: "/spog"`, `display: "standalone"`) and Apple touch icons already exist.
- **Existing read-only precedent:** Time Travel mode uses a sticky top banner (`#monmon-time-travel-banner`) and `body.in-time-travel` class in `nav_header.html`.
- **NomMon behavior:** `/nommon` renders the page shell server-side; `public/javascripts/nommon.js` then fetches `/api/nommon/plan?budget=...&people=...` (and optionally `&postcode=...`). Checked grocery items already persist in `localStorage` (`nommon_checked_items`).
- **Tests:** Contract tests live in `modules/tests-contract/` and Playwright E2E tests live in `modules/tests-e2e/specs/`. Several existing tests reference the `GET` delete/unpause routes and must be updated when those routes move to `DELETE`/`PUT`.

---

## 4. Approaches Considered

| Approach | Summary | Trade-offs | Decision |
|---|---|---|---|
| **1. Service Worker Snapshot Cache (Network-First + 2.5s Lie-Fi Timeout + Pre-Warming)** | Root `/sw.js` caches self-hosted + hashed assets Cache-First, and serves SSR HTML + key read JSON Network-First with a 2.5s timeout falling back to cached snapshots. Idle background pre-warmer keeps core pages & locked NomMon plan warm. Explicit `/api/version` check eliminates stale bundles. | Online loads wait up to ~2.5s on dying signal before switching to cache; live-only features (Global Search, Bank Sync, AI generation, map tiles) remain unavailable offline. | **Selected** — Reuses all existing Askama templates, handles both Sainsbury's Lie-Fi and Tube offline cleanly, and guarantees fresh deployments. |
| **2. Stale-While-Revalidate (Cache-First HTML + Background Refresh)** | Always serves cached HTML immediately (0ms) and updates the cache in the background for the *next* navigation. | Feels instant on poor signal, **but** after editing an outgoing/balance or deploying a new version, users see stale numbers or stale bundles on first load until refreshing again. Directly conflicts with the fresh-update anti-goal. | **Rejected** |
| **3. Client-Side Offline Store (IndexedDB + JS Rendering)** | Syncs JSON financial data into IndexedDB and renders read-only views client-side in JS when offline. | Enables offline search/filtering across raw records, **but** requires duplicating ~15 Askama HTML templates into JavaScript and maintaining parity indefinitely. | **Rejected** |

---

## 5. Detailed Design

### 5.1 Self-Hosted Third-Party Vendor Assets
Download and commit local copies of all external CSS, JS, and font assets under `public/vendor/`:
- `public/vendor/w3css/w3.css` (W3.CSS v4)
- `public/vendor/fontawesome/css/all.min.css` and `public/vendor/fontawesome/webfonts/*` (Font Awesome 5.15.2)
- `public/vendor/fonts/fonts.css` and self-hosted `.woff2` files for `Inter` (weights 300, 400, 500, 600, 700) and `Plus Jakarta Sans` (weights 500, 600, 700, 800)
- `public/vendor/leaflet/leaflet.css`, `public/vendor/leaflet/leaflet.js`, and Leaflet marker images (used by `/dig`)

Template updates:
- Update `nav_head.html`, `landing.html`, and `dig.html` to reference `/assets/vendor/...` (with `?v={{ crate::BUILD_HASH }}` or hashed filenames so `serve_asset_handler` serves them `immutable` and busts cache on build).
- Remove all `<link>` and `<script>` references to `www.w3schools.com`, `cdnjs.cloudflare.com`, `fonts.googleapis.com`, `fonts.gstatic.com`, and `unpkg.com`.
- Verify `serve_asset_handler` in `modules/monmon-rs/src/main.rs` supports all font/image extensions (`.woff2`, `.woff`, `.ttf`, `.eot`, `.svg`, `.png`) and sets `Cache-Control: public, max-age=31536000, immutable` when `?v=` is present (it already checks `q.contains("v=")`).
- Ensure `build-rust.sh` copies `public/vendor/` recursively into `modules/monmon-rs/public/` (existing `cp -ru public/* modules/monmon-rs/public/` already recurses).

### 5.2 Backend Endpoints & Template Metadata
1. **`GET /api/version`** (public, no auth required):
   - Returns `200 OK` JSON:
     ```json
     {
       "buildHash": "<BUILD_HASH>",
       "buildCommit": "<BUILD_COMMIT>",
       "buildNumber": "<BUILD_NUMBER>",
       "buildTimestamp": "<BUILD_TIMESTAMP>"
     }
     ```
   - Headers: `Cache-Control: no-cache, no-store, must-revalidate, max-age=0`.
2. **`GET /sw.js`** (root scope `/`):
   - Serves `public/javascripts/sw.js` with `const MONMON_BUILD_HASH = "<BUILD_HASH>";` injected (or replacing a `__MONMON_BUILD_HASH__` token) at serve time so every build produces a byte-different Service Worker file.
   - Headers: `Content-Type: application/javascript; charset=utf-8`, `Cache-Control: no-cache, no-store, must-revalidate, max-age=0`, `Service-Worker-Allowed: /`.
   - Also expose the list of static precache URLs (or inject `MONMON_BUILD_HASH` so `sw.js` constructs all `.${MONMON_BUILD_HASH}.js/css` URLs deterministically).
3. **`GET /offline`** (public, precached on SW install):
   - Lightweight branded Askama template (`offline.html`) using only self-hosted styles/icons.
   - Explains that the requested page wasn't cached before going offline, lists quick links to cached pages (especially `/spog` and `/nommon`), and provides a "Retry connection" button.
4. **Build hash meta tag:**
   - Add `<meta name="monmon-build-hash" content="{{ crate::BUILD_HASH }}">` to `nav_head.html` and `landing.html`.
5. **NomMon safe pre-warm query parameter (`GET /api/nommon/plan`):**
   - Add optional `locked_only: Option<bool>` (alias `lockedOnly`) to `NomMonQuery` in `modules/monmon-rs/src/nommon.rs`.
   - When `lockedOnly=true`: query `locked_meal_plan` and return the locked plan JSON if present; if no locked plan exists, immediately return `200 OK` `{ "configured": true, "locked": false, "plan": null }` **without** calling Mistral AI or returning a synthetic fallback plan.

### 5.3 Service Worker Caching & Lie-Fi Strategy (`public/javascripts/sw.js`)
Cache namespaces are strictly versioned by `MONMON_BUILD_HASH`:
- `monmon-static-<BUILD_HASH>` — vendor assets, hashed first-party CSS/JS, app icons/logos, `site.webmanifest`, and `/offline`.
- `monmon-pages-<BUILD_HASH>` — authenticated HTML page snapshots and read-only API JSON snapshots.

#### Lifecycle
- **`install`**: Precache the static shell assets + `/offline` into `monmon-static-<BUILD_HASH>`, then call `self.skipWaiting()`.
- **`activate`**: Enumerate `caches.keys()`, delete every cache not matching `monmon-static-<BUILD_HASH>` or `monmon-pages-<BUILD_HASH>`, then call `self.clients.claim()`.

#### Fetch Routing Rules
1. **Network-Only (never intercepted for caching):**
   - Cross-origin requests (e.g. `api.postcodes.io`, OpenStreetMap tiles).
   - Auth, version, health, and live-only endpoints: `/api/version`, `/health`, `/login`, `/signup`, `/callback`, `/logout`, `/centralLogout`, `/timeTravel/*`, `/api/auth/*`, `/api/search`, `/api/legmon/*`, `/bank/monzo/*`, `/api/monzo/webhook`, `/api/user/accessStatus`.
2. **Non-GET Mutating Requests (`POST`, `PUT`, `DELETE`, `PATCH`):**
   - Forward directly to network.
   - If the network fails (offline), return a synthetic `503 Service Unavailable` JSON response: `{ "offline": true, "error": "MonMon is offline (read-only mode)." }`.
3. **Static & Vendor Assets (`/assets/*`, `/public/*`, `/favicon.ico`, `/apple-touch-icon*.png`, `/site.webmanifest`):**
   - **Cache-First:** Return from `monmon-static-<BUILD_HASH>` if present; otherwise fetch from network, store `200 OK` responses in `monmon-static-<BUILD_HASH>`, and return.
4. **HTML Page Navigations (`request.mode === 'navigate'` or GET accepting `text/html`):**
   - **Network-First with 2,500ms Lie-Fi Timeout:**
     - Start `fetch(request)` alongside a 2,500ms timeout.
     - **If network resolves within 2.5s:**
       - Cache the response in `monmon-pages-<BUILD_HASH>` (with a cloned `X-MonMon-Cached-At: <ISO-8601>` header) **only if**:
         - `response.status === 200`
         - Content-Type is `text/html`
         - Final URL is not `/login`, `/signup`, `/waitinglist`, or `/offline`
         - Request does NOT have Time Travel active (signaled by SW checking whether the client/page indicates Time Travel, or simpler: backend attaches `X-MonMon-Time-Travel: 1` header whenever `time_travel::get_time_travel_date(&headers).is_some()`, and the SW refuses to cache any response carrying `X-MonMon-Time-Travel: 1`!). *Builder note: attaching `X-MonMon-Time-Travel: 1` in the existing Time Travel Axum middleware is clean, authoritative, and works even for HttpOnly/server-checked cookies.*
       - Return the fresh network response.
     - **If network fails OR exceeds 2,500ms:**
       - Look up the URL in `monmon-pages-<BUILD_HASH>` (matching pathname cleanly).
       - If found: clone response, attach `X-MonMon-Offline: 1` (preserving `X-MonMon-Cached-At`), notify clients via `postMessage({ type: 'MONMON_OFFLINE_SERVED', url, cachedAt })`, and return it immediately.
       - If the slow network request was still in flight when the 2.5s timer fired, allow it (up to 10s before aborting) to silently refresh `monmon-pages-<BUILD_HASH>` if it eventually returns a valid cacheable `200 OK` response, so subsequent navigations get fresh data.
       - If not found in cache: return cached `/offline` response.
5. **Cached Read-Only JSON Endpoints:**
   - **Fast DB-backed JSON endpoints** (`/api/spog/metrics`, `/api/spog/spending-pace`, `/api/transfers`, `/api/networth`, `/api/savings-plan`, `/api/dig/data`, `/api/monzo-dig/data`, `/api/waste-bin`):
     - **Network-First with 2,500ms Lie-Fi Timeout**, falling back to `monmon-pages-<BUILD_HASH>` on network failure or timeout (attaching `X-MonMon-Offline: 1` + `X-MonMon-Cached-At`).
   - **Mistral AI-backed JSON endpoints** (`/api/nommon/plan`, `/api/ed-says`, `/api/ed-says-outgoings`, `/api/ed-says-networth`) *(design call to prevent false Offline Mode on slow online AI calls)*:
     - **Explicit regeneration (`force=true`):** Always bypass cache and never apply the 2.5s timeout or 10s abort; fail cleanly if offline.
     - **Standard load (`force` absent/false):**
       - Never abort an in-flight AI request at 2.5s or 10s (legitimate Mistral generations can take 10–60s on server cache miss).
       - If the network fails outright, serve from `monmon-pages-<BUILD_HASH>` with `X-MonMon-Offline: 1`.
       - If a **cached copy already exists** and the network has not responded within 2,500ms, return the cached copy immediately with `X-MonMon-Cached-Fallback: 1` (notably **without** emitting `MONMON_OFFLINE_SERVED` so a slow online Mistral generation on Wi-Fi never falsely flips the app into global Offline Read-Only Mode—connectivity is verified independently via the instant `GET /api/version` check), while letting the in-flight request continue to completion in the background to refresh `monmon-pages-<BUILD_HASH>`.
       - If **no cached copy exists**, let the network request run to completion without a 2.5s or 10s cutoff.
   - **NomMon cache-key canonicalization:** Normalize `/api/nommon/plan?...` (when `force` is not `true`) to canonical cache key `/api/nommon/plan` so that cached/pre-warmed plans match regardless of local `budget`/`people`/`postcode` query parameters. Never overwrite an existing cached NomMon plan with a response whose JSON has `plan: null` (from `lockedOnly=true` when no plan is locked).

### 5.4 Background Pre-Warming
- Managed by `public/javascripts/offline-mode.js` delegating to the active Service Worker via `postMessage({ type: 'MONMON_PREWARM', urls })`.
- **When it runs:**
  - Only when authenticated (detected via presence of `#main-nav` / authenticated page and not on `/login`, `/waitinglist`, or Time Travel mode), online, and SW is active.
  - Scheduled after `window.load` via `requestIdleCallback` (fallback `setTimeout(..., 2000)`).
  - Throttled using `localStorage.getItem('monmon_last_warm_at')` to at most once every **15 minutes**, except immediately after a `BUILD_HASH` update or when returning from offline to online.
- **Route order (low concurrency: 2 in flight at a time):**
  1. **Priority 1 (Sainsbury's & Primary):** `/spog`, `/nommon`, `/api/nommon/plan?lockedOnly=true`
  2. **Priority 2 (Core Financial Views):** `/myOutgoings`, `/myAccounts`, `/myIncomings`, `/myBalances`, `/savingsPlan`, `/myMonthlyPot`, `/netWorth`, `/transfers`
  3. **Priority 3 (Secondary & Analytics):** `/spendingPace`, `/historicSpent`, `/bank`, `/dig`, `/wasteBin`, `/settings`
- Requests include `credentials: 'same-origin'` and `X-MonMon-Prewarm: 1`.
- Aborts remaining pre-warm URLs immediately if any request fails with a network error or redirects to `/login` or `/waitinglist`.
- Updates `monmon_last_warm_at` in `localStorage` upon completion and notifies clients so the offline banner can display accurate snapshot freshness.
- **Warming Status Indicator (Username Chip):**
  - Inside the top-right `#nav-user-chip` in `nav_header.html`, render a small status slot (`#offline-warm-indicator`, hidden by default).
  - While background pre-warming is actively running, display a small flame emoji (`🔥`) with a helpful tooltip (e.g., `"Warming offline pages…"`).
  - When all pages finish warming, swap it to a small tick (`✓` / `✅`) with tooltip `"Offline pages ready"`, hold briefly (~2–3 seconds), and then smoothly fade out / hide it.
  - If warming aborts early due to network loss/error, hide the indicator cleanly without showing the tick.

### 5.5 Frontend / Backend Version Check & Anti-Stale Update Flow
- On every online page load (and whenever the browser transitions from offline to online), `offline-mode.js` fetches `GET /api/version` with `{ cache: 'no-store' }` and a 2,500ms timeout.
- **If the fetch fails or times out:** Enter offline mode immediately (display persistent offline banner).
- **If the fetch succeeds:**
  - Read `<meta name="monmon-build-hash">`.
  - If `server.buildHash === pageBuildHash`: Confirm online state, clear any `sessionStorage` reload guard (`monmon_updating_to_hash`), and allow normal throttled pre-warming.
  - If `server.buildHash !== pageBuildHash`:
    1. Check `sessionStorage.getItem('monmon_updating_to_hash') === server.buildHash` to guarantee we never enter an infinite reload loop (if already attempted for this hash, log a warning and stop reloading).
    2. Set `sessionStorage.setItem('monmon_updating_to_hash', server.buildHash)`.
    3. Display a brief top notification bar ("Updating MonMon to the latest version…").
    4. Trigger `registration.update()`, instruct the SW via `postMessage({ type: 'MONMON_PURGE_CACHES' })` to delete all `monmon-*` caches (and also delete them via `window.caches` as fallback), await completion (with a 2s safety ceiling), and call `window.location.reload()`.

### 5.6 Persistent Offline Banner & Read-Only UX

#### Banner Placement & Visuals (`nav_header.html`)
- Placed at the top of the viewport as a `position: sticky; top: 0; z-index: 99999;` bar (alongside `#monmon-time-travel-banner`), hidden by default (`display: none`) and revealed whenever `document.body.classList.contains('is-offline')`.
- **Visual distinction from Time Travel (amber/orange):** Uses a cool slate/indigo palette (e.g., `#1e293b` to `#334155` gradient, subtle `#38bdf8` / `#94a3b8` bottom accent border, crisp white text) so Offline Mode and Time Travel are instantly distinguishable.
- **Contents:**
  - **Tasteful inline SVG offline icon** (a clean cloud-off / disconnected icon — inline SVG avoids Font Awesome 5 missing `wifi-slash` glyphs and guarantees immediate crisp rendering).
  - Compact pill badge: `OFFLINE`
  - Status text: `Read-Only Mode` + relative snapshot timestamp (`Last synced 12m ago` / `Cached snapshot`, refreshed every 30s).
  - Action button: `Retry` — probes `GET /api/version` (`no-store`); if reachable, reloads the current page live; if still offline, flashes brief inline feedback ("Still offline").
- **Mobile ergonomics:** Compact single-row / tightly wrapped layout on narrow screens so Sainsbury's shopping list remains prominent underneath.

#### Offline Detection & Recovery
- **Enters offline mode** when any of these occur:
  - `navigator.onLine === false` or `window` `offline` event fires.
  - `GET /api/version` check fails or times out (>2.5s).
  - SW sends `MONMON_OFFLINE_SERVED` for an **HTML navigation** or a failed fast JSON request (slow AI fallback headers `X-MonMon-Cached-Fallback: 1` do *not* trigger global offline mode on their own).
- **Interaction with Time Travel (`monmon_time_travel` cookie)** *(design call)*:
  - Because Time Travel pages are never written to `monmon-pages-*`, any offline navigation serves today's cached **live** snapshot.
  - While `body.is-offline` is active, `nav_header.html` / `offline-mode.js` suppresses `#monmon-time-travel-banner` (preventing sticky banner collision at `top: 0` and preventing live balances from being mislabeled with a past date) and renders the normal live date in `#ticker`. If the `monmon_time_travel` cookie is present while offline, the offline banner notes `"Read-Only Mode (Live snapshot — Time Travel paused offline)"` so the user still has full offline access to NomMon and their cached pages instead of being locked out.
- **Exits offline mode** when:
  - `window` `online` event fires (or user clicks `Retry`) **and** a live `GET /api/version` probe succeeds.
  - If the current page was served from cache while offline, automatically reloads (or if version changed, runs the update flow) so the user immediately sees live data.

#### Read-Only UI Guards (`body.is-offline`)
- **Forms:** Capture-phase `submit` listener on `document` prevents all form submissions unless `form.hasAttribute('data-offline-allowed')`, showing a brief non-blocking toast: `"Offline — editing is disabled in read-only mode."`
- **Mutating buttons & links:**
  - CSS rule dims and marks non-interactive: `button[type="submit"]`, `[data-method="DELETE"]`, `[data-method="PUT"]`, `.btn-delete`, `#btn-regenerate-nommon`, `#nommon-lock-wrapper button`, `#btn-save-postcode`, `#nav-drawer-time-travel-link`, Bank Sync / LegMon action buttons, and Edit/Add modal triggers.
  - Capture-phase click guard blocks `[data-method="DELETE"]`, `[data-method="PUT"]`, and links to edit-only forms (`/editOutgoing/*`, `/editAccount/*`, `/editIncoming/*`, `/editSavingsPlanItem/*`, `/savingsPlan/editItem/*`) with the same brief read-only toast.
- **Server-only widgets:**
  - `#global-search-input`: disabled with placeholder `"Search unavailable offline"`.
  - `waitingListModal.js`: checks `navigator.onLine` / `document.body.classList.contains('is-offline')` before polling `/api/user/accessStatus`; never opens the waiting list modal on network failure.
  - `#monmon-page-loader`: suppresses showing the blocking loader on blocked offline clicks/submits; normal cached link navigations still hide cleanly on `pageshow`.
- **NomMon Offline Experience (`public/javascripts/nommon.js`):**
  - Renders cached `/api/nommon/plan` seamlessly when offline (shows a subtle badge if viewing cached plan).
  - **Explicitly stays interactive offline:** checking/unchecking `.nommon-item-check` items, `Clear Checks`, and switching between `Shopping List`, `7-Day Meal Schedule`, and `Meal Recipes` tabs (all backed by `localStorage` and local DOM).
  - Disables `Regenerate Plan`, `Lock`/`Unlock Plan`, budget/people change triggers (which would invoke Mistral), and postcode Save while offline; skips `api.postcodes.io` lookup when offline.
- **Transfer Window Offline Experience (`public/javascripts/transfers.js`):**
  - **Explicitly stays interactive offline:** checking/unchecking payday transfer completion checkboxes (`input[type="checkbox"][id^="chk-"]`), `Clear Checks`, `Copy` transfer amount buttons, breakdown expand/collapse toggles, and `Next` / `Last` payday window tabs (all backed solely by `localStorage` and local DOM).
- **Monzo Dig (`/dig`):**
  - Self-hosted Leaflet CSS/JS + cached `/api/dig/data` allow the page, category histogram, and transaction stats to load offline; map tiles degrade cleanly without breaking page scripts.

### 5.7 Converting Legacy `GET` Mutating Routes to `DELETE` and `PUT`

#### Backend Route Changes (`modules/monmon-rs/src/main.rs` & handlers)
| Route | Current Methods | New Method | Notes |
|---|---|---|---|
| `/binOutgoing/:id` | `GET`, `POST` | `DELETE` | Archive outgoing |
| `/binAccount/:id` | `GET`, `POST` | `DELETE` | Archive account |
| `/binIncoming/:id` | `GET`, `POST` | `DELETE` | Archive incoming |
| `/balance/:id/delete` | `POST`, `GET` | `DELETE` | Delete balance entry |
| `/binPlan/:id` | `GET`, `POST` | `DELETE` | Archive sharing plan |
| `/binPot/:id` | `GET`, `POST` | `DELETE` | Archive pot |
| `/binPreset/:id` | `GET`, `POST` | `DELETE` | Archive spending preset |
| `/pot/preset/delete/:id` | `POST` | `DELETE` | Archive spending preset (alias) |
| `/binSavingsPlanItem/:id` | `GET` | `DELETE` | Archive savings plan item |
| `/binSavingsPlan/:id` | `GET` | `DELETE` | Archive savings plan |
| `/netWorth/binAsset/:id` | `GET` | `DELETE` | Archive net worth asset |
| `/unpauseOutgoing/:id` | `GET`, `POST` | `PUT` | Unpause outgoing |
| `/plans/updateSavingsPayoffPercentage` | `GET` | `PUT` | Update savings payoff % |

- **Handler response format:** Each converted handler checks `Accept` header:
  - If `Accept` contains `application/json`: returns `200 OK` with `{ "ok": true, "redirect": "<target_path>" }` (and appropriate `401`/`403`/`500` on auth/ownership/db error).
  - Otherwise: returns `303 See Other` with `Location: <target_path>` (compatible with existing redirect expectations).
- **Time Travel read-only middleware:** Already blocks all non-`GET`/`HEAD`/`OPTIONS` methods, so converting these to `DELETE`/`PUT` automatically closes a pre-existing Time Travel read-only gap as a bonus!

#### Frontend Helper & Template Updates
- Add a delegated click handler in `public/javascripts/common.js` for any `<button>` or element with `data-method="DELETE"` or `data-method="PUT"` and `data-action="<url>"` (plus optional `data-confirm="<message>"`):
  1. If `document.body.classList.contains('is-offline')`, block and show the read-only toast.
  2. If `data-confirm` is set and `!window.confirm(msg)`, abort.
  3. Show MonMon loader, call `fetch(action, { method, credentials: 'same-origin', headers: { 'Accept': 'application/json' } })`.
  4. On `res.ok`, parse JSON and navigate to `data.redirect` (or `window.location.reload()`).
  5. On error, hide loader and surface error message.
- Update templates currently using `<a href="/bin...">` or `<form method="POST" action="/bin...">` (`outgoings.html`, `accounts.html`, `incomings.html`, `balances.html`, `plans.html`, `pots.html`, `savings_plan.html`, `net_worth.html`) and `public/javascripts/index.js` (`#savings-slider-val-target`) to use `data-method="DELETE"` / `data-method="PUT"` + `data-action="..."` buttons.
- Update existing test suites referencing the old `GET`/`POST` routes or `a[href*="/unpauseOutgoing/"]` / `form[action*="/binAccount"]` selectors:
  - `modules/tests-contract/net-worth.contract.test.mjs`
  - `modules/tests-e2e/specs/smoke-e2e.spec.js`
  - `modules/tests-e2e/specs/parity-features.spec.js`
  - `modules/tests-e2e/specs/crud-lifecycle.spec.js`
  - (Plus any other contract/E2E specs uncovered during grep in implementation.)

### 5.8 Logout & Security Hygiene
- When the user clicks `/logout` or `/centralLogout` (when online), `offline-mode.js` intercepts the click briefly to send `{ type: 'MONMON_CLEAR_USER_CACHES' }` to the Service Worker (which deletes all `monmon-pages-*` caches and removes `monmon_last_warm_at` from `localStorage`), then proceeds with the logout navigation.
- On the backend, `logout_handler` adds `Clear-Site-Data: "cache"` as an additional defense-in-depth header for HTTPS production (`monmon.dryark.uk`).
- While offline, the Log out drawer link is disabled/marked unavailable (since OIDC logout requires Rauthy), preventing a broken redirect.

### 5.9 Verification & Testing Plan
- **Rust unit tests (`cargo test`):**
  - `GET /api/version` returns `BUILD_HASH` and `Cache-Control: no-store`.
  - `GET /sw.js` injects `BUILD_HASH`, returns `application/javascript` and `no-cache`.
  - `GET /api/nommon/plan?lockedOnly=true` returns `{ plan: null }` when unlocked without invoking Mistral.
  - All 13 converted routes reject `GET` with `405 Method Not Allowed` and succeed with `DELETE` / `PUT`.
  - Time Travel middleware attaches `X-MonMon-Time-Travel: 1` when active.
- **Playwright E2E suite (`modules/tests-e2e/specs/offline-mode.spec.js`):**
  - Self-hosted asset check: zero external requests to `w3schools.com`, `cdnjs.cloudflare.com`, `fonts.googleapis.com`, `fonts.gstatic.com`, or `unpkg.com` on `/spog`, `/nommon`, and `/dig`.
  - Service Worker registration + pre-warming: navigate to `/spog`, wait for pre-warm completion, switch context offline (`context.setOffline(true)`), navigate to `/nommon` and `/myOutgoings`, verify content renders cleanly from cache.
  - Offline banner & read-only UX: verify `#monmon-offline-banner` is visible with SVG icon and read-only badge; verify forms and `DELETE`/`PUT` buttons are disabled/blocked; verify NomMon shopping checkboxes remain interactive and persist in `localStorage`.
  - Lie-Fi timeout test: intercept navigation with a 10s artificial delay, verify cached snapshot renders within ~3s and triggers the offline banner.
  - Version mismatch auto-update test: stub `GET /api/version` to return a different `buildHash`, reload, and verify caches are purged and page triggers update reload (once, without looping).
  - Logout cache purge test: verify `monmon-pages-*` caches are cleared on logout.
  - Full existing contract and E2E suites remain green after `DELETE`/`PUT` conversion.

---

## 6. Left to the Builder

The following implementation details are intentionally left to the builder's discretion:
- Exact visual CSS tokens (light/dark mode adaptation, padding, font sizes) for `#monmon-offline-banner` and the brief read-only toast, provided it stays compact on mobile and visually distinct from the amber Time Travel banner.
- Exact SVG path geometry for the tasteful inline offline icon.
- Exact internal structure of `public/javascripts/offline-mode.js` and `public/javascripts/sw.js` (plain ES5/ES6 `.js`, no CoffeeScript, no bundler required).
- Whether `offline.html` is rendered via Askama or static HTML served by Axum (must match MonMon theme and use only self-hosted assets).
- Exact concurrency/idle-delay tuning for background pre-warming (target ~2 concurrent requests, idle after load).

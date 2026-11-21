## MonMon

- This became my testbed to stress test DHH's claims and manifesto: 

"pencils down, rewrite everything in Rust, get performance gains, don't read the code"

Spoilers: I did end up reading some of the code. Literally how can you not. I also modified about four lines, some config and some small bit in the Rust that deals with logging out. But that was it.

## HOW IT WENT

In short, Gemini 3.8 Flash started with a [vague plan](modules/README.md), then:

- wrote migration / playwright tests for most GETs and some POSTs to compare the Java and Rust versions
- chose to use its own hand rolled auth (monmon-auth) which I then steered back towards RAuthy
- hardcoded a demo user into the rust system
- Did not initially get the notion of a shared header or nav system at all
- The biggest glaring problem was auth: even though I'd heavily hinted to use RAuthy,
Gem went ahead with hardcoding usernames and passwords everywhere, messing up passkeys, and in the finish deployed and ran Rauthy but left it unattached to the app
- and for RAuthy, gem has no qualms about commited secrets into the repo. This repo is public and so generally, this is bad

The 'Ship of Theseus' is, in my view, the only real way for rewrites to work. We didn't have a stunning amount of tests to begin with so only some portions of the ship received the Theseus treatment. 

Generally, it got about 30% of the way there, then I went through and picked the UI apart
and asked Gem to fix them all piecemeal.

And the point? An elaborate way to try and speed up MonMon on my RPi5 (greenstreet)

## VERDICT

Driven mainly by `agy --dangerously-skip-permissions`, the whole rewrite took two and a half days.

Genuinely impressive in 2026-11

---

## Local Development & Testing

### 1. Set up the local environment

- **Rust toolchain** (for `modules/monmon-rs`):
  ```bash
  curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
  source "$HOME/.cargo/env"
  rustc --version && cargo --version
  ```
- **Docker** (for running a local PostgreSQL instance).
- **Node.js 20+** (only needed for the JS unit, contract, and Playwright E2E test suites).

### 2. Start & seed a local PostgreSQL database

Run the helper script to start a local Docker Postgres container (`monmon-local-postgres`), load [`db/schema.sql`](db/schema.sql), and seed the `demo` user from [`db/seed_demo.sql`](db/seed_demo.sql) (idempotent — safe to re-run):

```bash
./db/start_local_db.sh
```

Override defaults with env vars if needed (e.g. if port `5432` is already in use):

```bash
PGPORT=5434 PGPASSWORD=monmon_local ./db/start_local_db.sh
```

> Set `SKIP_DOCKER=1` to target an already-running local PostgreSQL instance, or `NO_SEED=1` to skip seeding demo data.

### 3. Start the app locally

`monmon-rs` serves static assets from `modules/monmon-rs/public/` (keep it synced with the canonical `public/` directory if you edit JS/CSS):

```bash
cp -ru public/* modules/monmon-rs/public/
cd modules/monmon-rs
PORT=9000 \
BASE_URL=http://localhost:9000 \
DB_URL="postgres://postgres:monmon_local@localhost:5432/monmon-play" \
cargo run
```

Then open [http://localhost:9000](http://localhost:9000) and sign in with `demo` / `demo` (configured in `modules/monmon-rs/src/auth.rs`; override via `DEMO_PASSWORD`).

> **Note:** `monmon-rs` reads `.env` automatically if present; avoid running `source .env` directly in bash if values like `SMTP_FROM` contain unquoted spaces or `<...>`.

### 4. Running the tests

| Suite | Requires running app/DB? | Command |
| :--- | :--- | :--- |
| **Rust unit & integration tests** | No | `cd modules/monmon-rs && cargo test` |
| **Frontend JS unit tests** (Node `vm`, mocked DOM) | No | `node --test modules/tests-contract/*.unit.test.mjs` |
| **HTTP contract tests** | Yes (`monmon-rs` + DB, optionally `legmon` / `rauthy`) | `BASE_URL=http://127.0.0.1:9000 ./modules/tests-contract/run.sh` |
| **Playwright E2E browser tests** (Desktop + Mobile Chromium) | Yes (`monmon-rs` + DB) | `BASE_URL=http://localhost:9000 ./modules/tests-e2e/run.sh` |

**First-time Playwright setup:**

```bash
cd modules/tests-e2e && npm install && npx playwright install chromium
```

To run a single Playwright spec:

```bash
BASE_URL=http://localhost:9000 ./modules/tests-e2e/run.sh specs/historic-spent-and-erratic-inspector.spec.js
```

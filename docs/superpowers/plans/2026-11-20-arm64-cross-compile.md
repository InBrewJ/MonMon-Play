# ARM64 Cross-Compilation & Zero-Compile Pi Deployment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enable cross-compiling `monmon-rs`, `legmon-rs`, and `memmon-rs` to `aarch64-unknown-linux-gnu` on this workstation and update the Dockerfiles and `ship/*.sh` scripts so the Raspberry Pi simply packages the prebuilt ARM64 binaries without running `cargo build`.

**Architecture:** Configure `.cargo/config.toml` in each Rust module to use `aarch64-linux-gnu-gcc` for `aarch64-unknown-linux-gnu`, add `./build-arm64.sh` (and update `./build-rust.sh` for native local builds) to stage binaries into `modules/<service>/prebuilt/<service>`, convert the three `Dockerfile`s to runtime-only `debian:bookworm-slim` images that `COPY prebuilt/<service> /app/<service>`, and update `ship/{monmon,legmon,memmon}.sh` to cross-compile locally before `rsync`ing to the Pi.

**Tech Stack:** Rust (`cargo`, `aarch64-unknown-linux-gnu`), GNU ARM64 cross-toolchain (`aarch64-linux-gnu-gcc`), Docker (`debian:bookworm-slim`), Bash (`build-arm64.sh`, `build-rust.sh`, `ship/*.sh`).

**Spec:** `docs/superpowers/specs/2026-11-20-arm64-cross-compile-design.md`

## Global Constraints

- Never execute `ship/*.sh` against the live Raspberry Pi (`AGENTS.md`: "never under any circumstances do you run deploy scripts. That is human work - test out deployment artifacts locally, by all means, but those artefacts are pushed to live by a human").
- Keep `prebuilt/` git-ignored so binary blobs are not committed to git, while `rsync` in `ship/*.sh` transfers `modules/<service>/prebuilt/<service>` to the Pi.

## Review Focus

- `build.rs` in `monmon-rs`: `monmon-rs` embeds `build.properties` (`build.commit`, `build.number`, `build.timestamp`) and Askama HTML templates at compile time, so `build-arm64.sh` must generate `modules/monmon-rs/build.properties` *before* running `cargo build --release --target aarch64-unknown-linux-gnu`.
- `rsync --delete` in `ship/{monmon,legmon,memmon}.sh`: `modules/<service>/prebuilt/<service>` must be inside `modules/<service>/` and not excluded by `rsync`, so `rsync` sends the freshly compiled ARM64 binary directly into the Pi's Docker build context.
- Architecture verification: `build-arm64.sh` must verify that the staged binary in `prebuilt/<service>` is an `ARM aarch64` ELF binary (`file` / `readelf`) so an x86_64 binary is never accidentally shipped to the Pi.
- `GLIBC` version ceiling: verify `monmon-rs`, `legmon-rs`, and `memmon-rs` ARM64 binaries require at most `GLIBC_2.36` (the version in `debian:bookworm-slim`).

---

### Task 1: Configure `.cargo/config.toml` and `.gitignore` for ARM64 Cross-Compilation

**Files:**
- Create: `modules/monmon-rs/.cargo/config.toml`
- Create: `modules/legmon-rs/.cargo/config.toml`
- Create: `modules/memmon-rs/.cargo/config.toml`
- Modify: `.gitignore`

- [ ] **Step 1: Add `.cargo/config.toml` with `linker = "aarch64-linux-gnu-gcc"` in each module and ignore `modules/*/prebuilt/` in `.gitignore`**

---

### Task 2: Create `build-arm64.sh` and Update `build-rust.sh` & Runtime `Dockerfile`s

**Files:**
- Create: `build-arm64.sh`
- Modify: `build-rust.sh`
- Modify: `modules/monmon-rs/Dockerfile`
- Modify: `modules/legmon-rs/Dockerfile`
- Modify: `modules/memmon-rs/Dockerfile`

- [ ] **Step 1: Create `build-arm64.sh` supporting `./build-arm64.sh [monmon|legmon|memmon|all]`**
- [ ] **Step 2: Update `build-rust.sh` to also copy native release binaries into `modules/<service>/prebuilt/<service>`**
- [ ] **Step 3: Update `modules/{monmon-rs,legmon-rs,memmon-rs}/Dockerfile` to `COPY prebuilt/<service> /app/<service>` on `debian:bookworm-slim`**

---

### Task 3: Update `ship/monmon.sh`, `ship/legmon.sh`, and `ship/memmon.sh` & Verify ARM64 Artifacts Locally

**Files:**
- Modify: `ship/monmon.sh`
- Modify: `ship/legmon.sh`
- Modify: `ship/memmon.sh`

- [ ] **Step 1: Update `ship/{monmon,legmon,memmon}.sh` to call `./build-arm64.sh <service>` locally before `rsync`ing to the Pi**
- [ ] **Step 2: Run `./build-arm64.sh all` locally and verify all 3 binaries are valid `ELF 64-bit LSB pie executable, ARM aarch64` with `GLIBC <= 2.36` and `bash -n ship/*.sh` passes**

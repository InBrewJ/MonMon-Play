# ARM64 Cross-Compilation & Zero-Compile Pi Deployment — Design Spec

## Intent and Why
Compiling Rust on the Raspberry Pi (`greenstreet.local`) takes a long time and maxes out all CPU cores on the Pi, causing the live `monmon-rs` service to hang during deployments. By cross-compiling the ARM64 (`aarch64-unknown-linux-gnu`) binaries on the x86_64 workstation using `aarch64-linux-gnu-gcc` and shipping the pre-compiled binaries inside `modules/<service>/prebuilt/<service>`, the Pi's Docker build step is reduced to a 1–2 second `COPY` into the slim runtime container image with zero Rust compilation on the Pi.

## Goals & Non-Goals
- **Goals:**
  - Cross-compile `monmon-rs`, `legmon-rs`, and `memmon-rs` for `aarch64-unknown-linux-gnu` on this workstation using standard `aarch64-linux-gnu-gcc`.
  - Stage compiled binaries at `modules/<service>/prebuilt/<service>` (git-ignored) so `rsync` in `ship/monmon.sh`, `ship/legmon.sh`, and `ship/memmon.sh` transfers them automatically to the Pi.
  - Convert `modules/{monmon-rs,legmon-rs,memmon-rs}/Dockerfile` into lightweight runtime-only Dockerfiles that copy `prebuilt/<service>` into `/app/<service>` without running `cargo build` inside Docker.
  - Ensure full `GLIBC` compatibility between the workstation's `aarch64-linux-gnu-gcc` (Ubuntu 24.04, glibc 2.39) and the container runtime base image (or verify max required `GLIBC_` symbol version; if `> 2.36`, use `ubuntu:24.04` or `debian:trixie-slim` as the slim runtime base image so `GLIBC_2.38`/`2.39` symbols are always satisfied).
  - Keep `./build-rust.sh` populating `modules/<service>/prebuilt/<service>` with native x86_64 binaries for local workstation Docker builds, and `./build-arm64.sh` populating `modules/<service>/prebuilt/<service>` with ARM64 binaries for Pi deployments.
- **Non-Goals:**
  - Running `ship/*.sh` against live production from the AI agent (strictly prohibited by `AGENTS.md`).

## Approaches Considered
1. **Option A (Selected): Cross-Compile ARM64 on Workstation + Prebuilt Runtime Dockerfiles:**
   - Configure `.cargo/config.toml` in each Rust module with `[target.aarch64-unknown-linux-gnu] linker = "aarch64-linux-gnu-gcc"`.
   - `./build-arm64.sh [monmon|legmon|memmon|all]` builds `--release --target aarch64-unknown-linux-gnu` and copies the binary to `modules/<service>/prebuilt/<service>`.
   - `ship/{monmon,legmon,memmon}.sh` invokes `./build-arm64.sh <service>` locally, then `rsync`s `modules/<service>/` (including `prebuilt/<service>`) to the Pi and runs `docker compose -f docker-compose.rust.yaml build <service>` (which now finishes in ~2s with 0 compilation).
2. **Option B: Direct Host Bind-Mount of Binary without Docker Build:**
   - Not chosen because Option A preserves the self-contained Docker image lifecycle already used by `ship/*.sh` and `run_prod.sh`.

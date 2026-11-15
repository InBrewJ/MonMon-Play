FROM rust:bookworm AS builder
WORKDIR /build
COPY Cargo.toml Cargo.lock ./
COPY src ./src
RUN --mount=type=cache,id=memmon-cargo-registry,target=/usr/local/cargo/registry \
    --mount=type=cache,id=memmon-cargo-git,target=/usr/local/cargo/git \
    --mount=type=cache,id=memmon-build-target,target=/build/target \
    cargo build --release && \
    cp /build/target/release/memmon-rs /build/memmon-rs

FROM debian:bookworm-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates \
    curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY --from=builder /build/memmon-rs /app/memmon-rs

ENV PORT=9006 \
    HOST=127.0.0.1 \
    RUST_LOG=info

EXPOSE 9006

HEALTHCHECK --interval=5s --timeout=3s --retries=5 \
  CMD curl -f http://127.0.0.1:9006/health || exit 1

CMD ["/app/memmon-rs"]

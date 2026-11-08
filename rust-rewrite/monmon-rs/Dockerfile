# Multi-stage Dockerfile for monmon-rs
FROM rust:1.80-slim-bookworm AS builder

WORKDIR /usr/src/monmon-rs

RUN apt-get update && apt-get install -y --no-install-recommends \
    pkg-config \
    libssl-dev \
    && rm -rf /var/lib/apt/lists/*

COPY Cargo.toml Cargo.lock* ./
RUN mkdir -p src && echo "fn main() {}" > src/main.rs && (cargo build --release || true) && rm -rf src

COPY . .
RUN cargo build --release

# Minimal runtime image
FROM debian:bookworm-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates \
    curl \
    libssl3 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY --from=builder /usr/src/monmon-rs/target/release/monmon-rs /app/monmon-rs
COPY public /app/public

ENV PORT=9000 \
    RUST_LOG=info

EXPOSE 9000

HEALTHCHECK --interval=5s --timeout=3s --retries=5 \
  CMD curl -f http://localhost:9000/health || exit 1

CMD ["/app/monmon-rs"]

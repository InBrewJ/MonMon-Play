# Multi-stage Dockerfile for legmon-rs
FROM rust:1.80-slim-bookworm AS builder

WORKDIR /usr/src/legmon-rs

RUN apt-get update && apt-get install -y --no-install-recommends \
    pkg-config \
    libssl-dev \
    && rm -rf /var/lib/apt/lists/*

COPY Cargo.toml Cargo.lock* ./
RUN mkdir -p src && echo "fn main() {}" > src/main.rs && (cargo build --release || true) && rm -rf src

COPY . .
RUN cargo build --release

# Minimal runtime image with poppler-utils for pdf2text
FROM debian:bookworm-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
    poppler-utils \
    ca-certificates \
    curl \
    libssl3 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY --from=builder /usr/src/legmon-rs/target/release/legmon-rs /app/legmon-rs

ENV PORT=9005 \
    RUST_LOG=info

EXPOSE 9005

HEALTHCHECK --interval=5s --timeout=3s --retries=5 \
  CMD curl -f http://localhost:9005/health || exit 1

CMD ["/app/legmon-rs"]

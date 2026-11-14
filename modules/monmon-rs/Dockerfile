FROM rust:bookworm AS builder
WORKDIR /build
ARG BUILD_COMMIT
ARG BUILD_NUMBER
ARG BUILD_TIMESTAMP
ENV BUILD_COMMIT=$BUILD_COMMIT
ENV BUILD_NUMBER=$BUILD_NUMBER
ENV BUILD_TIMESTAMP=$BUILD_TIMESTAMP
COPY Cargo.toml Cargo.lock build.rs build.properties* bank-holidays-uk.json* ./
COPY templates ./templates
COPY src ./src
RUN --mount=type=cache,id=monmon-cargo-registry,target=/usr/local/cargo/registry \
    --mount=type=cache,id=monmon-cargo-git,target=/usr/local/cargo/git \
    --mount=type=cache,id=monmon-build-target,target=/build/target \
    cargo build --release && \
    cp /build/target/release/monmon-rs /build/monmon-rs

FROM debian:bookworm-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates \
    curl \
    libssl3 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY --from=builder /build/monmon-rs /app/monmon-rs
COPY public /app/public

ENV PORT=9000 \
    RUST_LOG=info

EXPOSE 9000

HEALTHCHECK --interval=5s --timeout=3s --retries=5 \
  CMD curl -f http://localhost:9000/health || exit 1

CMD ["/app/monmon-rs"]

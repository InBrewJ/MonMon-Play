FROM debian:bookworm-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates \
    curl \
    libssl3 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY target/release/monmon-rs /app/monmon-rs
COPY public /app/public

ENV PORT=9001 \
    RUST_LOG=info

EXPOSE 9001

HEALTHCHECK --interval=5s --timeout=3s --retries=5 \
  CMD curl -f http://localhost:9001/health || exit 1

CMD ["/app/monmon-rs"]

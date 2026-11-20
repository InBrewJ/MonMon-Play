FROM ubuntu:24.04

RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates \
    curl \
    libssl3 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY prebuilt/monmon-rs /app/monmon-rs
COPY public /app/public

ENV PORT=9000 \
    RUST_LOG=info

EXPOSE 9000

HEALTHCHECK --interval=5s --timeout=3s --retries=5 \
  CMD curl -f http://localhost:9000/health || exit 1

CMD ["/app/monmon-rs"]

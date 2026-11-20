FROM ubuntu:24.04

RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates \
    curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY prebuilt/memmon-rs /app/memmon-rs

ENV PORT=9006 \
    HOST=127.0.0.1 \
    RUST_LOG=info

EXPOSE 9006

HEALTHCHECK --interval=5s --timeout=3s --retries=5 \
  CMD curl -f http://127.0.0.1:9006/health || exit 1

CMD ["/app/memmon-rs"]

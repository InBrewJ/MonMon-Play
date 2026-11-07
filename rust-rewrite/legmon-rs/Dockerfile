FROM debian:bookworm-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
    poppler-utils \
    ca-certificates \
    curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY target/release/legmon-rs /app/legmon-rs

EXPOSE 9005

CMD ["/app/legmon-rs"]

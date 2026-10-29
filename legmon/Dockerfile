FROM eclipse-temurin:11-jre-focal

# Install OCR & PDF tools (Tesseract, pdftotext / poppler-utils, curl)
RUN apt-get update && apt-get install -y --no-install-recommends \
    tesseract-ocr \
    tesseract-ocr-eng \
    poppler-utils \
    curl \
    ca-certificates \
    tar \
    && rm -rf /var/lib/apt/lists/*

# Install xberg CLI from https://github.com/xberg-io/xberg/releases
RUN mkdir -p /opt/xberg && \
    (curl -sL "https://github.com/xberg-io/xberg/releases/download/v1.3.10/xberg-cli-x86_64-unknown-linux-musl.tar.gz" | tar -xz -C /opt/xberg --strip-components=1 && \
     chmod +x /opt/xberg/xberg /opt/xberg/xberg.bin && \
     printf '#!/bin/sh\nexec /opt/xberg/xberg "$@"\n' > /usr/local/bin/xberg && \
     chmod +x /usr/local/bin/xberg || true)

ENV PATH="/opt/xberg:${PATH}"

WORKDIR /app
RUN mkdir -p /app/logs

# Copy assembly jar
COPY target/scala-2.13/legmon-assembly-1.0.0.jar /app/legmon.jar

EXPOSE 9005

CMD ["java", "-Duser.dir=/app", "-jar", "/app/legmon.jar"]

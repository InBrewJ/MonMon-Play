FROM eclipse-temurin:11-jre-focal

# Install curl and unzip for health checks and archive extraction
RUN apt-get update && apt-get install -y --no-install-recommends \
    curl \
    unzip \
    bash \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Copy the built distribution zip from target/universal/
COPY target/universal/monmon-play-java--1.0-SNAPSHOT.zip /tmp/monmon.zip

# Unpack and make entry script executable
RUN unzip -q /tmp/monmon.zip -d /tmp/unpacked \
    && mv /tmp/unpacked/monmon-play-java--1.0-SNAPSHOT/* /app/ \
    && rm -rf /tmp/monmon.zip /tmp/unpacked \
    && chmod +x /app/bin/monmon-play-java-

EXPOSE 9000

# Remove any stale RUNNING_PID on container startup and launch Play
ENTRYPOINT ["/bin/bash", "-c", "rm -f /app/RUNNING_PID && exec /app/bin/monmon-play-java- -Dconfig.resource=prod.conf -Dhttp.port=9000 \"$@\"", "--"]

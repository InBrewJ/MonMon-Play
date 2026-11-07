FROM eclipse-temurin:21-jre-jammy

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
ENTRYPOINT ["/bin/bash", "-c", "rm -f /app/RUNNING_PID && exec /app/bin/monmon-play-java- -J-Xms256m -J-Xmx512m -J--add-opens=java.base/java.lang=ALL-UNNAMED -J--add-opens=java.base/java.lang.invoke=ALL-UNNAMED -J--add-opens=java.base/java.lang.reflect=ALL-UNNAMED -J--add-opens=java.base/java.io=ALL-UNNAMED -J--add-opens=java.base/java.net=ALL-UNNAMED -J--add-opens=java.base/java.nio=ALL-UNNAMED -J--add-opens=java.base/java.util=ALL-UNNAMED -J--add-opens=java.base/java.util.concurrent=ALL-UNNAMED -J--add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED -J--add-opens=java.base/sun.nio.ch=ALL-UNNAMED -J--add-opens=java.base/sun.security.ssl=ALL-UNNAMED -J--add-opens=java.base/sun.security.util=ALL-UNNAMED -J--add-opens=java.base/sun.net.www.protocol.file=ALL-UNNAMED -J--add-opens=java.base/sun.net.www.protocol.jar=ALL-UNNAMED -J--add-exports=java.base/sun.net.www.protocol.file=ALL-UNNAMED -J--add-exports=java.base/sun.net.www.protocol.jar=ALL-UNNAMED -Dconfig.resource=prod.conf -Dhttp.port=9000 \"$@\"", "--"]

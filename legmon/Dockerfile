FROM eclipse-temurin:21-jre-jammy

RUN apt-get update && apt-get install -y --no-install-recommends \
    tesseract-ocr \
    tesseract-ocr-eng \
    poppler-utils \
    curl \
    ca-certificates \
    tar \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
RUN mkdir -p /app/logs

COPY target/scala-2.13/legmon-assembly-1.0.0.jar /app/legmon.jar

EXPOSE 9005

CMD ["java", "-Xms64m", "-Xmx192m", "-Duser.dir=/app", "-jar", "/app/legmon.jar"]

# Build: from this repo's root (with the app repo cloned alongside at ../cuk-moa-kmp)
#   ./gradlew fatJar
#   docker build -t cuk-moa-ktor .
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY build/libs/server-all.jar /app/server.jar
ENV DATABASE_PATH=/data/announcements.db \
    PORT=8080 \
    SCRAPE_INTERVAL_MIN=30
VOLUME /data
EXPOSE 8080
# Sized for t3.small (2GB). Lower -Xmx on machines with less memory.
CMD ["java", "-Xmx512m", "-jar", "/app/server.jar"]

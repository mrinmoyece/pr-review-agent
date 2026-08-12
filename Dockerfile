# ── Stage 1: Build ──────────────────────────────────────────────────────────
FROM eclipse-temurin:25-jdk-jammy@sha256:f122992af75e61d87892f8a37c60f7cfa498b18748c1c9f8563da9a3b1893278 AS builder
WORKDIR /build

# Copy Gradle wrapper + build descriptor first (layer cache — deps only re-download if these change)
COPY gradlew .
COPY gradle/ gradle/
COPY build.gradle.kts settings.gradle.kts ./

# Pre-download dependencies (cached unless build.gradle.kts changes)
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon --quiet 2>/dev/null || true

# Copy source and build
COPY src/ src/
RUN ./gradlew bootJar --no-daemon -x test

# ── Stage 2: Runtime ─────────────────────────────────────────────────────────
FROM eclipse-temurin:25-jre-alpine@sha256:28db6fdf60e38945e43d840c0333aeaec66c15943070104f7586fd3c9d1665b0 AS runtime

LABEL org.opencontainers.image.title="pr-review-agent"
LABEL org.opencontainers.image.description="AI-powered GitHub PR review agent"
LABEL org.opencontainers.image.vendor="AgentForge"

RUN addgroup -g 1001 -S appgroup \
 && adduser -u 1001 -S -D -H -G appgroup appuser \
 && apk upgrade --no-cache \
 && apk add --no-cache curl

WORKDIR /app

COPY --from=builder /build/build/libs/*.jar app.jar

RUN chown -R appuser:appgroup /app

USER appuser

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD curl --fail --silent http://localhost:9090/actuator/health >/dev/null || exit 1

ENTRYPOINT ["java", \
  "-XX:+UseZGC", \
  "-XX:MaxRAMPercentage=75.0", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]

# ── Stage 1: Build ──────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-jammy@sha256:55fb9bf738f5d9b4a6c01b39337e3070d3e27370dd3c478fd1d5d3cd2233c6d8 AS builder
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
FROM eclipse-temurin:21-jre-alpine@sha256:3f08b13888f595cc49edabea7250ba69499ba25602b267da591720769400e08c AS runtime

LABEL org.opencontainers.image.title="pr-review-agent"
LABEL org.opencontainers.image.description="AI-powered GitHub PR review agent"
LABEL org.opencontainers.image.vendor="AgentForge"

RUN addgroup -g 1001 -S appgroup \
 && adduser -u 1001 -S -D -H -G appgroup appuser \
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

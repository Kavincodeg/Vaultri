# Stage 1: Build application bootJar with Gradle image and JDK 21
FROM gradle:8.12-jdk21 AS builder
WORKDIR /workspace

# Copy Gradle wrapper and configuration files first to leverage build cache
COPY gradlew gradlew
COPY gradle/ gradle/
COPY build.gradle.kts settings.gradle.kts ./

# Copy application source code
COPY src/ src/

# Build Spring Boot executable jar using installed gradle
RUN gradle bootJar -x test --no-daemon

# Stage 2: Minimal runtime image
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Run as non-root user
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser:appgroup

# Copy executable bootJar (excluding -plain.jar)
COPY --from=builder --chown=appuser:appgroup /workspace/build/libs/core-api-0.0.1-SNAPSHOT.jar app.jar

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -Dserver.port=${PORT:-8080} -jar app.jar"]

# ==============================================================================
# Stage 1: Build Executable Fat JAR
# ==============================================================================
FROM maven:3.9.9-eclipse-temurin-21-alpine AS builder

WORKDIR /build

# Cache Maven dependencies
COPY pom.xml .
RUN mvn dependency:go-offline -B || true

# Copy source code and build fat JAR
COPY src ./src
RUN mvn clean package -DskipTests -B

# ==============================================================================
# Stage 2: Minimal Production Runtime Image
# ==============================================================================
FROM eclipse-temurin:21-jre-alpine

LABEL maintainer="monisha"
LABEL description="JCloudSync - Enterprise Java 21 Cloud Synchronization Engine"

# Create non-root user for security
RUN addgroup -S jcloud && adduser -S jcloud -G jcloud

WORKDIR /app

# Copy executable fat JAR from builder stage
COPY --from=builder /build/target/jcloud-sync-1.0.0-SNAPSHOT.jar /app/jcloud-sync.jar

# Copy default config template
COPY config.yaml /app/config.yaml

# Set up storage directories and file permissions
RUN mkdir -p /app/sync-folder && chown -R jcloud:jcloud /app

USER jcloud

# Expose metrics port
EXPOSE 9090

VOLUME ["/app/sync-folder"]

ENTRYPOINT ["java", "-jar", "/app/jcloud-sync.jar"]
CMD ["watch"]

# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
# MULTI-STAGE BUILD for the Eureka Server
# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
#
# Stage 1 (builder): Compiles the project and creates the JAR.
#   Uses a full JDK image with Maven.
#
# Stage 2 (runtime): Runs the JAR with a minimal JRE.
#   The final image is ~300MB smaller because it doesn't include
#   the JDK compiler, Maven, or source files.

# ── Stage 1: Build ─────────────────────────────────────────────
FROM eclipse-temurin:25-jdk AS builder

# Install Maven (not included in the base JDK image).
RUN apt-get update && \
    apt-get install -y --no-install-recommends maven && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /build

# Copy the parent POM first (for dependency resolution).
# Maven needs the parent POM to resolve the <parent> block
# in the eureka-server's POM.
COPY pom.xml ./pom.xml
COPY eureka-server/pom.xml ./eureka-server/pom.xml

# Download dependencies (cached by Docker layer caching).
# The -pl flag tells Maven: "Only process the eureka-server module."
# The -am flag means "also make" — build any modules it depends on (the parent).
WORKDIR /build/eureka-server
RUN --mount=type=cache,target=/root/.m2 \
    mvn dependency:go-offline -B

# Copy source code and build.
COPY eureka-server/src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn package -DskipTests -B

# ── Stage 2: Runtime ─────────────────────────────────────────── \
FROM eclipse-temurin:25-jdk

WORKDIR /app

# Copy the fat JAR from the builder stage.
COPY --from=builder /build/eureka-server/target/*.jar app.jar

# Expose the Eureka port (documentation for docker-compose and operators).
EXPOSE 8761

# Run with sensible JVM flags for containers.
# -XX:+UseContainerSupport: JVM respects container memory/CPU limits.
# -XX:MaxRAMPercentage=75: Use at most 75% of container's memory for heap.
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
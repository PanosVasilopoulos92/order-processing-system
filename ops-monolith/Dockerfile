# Multi-stage build for the OPS Monolith.
# Same pattern as ops-auth — depends on ops-common.

FROM eclipse-temurin:25-jdk AS builder

RUN apt-get update && \
    apt-get install -y --no-install-recommends maven && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /build

# Copy parent POM, ops-common POM (dependency), and this module's POM.
COPY pom.xml ./pom.xml
COPY ops-common/pom.xml ./ops-common/pom.xml
COPY ops-monolith/pom.xml ./ops-monolith/pom.xml

# Build ops-common first (ops-monolith depends on it).
COPY ops-common/src ./ops-common/src
RUN --mount=type=cache,target=/root/.m2 \
    mvn install -pl ops-common -B -DskipTests

# Download ops-monolith dependencies then build.
WORKDIR /build/ops-monolith
RUN --mount=type=cache,target=/root/.m2 \
    mvn dependency:go-offline -B

COPY ops-monolith/src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn package -DskipTests -B

# Runtime
FROM eclipse-temurin:25-jdk

WORKDIR /app
COPY --from=builder /build/ops-monolith/target/*.jar app.jar

EXPOSE 8888

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]

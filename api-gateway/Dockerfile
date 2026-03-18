FROM eclipse-temurin:25-jdk AS builder

RUN apt-get update && \
    apt-get install -y --no-install-recommends maven && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /build

COPY pom.xml ./pom.xml
COPY api-gateway/pom.xml ./api-gateway/pom.xml

WORKDIR /build/api-gateway
RUN --mount=type=cache,target=/root/.m2 \
    mvn dependency:go-offline -B

COPY api-gateway/src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn package -DskipTests -B

FROM eclipse-temurin:25-jdk

WORKDIR /app
COPY --from=builder /build/api-gateway/target/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
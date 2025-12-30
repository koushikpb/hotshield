# syntax=docker/dockerfile:1
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
COPY src src
# The cache mount keeps the Gradle distribution and dependency jars across builds, which is what
# the old separate "dependencies" layer was for. It also survives build-file changes, so that
# layer would only add a step without saving a download.
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon bootJar -x test

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 10001 hotshield
COPY --from=build /src/build/libs/app.jar app.jar
USER hotshield
EXPOSE 8080
# The JVM picks SerialGC when it sees fewer than 2 CPUs or under ~1.8 GB, which small Fargate
# tasks are, while a laptop container gets G1. Pinning G1 keeps local and Fargate p99s from coming
# from different collectors. MaxRAMPercentage sizes the heap from the container limit, not the
# host. ExitOnOutOfMemoryError lets ECS replace a broken task instead of leaving it half alive.
ENTRYPOINT ["java", "-XX:+UseG1GC", "-XX:+ExitOnOutOfMemoryError", "-XX:MaxRAMPercentage=75", \
            "-jar", "/app/app.jar"]

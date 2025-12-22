FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
# Copy the build files first so the dependency download layer is reused when only sources change.
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN ./gradlew --no-daemon dependencies > /dev/null
COPY src src
RUN ./gradlew --no-daemon bootJar -x test

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 10001 hotshield
COPY --from=build /src/build/libs/hotshield-*.jar app.jar
USER hotshield
EXPOSE 8080
# Size the heap from the container limit, not the host, so Fargate task sizes behave predictably.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]

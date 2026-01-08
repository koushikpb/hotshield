import java.time.Duration

plugins {
    java
    id("org.springframework.boot") version "3.5.16"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "dev.hotshield"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("com.github.ben-manes.caffeine:caffeine")
    implementation("io.micrometer:micrometer-registry-prometheus")
    // Annotations only; the Java agent does the tracing at runtime and ignores them when it is not attached.
    implementation("io.opentelemetry.instrumentation:opentelemetry-instrumentation-annotations:2.32.0")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// A fixed name so the Dockerfile doesn't need a glob that breaks on a version bump.
tasks.bootJar {
    archiveFileName = "app.jar"
}

// A SingleFlight that runs the loader under a monitor or inside computeIfAbsent can pin every carrier thread
// and hang the whole test JVM, which no per-test timeout can interrupt. This turns that hang into a failure.
tasks.test {
    timeout = Duration.ofMinutes(3)
}

import org.springframework.boot.gradle.tasks.bundling.BootJar

plugins {
    java
    id("org.springframework.boot") version "3.5.16"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.owasp.dependencycheck") version "12.2.2"
    id("com.github.spotbugs") version "6.5.6"
    checkstyle
    jacoco
}

group   = "com.agentforge"
version = "1.0.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
    named("spotbugs") {
        resolutionStrategy.force("org.apache.commons:commons-lang3:3.20.0")
    }
}

repositories {
    mavenCentral()
}

val azureOpenAiVersion = "1.0.0-beta.16"
val resilience4jVersion = "2.4.0"
extra["commons-lang3.version"] = "3.20.0"
extra["netty.version"] = "4.1.136.Final"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-aop")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    implementation("com.azure:azure-ai-openai:$azureOpenAiVersion")

    implementation("io.github.resilience4j:resilience4j-spring-boot3:$resilience4jVersion")
    implementation("io.github.resilience4j:resilience4j-circuitbreaker:$resilience4jVersion")
    implementation("io.github.resilience4j:resilience4j-retry:$resilience4jVersion")

    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation(platform("tools.jackson:jackson-bom:3.2.1"))
    implementation("net.logstash.logback:logstash-logback-encoder:9.0")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("com.github.ben-manes.caffeine:caffeine")

    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.mockito:mockito-core")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.named<BootJar>("bootJar") {
    archiveFileName.set("${project.name}-${project.version}.jar")
}

// ── Code quality: Checkstyle ──────────────────────────────────────────────────
checkstyle {
    toolVersion = "10.20.2"
    configFile = file("config/checkstyle/checkstyle.xml")
    isIgnoreFailures = false
    maxWarnings = 0
}

// ── Code quality: SpotBugs ────────────────────────────────────────────────────
spotbugs {
    ignoreFailures.set(false)
    reportLevel.set(com.github.spotbugs.snom.Confidence.HIGH)
}

tasks.spotbugsMain {
    reports.create("html") { required.set(true) }
}

tasks.spotbugsTest {
    reports.create("html") { required.set(true) }
}

// ── Coverage: JaCoCo ──
jacoco {
    toolVersion = "0.8.12"
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

tasks.test {
    finalizedBy(tasks.jacocoTestReport)
}

// ── SCA: OWASP Dependency-Check — fails the build on High/Critical findings.
// Set an NVD_API_KEY repo secret to avoid NVD rate-limiting on the CVE feed sync.
dependencyCheck {
    failBuildOnCVSS = 7.0f
    autoUpdate = providers.gradleProperty("dependencyCheckAutoUpdate")
        .map(String::toBoolean)
        .getOrElse(true)
    nvd {
        apiKey = System.getenv("NVD_API_KEY")
    }
}

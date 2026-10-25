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

// ── Global CVE overrides (applies to ALL configurations incl. checkstyle, spotbugs, owasp) ──
// Forces patched versions of transitive deps flagged by Dependabot / Trivy.
configurations.all {
    resolutionStrategy.eachDependency {
        when ("${requested.group}:${requested.name}") {
            // CVE-2026-54399, CVE-2026-54428 — Apache HttpComponents Core 5
            "org.apache.httpcomponents.core5:httpcore5"    -> useVersion("5.4.3")
            "org.apache.httpcomponents.core5:httpcore5-h2" -> useVersion("5.4.3")
            // CVE-2026-64607 — Apache HttpClient 5
            "org.apache.httpcomponents.client5:httpclient5" -> useVersion("5.6.3")
            // CVE-2026-71497 — jsoup
            "org.jsoup:jsoup"                              -> useVersion("1.23.1")
            // CVE-2025-67030 — Plexus Utils
            "org.codehaus.plexus:plexus-utils"             -> useVersion("3.6.1")
            // CVE-2025-48734 — Commons BeanUtils
            "commons-beanutils:commons-beanutils"          -> useVersion("1.11.0")
        }
    }
}

repositories {
    mavenCentral()
}

val azureOpenAiVersion = "1.0.0-beta.16"
val resilience4jVersion = "2.4.0"
extra["commons-lang3.version"] = "3.20.0"
extra["jackson-bom.version"] = "2.21.5"
// Netty 4.1.137.Final: fixes CVE-2026-59903 (CorsHandler Vary-header override).
extra["netty.version"] = "4.1.137.Final"
// log4j2 2.25.5: fixes CVE-2026-49844 (non-finite float JSON encoding).
extra["log4j2.version"] = "2.25.5"

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
    implementation(platform("tools.jackson:jackson-bom:3.2.4"))
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

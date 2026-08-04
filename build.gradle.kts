import org.springframework.boot.gradle.tasks.bundling.BootJar

plugins {
    java
    id("org.springframework.boot") version "3.4.6"
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
}

repositories {
    mavenCentral()
}

extra["azureOpenAiVersion"]  = "1.0.0-beta.16"
extra["resilience4jVersion"] = "2.2.0"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-aop")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    implementation("com.azure:azure-ai-openai:${extra["azureOpenAiVersion"]}")

    implementation("io.github.resilience4j:resilience4j-spring-boot3:${extra["resilience4jVersion"]}")
    implementation("io.github.resilience4j:resilience4j-circuitbreaker:${extra["resilience4jVersion"]}")
    implementation("io.github.resilience4j:resilience4j-retry:${extra["resilience4jVersion"]}")

    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("net.logstash.logback:logstash-logback-encoder:7.4")
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

// ── Code quality: Checkstyle (advisory — report-only until baseline is clean) ──
checkstyle {
    toolVersion = "10.20.2"
    configFile = file("config/checkstyle/checkstyle.xml")
    isIgnoreFailures = true
    maxWarnings = Int.MAX_VALUE
}

// ── Code quality: SpotBugs (advisory — report-only until baseline is clean) ──
spotbugs {
    ignoreFailures.set(true)
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

// ── SCA: OWASP Dependency-Check — fails the build on Critical (CVSS >= 9) findings.
// Set an NVD_API_KEY repo secret to avoid NVD rate-limiting on the CVE feed sync.
dependencyCheck {
    failBuildOnCVSS = 9.0f
    nvd {
        apiKey = System.getenv("NVD_API_KEY")
    }
}

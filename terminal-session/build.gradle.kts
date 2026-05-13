plugins {
    id("java-library")
    kotlin("jvm")
}

group = "com.gagik"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    api(project(":terminal-core"))
    api(project(":terminal-parser"))
    api(project(":terminal-integration"))
    api(project(":terminal-input"))
    api(project(":terminal-render-api"))
    api(project(":terminal-render-cache"))
    api(project(":terminal-transport-api"))
    implementation(project(":terminal-protocol"))

    testImplementation(project(":terminal-testkit"))
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:6.0.0")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}

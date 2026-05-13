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
    implementation(project(":terminal-core"))
    implementation(project(":terminal-integration"))
    implementation(project(":terminal-input"))
    api(project(":terminal-session"))
    implementation(project(":terminal-transport-api"))
    implementation("org.jetbrains.pty4j:pty4j:0.13.12")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:6.0.0")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}

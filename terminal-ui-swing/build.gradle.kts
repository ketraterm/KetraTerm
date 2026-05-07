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
    api(project(":terminal-session"))
    implementation(project(":terminal-input"))
    implementation(project(":terminal-render-api"))
    implementation(project(":terminal-render-cache"))

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:6.0.0")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}

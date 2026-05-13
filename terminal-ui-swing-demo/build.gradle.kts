plugins {
    application
    kotlin("jvm")
}

group = "com.gagik"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":terminal-pty"))
    implementation(project(":terminal-ui-swing"))
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.gagik.terminal.ui.swing.demo.TerminalSwingDemoKt")
}

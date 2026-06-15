import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.changelog")
    id("org.jetbrains.intellij.platform")
}

private val jvtermVersion = "0.1.0-alpha01-SNAPSHOT"

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    implementation("io.github.jvterm:jvterm-ui-swing:$jvtermVersion")
    implementation("io.github.jvterm:jvterm-workspace:$jvtermVersion") {
        exclude(group = "org.jetbrains.pty4j", module = "pty4j")
        exclude(group = "net.java.dev.jna", module = "jna")
        exclude(group = "net.java.dev.jna", module = "jna-platform")
    }
    runtimeOnly("org.slf4j:slf4j-nop:2.0.18")

    testImplementation(libs.junit)

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        intellijIdea("2025.3.5")
        testFramework(TestFrameworkType.Platform)

        // Add plugin dependencies for compilation here, for example:
        // bundledPlugin("com.intellij.java")
    }
}

intellijPlatform {
    pluginConfiguration {
        version.set(project.version.toString())

        ideaVersion {
            sinceBuild.set("253")
        }
    }
}

kotlin {
    jvmToolchain(21)
}

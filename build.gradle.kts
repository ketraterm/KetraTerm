plugins {
    kotlin("jvm") version "2.2.21" apply false
    id("com.diffplug.spotless") version "8.6.0"
}

subprojects {
    repositories {
        mavenCentral()
    }

    apply(plugin = "com.diffplug.spotless")

    configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        kotlin {
            target("src/**/*.kt")
            ktlint("1.3.1")
            licenseHeaderFile(rootProject.file("gradle/license-header.txt"))
        }
        kotlinGradle {
            target("*.gradle.kts")
            ktlint("1.3.1")
        }
    }
}



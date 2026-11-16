/*
 * Copyright 2026 Gagik Sargsyan
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.gradle.api.artifacts.component.ModuleComponentIdentifier

plugins {
    application
    kotlin("jvm")
}

val libraryVersion = providers.gradleProperty("libraryVersion").get()
repositories {
    val libraryRepository = providers.gradleProperty("libraryRepository").orNull
    if (libraryRepository != null) {
        exclusiveContent {
            forRepository { maven { url = uri(libraryRepository) } }
            filter { includeGroup("io.github.ketraterm") }
        }
    } else if (libraryVersion.endsWith("-SNAPSHOT")) {
        maven("https://central.sonatype.com/repository/maven-snapshots/") {
            content { includeGroup("io.github.ketraterm") }
            mavenContent { snapshotsOnly() }
        }
    }
    mavenCentral()
}
configurations.configureEach { resolutionStrategy.cacheChangingModulesFor(0, "seconds") }
kotlin { jvmToolchain(25) }

dependencies {
    implementation(platform("io.github.ketraterm:ketraterm-bom:$libraryVersion"))
    implementation("io.github.ketraterm:ketraterm-swing")
    implementation("io.github.ketraterm:ketraterm-ui-swing-host")
    implementation("io.github.ketraterm:ketraterm-pty")
    implementation("io.github.ketraterm:ketraterm-shell-launch")
    implementation("io.github.ketraterm:ketraterm-completion-host")
    implementation("io.github.ketraterm:ketraterm-completion-session")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    runtimeOnly("org.slf4j:slf4j-nop:2.0.20")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.1.3")
}

application {
    mainClass.set("io.github.ketraterm.examples.host.HostIntegrationExampleKt")
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

tasks.test {
    useJUnitPlatform { excludeTags("native", "desktop") }
    systemProperty("java.awt.headless", "true")
}

tasks.register<Test>("desktopTest") {
    group = "verification"
    description = "Tests the example with a real window and PTY."
    testClassesDirs =
        sourceSets.test
            .get()
            .output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("desktop") }
    systemProperty("java.awt.headless", "false")
    systemProperty("junit.jupiter.execution.parallel.enabled", "false")
    systemProperty("junit.jupiter.execution.timeout.default", "2 m")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    outputs.upToDateWhen { false }
}

val verifyDependencies =
    tasks.register("verifyDependencies") {
        val runtime = configurations.runtimeClasspath
        inputs.files(runtime)
        doLast {
            val forbidden = setOf("ketraterm-app", "ketraterm-workspace", "ketraterm-testkit", "ketraterm-benchmarks")
            runtime.get().incoming.resolutionResult.allComponents.forEach { component ->
                val id = component.id
                check(id !is org.gradle.api.artifacts.component.ProjectComponentIdentifier || id.projectPath == ":") {
                    "The example must use Maven artifacts: $id"
                }
                if (id is ModuleComponentIdentifier && id.group == "io.github.ketraterm") {
                    check(id.module !in forbidden) { "The example pulled $id" }
                    check(id.version == libraryVersion) { "The example mixed library versions: $id" }
                }
            }
        }
    }
tasks.check { dependsOn(verifyDependencies) }

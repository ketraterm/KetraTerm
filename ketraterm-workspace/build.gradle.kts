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

plugins {
    kotlin("jvm")
}

dependencies {
    val kotlinxCoroutinesVersion = rootProject.extra["kotlinxCoroutinesVersion"] as String

    api(project(":ketraterm-session"))
    implementation(project(":ketraterm-pty"))
    implementation(project(":ketraterm-shell-integration"))
    implementation(project(":ketraterm-render-api"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinxCoroutinesVersion")

    testImplementation(kotlin("test"))
    testImplementation(project(":ketraterm-testkit"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$kotlinxCoroutinesVersion")
}

val defaultDispatcherTest =
    tasks.register<Test>("defaultDispatcherTest") {
        description = "Checks default workspace startup dispatch with two CPU workers."
        group = "verification"
        testClassesDirs =
            sourceSets.test
                .get()
                .output.classesDirs
        classpath = sourceSets.test.get().runtimeClasspath
        useJUnitPlatform()
        filter { includeTestsMatching("io.github.ketraterm.workspace.TerminalWorkspaceDefaultDispatcherTest") }
        systemProperty("kotlinx.coroutines.scheduler.core.pool.size", "2")
    }

tasks.test {
    useJUnitPlatform()
    exclude("**/TerminalWorkspaceDefaultDispatcherTest.class")
    dependsOn(defaultDispatcherTest)
}

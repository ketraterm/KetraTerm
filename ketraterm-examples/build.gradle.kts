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
    application
    kotlin("jvm")
}

dependencies {
    val coroutinesVersion = rootProject.extra["kotlinxCoroutinesVersion"] as String
    implementation(project(":ketraterm-workspace"))
    implementation(project(":ketraterm-ui-swing-host"))
    implementation(project(":ketraterm-completion-host"))
    runtimeOnly("org.slf4j:slf4j-nop:2.0.20")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$coroutinesVersion")
    testImplementation(project(":ketraterm-testkit"))
    testImplementation(project(":ketraterm-core"))
    testImplementation(project(":ketraterm-shell-integration"))
}

application {
    mainClass.set("io.github.ketraterm.examples.suggestions.SuggestionExampleKt")
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

tasks.test { useJUnitPlatform() }

tasks.named<JavaExec>("run") {
    workingDir(rootProject.projectDir)
}

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

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.formdev:flatlaf:3.7.1")
    implementation("com.formdev:flatlaf-extras:3.7.1")
    implementation(project(":ketraterm-ui-swing"))
    implementation(project(":ketraterm-workspace"))

    runtimeOnly("org.slf4j:slf4j-nop:2.0.18")

    testImplementation(kotlin("test"))
}

application {
    mainClass.set("io.github.ketraterm.app.KetraTermAppKt")
}

tasks.test {
    useJUnitPlatform()
}

tasks.processResources {
    val appVersion = project.version.toString()
    inputs.property("version", appVersion)
    filesMatching("**/version.properties") {
        expand(mapOf("version" to appVersion))
    }
}

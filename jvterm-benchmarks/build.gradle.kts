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
    id("me.champeau.jmh") version "0.7.3"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":jvterm-core"))
    implementation(project(":jvterm-host"))
    implementation(project(":jvterm-parser"))
    implementation(project(":jvterm-render-api"))
    implementation(project(":jvterm-render-cache"))
    implementation(project(":jvterm-protocol"))
    implementation(project(":jvterm-session"))
    implementation(project(":jvterm-input"))
    implementation(project(":jvterm-ui-swing"))

    implementation("org.openjdk.jmh:jmh-core:1.37")
    annotationProcessor("org.openjdk.jmh:jmh-generator-annprocess:1.37")
}

jmh {
    warmupIterations.set(3)
    iterations.set(5)
    fork.set(1)
    benchmarkMode.set(listOf("thrpt"))
    timeUnit.set("ms")
    profilers.set(listOf("gc"))
}

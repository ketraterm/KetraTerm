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

import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.testing.Test

// Modules share a display; serialize their real-window suites even under --parallel.
abstract class DesktopTestDisplay : BuildService<BuildServiceParameters.None>

val desktopDisplay =
    gradle.sharedServices.registerIfAbsent("desktopTestDisplay", DesktopTestDisplay::class) {
        maxParallelUsages.set(1)
    }
val nativeTests =
    tasks.register("nativeTest") {
        group = "verification"
        description = "Runs tests requiring installed tools, real shells, or native PTYs."
    }
val desktopTests =
    tasks.register("desktopTest") {
        group = "verification"
        description = "Runs real-window tests serially on an interactive or virtual display."
    }

subprojects {
    plugins.withId("org.jetbrains.kotlin.jvm") {
        val testSources = extensions.getByType<SourceSetContainer>().named("test")
        tasks.named<Test>("test") {
            useJUnitPlatform { excludeTags("native", "desktop") }
            systemProperty("java.awt.headless", "true")
        }
        for (suite in listOf("native", "desktop")) {
            val suiteTask =
                tasks.register<Test>("${suite}Test") {
                    group = "verification"
                    description = "Runs the $suite test suite in this module."
                    testClassesDirs = testSources.get().output.classesDirs
                    classpath = testSources.get().runtimeClasspath
                    useJUnitPlatform { includeTags(suite) }
                    // Many modules legitimately have no tests in one of the environment-dependent suites.
                    failOnNoDiscoveredTests.set(false)
                    // Installed tools, shell state, and the desktop are not reproducible Gradle inputs.
                    outputs.upToDateWhen { false }
                    outputs.doNotCacheIf("Tests depend on the host environment") { true }
                    maxParallelForks = 1
                    systemProperty("junit.jupiter.execution.parallel.enabled", "false")
                    systemProperty("java.awt.headless", (suite != "desktop").toString())
                    systemProperty("terminal.pty.wsl.distribution", providers.systemProperty("terminal.pty.wsl.distribution").getOrElse(""))
                    systemProperty("terminal.test.requiredShells", providers.systemProperty("terminal.test.requiredShells").getOrElse(""))
                    systemProperty(
                        "terminal.test.artifactsDirectory",
                        layout.buildDirectory
                            .dir("test-artifacts/${suite}Test")
                            .get()
                            .asFile.absolutePath,
                    )
                    shouldRunAfter(tasks.named("test"))
                    if (suite == "desktop") usesService(desktopDisplay)
                }
            if (suite == "native") {
                nativeTests.configure { dependsOn(suiteTask) }
            } else {
                desktopTests.configure { dependsOn(suiteTask) }
            }
        }
    }
}

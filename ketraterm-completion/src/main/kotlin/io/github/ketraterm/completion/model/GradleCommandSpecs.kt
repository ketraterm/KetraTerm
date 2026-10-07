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
package io.github.ketraterm.completion.model

import io.github.ketraterm.completion.internal.GradleCompletionSyntax

/**
 * Curated Gradle command specifications focused on universal project tasks and options.
 */
internal object GradleCommandSpecs {
    fun gradle(): TerminalCommandSpec =
        TerminalCommandSpec(
            name = GradleCompletionSyntax.COMMAND_NAME,
            description = commandSpecText("spec.gradle.description"),
            aliases = listOf("./gradlew", "./gradlew.bat", ".\\gradlew", ".\\gradlew.bat", "gradlew", "gradlew.bat"),
            repeatableSubcommands = true,
            subcommands =
                listOf(
                    TerminalCommandSpec("build", commandSpecText("spec.gradle.build.description")),
                    TerminalCommandSpec("test", commandSpecText("spec.gradle.test.description")),
                    TerminalCommandSpec("check", commandSpecText("spec.gradle.check.description")),
                    TerminalCommandSpec("clean", commandSpecText("spec.gradle.clean.description")),
                    TerminalCommandSpec("tasks", commandSpecText("spec.gradle.tasks.description")),
                    TerminalCommandSpec("run", commandSpecText("spec.gradle.run.description")),
                    TerminalCommandSpec("assemble", commandSpecText("spec.gradle.assemble.description")),
                    TerminalCommandSpec("help", commandSpecText("spec.gradle.help.description")),
                    TerminalCommandSpec("projects", commandSpecText("spec.gradle.projects.description")),
                    TerminalCommandSpec("properties", commandSpecText("spec.gradle.properties.description")),
                    TerminalCommandSpec("dependencies", commandSpecText("spec.gradle.dependencies.description")),
                    TerminalCommandSpec("dependencyInsight", commandSpecText("spec.gradle.dependencyInsight.description")),
                    TerminalCommandSpec("wrapper", commandSpecText("spec.gradle.wrapper.description")),
                ),
            options =
                listOf(
                    TerminalOptionSpec(listOf("--help", "-h"), commandSpecText("spec.gradle.option.--help.description")),
                    TerminalOptionSpec(listOf("--version", "-v"), commandSpecText("spec.gradle.option.--version.description")),
                    TerminalOptionSpec(
                        names = listOf("--console"),
                        description = commandSpecText("spec.gradle.option.--console.description"),
                        requiresValue = true,
                        valueCandidates = listOf("auto", "plain", "rich", "verbose"),
                    ),
                    TerminalOptionSpec(listOf("--info", "-i"), commandSpecText("spec.gradle.option.--info.description")),
                    TerminalOptionSpec(listOf("--debug", "-d"), commandSpecText("spec.gradle.option.--debug.description")),
                    TerminalOptionSpec(listOf("--warn", "-w"), commandSpecText("spec.gradle.option.--warn.description")),
                    TerminalOptionSpec(listOf("--quiet", "-q"), commandSpecText("spec.gradle.option.--quiet.description")),
                    TerminalOptionSpec(listOf("--stacktrace", "-s"), commandSpecText("spec.gradle.option.--stacktrace.description")),
                    TerminalOptionSpec(
                        listOf("--full-stacktrace", "-S"),
                        commandSpecText("spec.gradle.option.--full-stacktrace.description"),
                    ),
                    TerminalOptionSpec(listOf("--scan"), commandSpecText("spec.gradle.option.--scan.description")),
                    TerminalOptionSpec(listOf("--no-scan"), commandSpecText("spec.gradle.option.--no-scan.description")),
                    TerminalOptionSpec(listOf("--build-cache"), commandSpecText("spec.gradle.option.--build-cache.description")),
                    TerminalOptionSpec(listOf("--no-build-cache"), commandSpecText("spec.gradle.option.--no-build-cache.description")),
                    TerminalOptionSpec(
                        listOf("--configuration-cache"),
                        commandSpecText("spec.gradle.option.--configuration-cache.description"),
                    ),
                    TerminalOptionSpec(
                        listOf("--no-configuration-cache"),
                        commandSpecText("spec.gradle.option.--no-configuration-cache.description"),
                    ),
                    TerminalOptionSpec(
                        names = listOf("--configuration-cache-problems"),
                        description = commandSpecText("spec.gradle.option.--configuration-cache-problems.description"),
                        requiresValue = true,
                        valueCandidates = listOf("fail", "warn"),
                    ),
                    TerminalOptionSpec(listOf("--daemon"), commandSpecText("spec.gradle.option.--daemon.description")),
                    TerminalOptionSpec(listOf("--no-daemon"), commandSpecText("spec.gradle.option.--no-daemon.description")),
                    TerminalOptionSpec(listOf("--stop"), commandSpecText("spec.gradle.option.--stop.description")),
                    TerminalOptionSpec(listOf("--status"), commandSpecText("spec.gradle.option.--status.description")),
                    TerminalOptionSpec(listOf("--offline"), commandSpecText("spec.gradle.option.--offline.description")),
                    TerminalOptionSpec(listOf("--parallel"), commandSpecText("spec.gradle.option.--parallel.description")),
                    TerminalOptionSpec(listOf("--no-parallel"), commandSpecText("spec.gradle.option.--no-parallel.description")),
                    TerminalOptionSpec(
                        listOf("--max-workers"),
                        commandSpecText("spec.gradle.option.--max-workers.description"),
                        requiresValue = true,
                    ),
                    TerminalOptionSpec(listOf("--continuous", "-t"), commandSpecText("spec.gradle.option.--continuous.description")),
                    TerminalOptionSpec(
                        listOf("--refresh-dependencies"),
                        commandSpecText("spec.gradle.option.--refresh-dependencies.description"),
                    ),
                    TerminalOptionSpec(listOf("--dry-run", "-m"), commandSpecText("spec.gradle.option.--dry-run.description")),
                    TerminalOptionSpec(listOf("--rerun-tasks"), commandSpecText("spec.gradle.option.--rerun-tasks.description")),
                    TerminalOptionSpec(listOf("--continue"), commandSpecText("spec.gradle.option.--continue.description")),
                    TerminalOptionSpec(
                        listOf("--exclude-task", "-x"),
                        commandSpecText("spec.gradle.option.--exclude-task.description"),
                        requiresValue = true,
                    ),
                    TerminalOptionSpec(
                        names = GradleCompletionSyntax.PROJECT_DIRECTORY_OPTION_NAMES,
                        description = commandSpecText("spec.gradle.option.--project-dir.description"),
                        requiresValue = true,
                        valuePathKind = TerminalPathArgumentKind.DIRECTORY,
                    ),
                    TerminalOptionSpec(
                        names = listOf("--settings-file", "-c"),
                        description = commandSpecText("spec.gradle.option.--settings-file.description"),
                        requiresValue = true,
                        valuePathKind = TerminalPathArgumentKind.FILE,
                    ),
                    TerminalOptionSpec(
                        names = listOf("--build-file", "-b"),
                        description = commandSpecText("spec.gradle.option.--build-file.description"),
                        requiresValue = true,
                        valuePathKind = TerminalPathArgumentKind.FILE,
                    ),
                    TerminalOptionSpec(
                        names = listOf("--init-script", "-I"),
                        description = commandSpecText("spec.gradle.option.--init-script.description"),
                        requiresValue = true,
                        valuePathKind = TerminalPathArgumentKind.FILE,
                    ),
                ),
        )
}

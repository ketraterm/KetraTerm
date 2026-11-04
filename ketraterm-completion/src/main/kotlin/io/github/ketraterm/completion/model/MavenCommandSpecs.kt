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

/** Selected Maven lifecycle phases and reactor options, including wrapper aliases. */
internal object MavenCommandSpecs {
    fun maven(): TerminalCommandSpec =
        TerminalCommandSpec(
            name = "mvn",
            description = commandSpecText("spec.mvn.description"),
            aliases = listOf("mvnw", "./mvnw", "mvnw.cmd", "./mvnw.cmd", ".\\mvnw", ".\\mvnw.cmd"),
            repeatableSubcommands = true,
            subcommands =
                listOf(
                    TerminalCommandSpec(
                        name = "clean",
                        description = commandSpecText("spec.mvn.clean.description"),
                    ),
                    TerminalCommandSpec(
                        name = "validate",
                        description = commandSpecText("spec.mvn.validate.description"),
                    ),
                    TerminalCommandSpec(
                        name = "compile",
                        description = commandSpecText("spec.mvn.compile.description"),
                    ),
                    TerminalCommandSpec(
                        name = "test",
                        description = commandSpecText("spec.mvn.test.description"),
                    ),
                    TerminalCommandSpec(
                        name = "package",
                        description = commandSpecText("spec.mvn.package.description"),
                    ),
                    TerminalCommandSpec(
                        name = "verify",
                        description = commandSpecText("spec.mvn.verify.description"),
                    ),
                    TerminalCommandSpec(
                        name = "install",
                        description = commandSpecText("spec.mvn.install.description"),
                    ),
                    TerminalCommandSpec(
                        name = "deploy",
                        description = commandSpecText("spec.mvn.deploy.description"),
                    ),
                    TerminalCommandSpec(
                        name = "site",
                        description = commandSpecText("spec.mvn.site.description"),
                    ),
                ),
            options =
                listOf(
                    TerminalOptionSpec(
                        names = listOf("--help", "-h"),
                        description = commandSpecText("spec.mvn.option.--help.description"),
                    ),
                    TerminalOptionSpec(
                        names = listOf("--version", "-v"),
                        description = commandSpecText("spec.mvn.option.--version.description"),
                    ),
                    TerminalOptionSpec(
                        names = listOf("--batch-mode", "-B"),
                        description = commandSpecText("spec.mvn.option.--batch-mode.description"),
                    ),
                    TerminalOptionSpec(
                        names = listOf("--offline", "-o"),
                        description = commandSpecText("spec.mvn.option.--offline.description"),
                    ),
                    TerminalOptionSpec(
                        names = listOf("--update-snapshots", "-U"),
                        description = commandSpecText("spec.mvn.option.--update-snapshots.description"),
                    ),
                    TerminalOptionSpec(
                        names = listOf("--errors", "-e"),
                        description = commandSpecText("spec.mvn.option.--errors.description"),
                    ),
                    TerminalOptionSpec(
                        names = listOf("--debug", "-X"),
                        description = commandSpecText("spec.mvn.option.--debug.description"),
                    ),
                    TerminalOptionSpec(
                        names = listOf("--file", "-f"),
                        description = commandSpecText("spec.mvn.option.--file.description"),
                        requiresValue = true,
                        valueName = commandSpecText("spec.mvn.option.--file.valueName"),
                        valuePathKind = TerminalPathArgumentKind.FILE_OR_DIRECTORY,
                    ),
                    TerminalOptionSpec(
                        names = listOf("--settings", "-s"),
                        description = commandSpecText("spec.mvn.option.--settings.description"),
                        requiresValue = true,
                        valueName = commandSpecText("spec.mvn.option.--settings.valueName"),
                        valuePathKind = TerminalPathArgumentKind.FILE,
                    ),
                    TerminalOptionSpec(
                        names = listOf("--activate-profiles", "-P"),
                        description = commandSpecText("spec.mvn.option.--activate-profiles.description"),
                        requiresValue = true,
                        valueName = commandSpecText("spec.mvn.option.--activate-profiles.valueName"),
                    ),
                    TerminalOptionSpec(
                        names = listOf("--projects", "-pl"),
                        description = commandSpecText("spec.mvn.option.--projects.description"),
                        requiresValue = true,
                        valueName = commandSpecText("spec.mvn.option.--projects.valueName"),
                    ),
                    TerminalOptionSpec(
                        names = listOf("--define", "-D"),
                        description = commandSpecText("spec.mvn.option.--define.description"),
                        requiresValue = true,
                        valueName = commandSpecText("spec.mvn.option.--define.valueName"),
                    ),
                    TerminalOptionSpec(
                        names = listOf("--threads", "-T"),
                        description = commandSpecText("spec.mvn.option.--threads.description"),
                        requiresValue = true,
                        valueName = commandSpecText("spec.mvn.option.--threads.valueName"),
                    ),
                    TerminalOptionSpec(
                        names = listOf("--also-make", "-am"),
                        description = commandSpecText("spec.mvn.option.--also-make.description"),
                    ),
                    TerminalOptionSpec(
                        names = listOf("--resume-from", "-rf"),
                        description = commandSpecText("spec.mvn.option.--resume-from.description"),
                        requiresValue = true,
                        valueName = commandSpecText("spec.mvn.option.--resume-from.valueName"),
                    ),
                ),
        )
}

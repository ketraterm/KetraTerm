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

/** Selected .NET SDK commands; runner-specific test options remain native-completer owned. */
internal object DotnetCommandSpecs {
    fun dotnet(): TerminalCommandSpec =
        TerminalCommandSpec(
            name = "dotnet",
            description = commandSpecText("spec.dotnet.description"),
            subcommands =
                listOf(
                    TerminalCommandSpec(
                        name = "build",
                        description = commandSpecText("spec.dotnet.build.description"),
                        positionalArguments =
                            listOf(
                                TerminalArgumentSpec(
                                    commandSpecText("spec.dotnet.build.argument.project.name"),
                                    isOptional = true,
                                    pathKind = TerminalPathArgumentKind.FILE_OR_DIRECTORY,
                                ),
                            ),
                        options =
                            listOf(
                                TerminalOptionSpec(
                                    names = listOf("--configuration", "-c"),
                                    description = commandSpecText("spec.dotnet.build.option.--configuration.description"),
                                    requiresValue = true,
                                    valueName = commandSpecText("spec.dotnet.build.option.--configuration.valueName"),
                                    valueCandidates = listOf("Debug", "Release"),
                                ),
                                TerminalOptionSpec(
                                    names = listOf("--framework", "-f"),
                                    description = commandSpecText("spec.dotnet.build.option.--framework.description"),
                                    requiresValue = true,
                                    valueName = commandSpecText("spec.dotnet.build.option.--framework.valueName"),
                                ),
                                TerminalOptionSpec(
                                    names = listOf("--output", "-o"),
                                    description = commandSpecText("spec.dotnet.build.option.--output.description"),
                                    requiresValue = true,
                                    valueName = commandSpecText("spec.dotnet.build.option.--output.valueName"),
                                    valuePathKind = TerminalPathArgumentKind.DIRECTORY,
                                ),
                                TerminalOptionSpec(
                                    names = listOf("--no-restore"),
                                    description = commandSpecText("spec.dotnet.build.option.--no-restore.description"),
                                ),
                                TerminalOptionSpec(
                                    names = listOf("--verbosity", "-v"),
                                    description = commandSpecText("spec.dotnet.build.option.--verbosity.description"),
                                    requiresValue = true,
                                    valueName = commandSpecText("spec.dotnet.build.option.--verbosity.valueName"),
                                    valueCandidates = listOf("quiet", "minimal", "normal", "detailed", "diagnostic"),
                                ),
                            ),
                    ),
                    TerminalCommandSpec(
                        name = "clean",
                        description = commandSpecText("spec.dotnet.clean.description"),
                        positionalArguments =
                            listOf(
                                TerminalArgumentSpec(
                                    commandSpecText("spec.dotnet.clean.argument.project.name"),
                                    isOptional = true,
                                    pathKind = TerminalPathArgumentKind.FILE_OR_DIRECTORY,
                                ),
                            ),
                        options =
                            listOf(
                                TerminalOptionSpec(
                                    names = listOf("--configuration", "-c"),
                                    description = commandSpecText("spec.dotnet.clean.option.--configuration.description"),
                                    requiresValue = true,
                                    valueName = commandSpecText("spec.dotnet.clean.option.--configuration.valueName"),
                                    valueCandidates = listOf("Debug", "Release"),
                                ),
                                TerminalOptionSpec(
                                    names = listOf("--verbosity", "-v"),
                                    description = commandSpecText("spec.dotnet.clean.option.--verbosity.description"),
                                    requiresValue = true,
                                    valueName = commandSpecText("spec.dotnet.clean.option.--verbosity.valueName"),
                                    valueCandidates = listOf("quiet", "minimal", "normal", "detailed", "diagnostic"),
                                ),
                            ),
                    ),
                    TerminalCommandSpec(
                        name = "restore",
                        description = commandSpecText("spec.dotnet.restore.description"),
                        positionalArguments =
                            listOf(
                                TerminalArgumentSpec(
                                    commandSpecText("spec.dotnet.restore.argument.project.name"),
                                    isOptional = true,
                                    pathKind = TerminalPathArgumentKind.FILE_OR_DIRECTORY,
                                ),
                            ),
                        options =
                            listOf(
                                TerminalOptionSpec(
                                    names = listOf("--source", "-s"),
                                    description = commandSpecText("spec.dotnet.restore.option.--source.description"),
                                    requiresValue = true,
                                    valueName = commandSpecText("spec.dotnet.restore.option.--source.valueName"),
                                ),
                                TerminalOptionSpec(
                                    names = listOf("--packages"),
                                    description = commandSpecText("spec.dotnet.restore.option.--packages.description"),
                                    requiresValue = true,
                                    valueName = commandSpecText("spec.dotnet.restore.option.--packages.valueName"),
                                    valuePathKind = TerminalPathArgumentKind.DIRECTORY,
                                ),
                                TerminalOptionSpec(
                                    names = listOf("--force"),
                                    description = commandSpecText("spec.dotnet.restore.option.--force.description"),
                                ),
                                TerminalOptionSpec(
                                    names = listOf("--verbosity", "-v"),
                                    description = commandSpecText("spec.dotnet.restore.option.--verbosity.description"),
                                    requiresValue = true,
                                    valueName = commandSpecText("spec.dotnet.restore.option.--verbosity.valueName"),
                                    valueCandidates = listOf("quiet", "minimal", "normal", "detailed", "diagnostic"),
                                ),
                            ),
                    ),
                    TerminalCommandSpec(
                        name = "publish",
                        description = commandSpecText("spec.dotnet.publish.description"),
                        positionalArguments =
                            listOf(
                                TerminalArgumentSpec(
                                    commandSpecText("spec.dotnet.publish.argument.project.name"),
                                    isOptional = true,
                                    pathKind = TerminalPathArgumentKind.FILE_OR_DIRECTORY,
                                ),
                            ),
                        options =
                            listOf(
                                TerminalOptionSpec(
                                    names = listOf("--configuration", "-c"),
                                    description = commandSpecText("spec.dotnet.publish.option.--configuration.description"),
                                    requiresValue = true,
                                    valueName = commandSpecText("spec.dotnet.publish.option.--configuration.valueName"),
                                    valueCandidates = listOf("Debug", "Release"),
                                ),
                                TerminalOptionSpec(
                                    names = listOf("--framework", "-f"),
                                    description = commandSpecText("spec.dotnet.publish.option.--framework.description"),
                                    requiresValue = true,
                                    valueName = commandSpecText("spec.dotnet.publish.option.--framework.valueName"),
                                ),
                                TerminalOptionSpec(
                                    names = listOf("--output", "-o"),
                                    description = commandSpecText("spec.dotnet.publish.option.--output.description"),
                                    requiresValue = true,
                                    valueName = commandSpecText("spec.dotnet.publish.option.--output.valueName"),
                                    valuePathKind = TerminalPathArgumentKind.DIRECTORY,
                                ),
                                TerminalOptionSpec(
                                    names = listOf("--no-restore"),
                                    description = commandSpecText("spec.dotnet.publish.option.--no-restore.description"),
                                ),
                                TerminalOptionSpec(
                                    names = listOf("--verbosity", "-v"),
                                    description = commandSpecText("spec.dotnet.publish.option.--verbosity.description"),
                                    requiresValue = true,
                                    valueName = commandSpecText("spec.dotnet.publish.option.--verbosity.valueName"),
                                    valueCandidates = listOf("quiet", "minimal", "normal", "detailed", "diagnostic"),
                                ),
                            ),
                    ),
                    TerminalCommandSpec(
                        name = "run",
                        description = commandSpecText("spec.dotnet.run.description"),
                        positionalArguments =
                            listOf(
                                TerminalArgumentSpec(
                                    commandSpecText("spec.dotnet.run.argument.args.name"),
                                    isOptional = true,
                                    isVariadic = true,
                                ),
                            ),
                        positionalArgumentAfterDoubleDash =
                            TerminalArgumentSpec(
                                commandSpecText("spec.dotnet.run.argumentAfterDoubleDash.name"),
                                isOptional = true,
                                isVariadic = true,
                            ),
                        options =
                            listOf(
                                TerminalOptionSpec(
                                    names = listOf("--project"),
                                    description = commandSpecText("spec.dotnet.run.option.--project.description"),
                                    requiresValue = true,
                                    valueName = commandSpecText("spec.dotnet.run.option.--project.valueName"),
                                    valuePathKind = TerminalPathArgumentKind.FILE_OR_DIRECTORY,
                                ),
                                TerminalOptionSpec(
                                    names = listOf("--configuration", "-c"),
                                    description = commandSpecText("spec.dotnet.run.option.--configuration.description"),
                                    requiresValue = true,
                                    valueName = commandSpecText("spec.dotnet.run.option.--configuration.valueName"),
                                    valueCandidates = listOf("Debug", "Release"),
                                ),
                                TerminalOptionSpec(
                                    names = listOf("--framework", "-f"),
                                    description = commandSpecText("spec.dotnet.run.option.--framework.description"),
                                    requiresValue = true,
                                    valueName = commandSpecText("spec.dotnet.run.option.--framework.valueName"),
                                ),
                                TerminalOptionSpec(
                                    names = listOf("--launch-profile"),
                                    description = commandSpecText("spec.dotnet.run.option.--launch-profile.description"),
                                    requiresValue = true,
                                    valueName = commandSpecText("spec.dotnet.run.option.--launch-profile.valueName"),
                                ),
                                TerminalOptionSpec(
                                    names = listOf("--no-launch-profile"),
                                    description = commandSpecText("spec.dotnet.run.option.--no-launch-profile.description"),
                                ),
                                TerminalOptionSpec(
                                    names = listOf("--no-build"),
                                    description = commandSpecText("spec.dotnet.run.option.--no-build.description"),
                                ),
                                TerminalOptionSpec(
                                    names = listOf("--no-restore"),
                                    description = commandSpecText("spec.dotnet.run.option.--no-restore.description"),
                                ),
                            ),
                    ),
                    TerminalCommandSpec(
                        name = "test",
                        description = commandSpecText("spec.dotnet.test.description"),
                    ),
                    TerminalCommandSpec(
                        name = "new",
                        description = commandSpecText("spec.dotnet.new.description"),
                        positionalArguments =
                            listOf(
                                TerminalArgumentSpec(
                                    commandSpecText("spec.dotnet.new.argument.template.name"),
                                    isOptional = true,
                                ),
                            ),
                        subcommands =
                            listOf(
                                TerminalCommandSpec(name = "list", description = commandSpecText("spec.dotnet.new.list.description")),
                            ),
                        options =
                            listOf(
                                TerminalOptionSpec(
                                    names = listOf("--name", "-n"),
                                    description = commandSpecText("spec.dotnet.new.option.--name.description"),
                                    requiresValue = true,
                                    valueName = commandSpecText("spec.dotnet.new.option.--name.valueName"),
                                ),
                                TerminalOptionSpec(
                                    names = listOf("--output", "-o"),
                                    description = commandSpecText("spec.dotnet.new.option.--output.description"),
                                    requiresValue = true,
                                    valueName = commandSpecText("spec.dotnet.new.option.--output.valueName"),
                                    valuePathKind = TerminalPathArgumentKind.DIRECTORY,
                                ),
                                TerminalOptionSpec(
                                    names = listOf("--framework", "-f"),
                                    description = commandSpecText("spec.dotnet.new.option.--framework.description"),
                                    requiresValue = true,
                                    valueName = commandSpecText("spec.dotnet.new.option.--framework.valueName"),
                                ),
                            ),
                    ),
                    TerminalCommandSpec(
                        name = "sln",
                        description = commandSpecText("spec.dotnet.sln.description"),
                        subcommands =
                            listOf(
                                TerminalCommandSpec(
                                    name = "add",
                                    description = commandSpecText("spec.dotnet.sln.add.description"),
                                    positionalArguments =
                                        listOf(
                                            TerminalArgumentSpec(
                                                commandSpecText("spec.dotnet.sln.add.argument.project.name"),
                                                isVariadic = true,
                                                pathKind = TerminalPathArgumentKind.FILE_OR_DIRECTORY,
                                            ),
                                        ),
                                ),
                                TerminalCommandSpec(
                                    name = "remove",
                                    description = commandSpecText("spec.dotnet.sln.remove.description"),
                                    positionalArguments =
                                        listOf(
                                            TerminalArgumentSpec(
                                                commandSpecText("spec.dotnet.sln.remove.argument.project.name"),
                                                isVariadic = true,
                                                pathKind = TerminalPathArgumentKind.FILE_OR_DIRECTORY,
                                            ),
                                        ),
                                ),
                                TerminalCommandSpec(
                                    name = "list",
                                    description = commandSpecText("spec.dotnet.sln.list.description"),
                                ),
                            ),
                    ),
                ),
            options =
                listOf(
                    TerminalOptionSpec(
                        names = listOf("--help", "-h"),
                        description = commandSpecText("spec.dotnet.option.--help.description"),
                    ),
                    TerminalOptionSpec(
                        names = listOf("--version"),
                        description = commandSpecText("spec.dotnet.option.--version.description"),
                    ),
                    TerminalOptionSpec(
                        names = listOf("--info"),
                        description = commandSpecText("spec.dotnet.option.--info.description"),
                    ),
                    TerminalOptionSpec(
                        names = listOf("--list-sdks"),
                        description = commandSpecText("spec.dotnet.option.--list-sdks.description"),
                    ),
                    TerminalOptionSpec(
                        names = listOf("--list-runtimes"),
                        description = commandSpecText("spec.dotnet.option.--list-runtimes.description"),
                    ),
                ),
        )
}

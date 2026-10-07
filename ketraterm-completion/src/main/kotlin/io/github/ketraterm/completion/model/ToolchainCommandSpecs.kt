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

/**
 * Curated cloud, compiler, device, and launcher specifications (AWS, Kotlin, Kotlinc, ADB, Ketra).
 */
internal object ToolchainCommandSpecs {
    fun aws(): TerminalCommandSpec =
        TerminalCommandSpec(
            name = "aws",
            description = commandSpecText("spec.aws.description"),
            subcommands =
                listOf(
                    TerminalCommandSpec("s3", commandSpecText("spec.aws.s3.description")),
                    TerminalCommandSpec("ec2", commandSpecText("spec.aws.ec2.description")),
                    TerminalCommandSpec("rds", commandSpecText("spec.aws.rds.description")),
                    TerminalCommandSpec("dynamodb", commandSpecText("spec.aws.dynamodb.description")),
                    TerminalCommandSpec("lambda", commandSpecText("spec.aws.lambda.description")),
                    TerminalCommandSpec("iam", commandSpecText("spec.aws.iam.description")),
                    TerminalCommandSpec("sts", commandSpecText("spec.aws.sts.description")),
                    TerminalCommandSpec("configure", commandSpecText("spec.aws.configure.description")),
                    TerminalCommandSpec("cloudformation", commandSpecText("spec.aws.cloudformation.description")),
                ),
            options =
                listOf(
                    TerminalOptionSpec(listOf("--help"), commandSpecText("spec.aws.option.--help.description")),
                    TerminalOptionSpec(listOf("--version"), commandSpecText("spec.aws.option.--version.description")),
                    TerminalOptionSpec(
                        names = listOf("--profile"),
                        description = commandSpecText("spec.aws.option.--profile.description"),
                        requiresValue = true,
                        valueDomain = TerminalCompletionValueDomain.AWS_PROFILE,
                    ),
                    TerminalOptionSpec(
                        names = listOf("--region"),
                        description = commandSpecText("spec.aws.option.--region.description"),
                        requiresValue = true,
                        valueDomain = TerminalCompletionValueDomain.AWS_REGION,
                    ),
                    TerminalOptionSpec(
                        names = listOf("--output"),
                        description = commandSpecText("spec.aws.option.--output.description"),
                        requiresValue = true,
                        valueCandidates = listOf("json", "text", "table", "yaml", "yaml-stream"),
                    ),
                ),
        )

    fun kotlin(): TerminalCommandSpec =
        TerminalCommandSpec(
            name = "kotlin",
            description = commandSpecText("spec.kotlin.description"),
            subcommands =
                listOf(
                    TerminalCommandSpec("run", commandSpecText("spec.kotlin.run.description")),
                    TerminalCommandSpec("build", commandSpecText("spec.kotlin.build.description")),
                    TerminalCommandSpec("test", commandSpecText("spec.kotlin.test.description")),
                ),
            options =
                listOf(
                    TerminalOptionSpec(listOf("-version", "--version", "-v"), commandSpecText("spec.kotlin.option.-version.description")),
                    TerminalOptionSpec(listOf("-help", "-h"), commandSpecText("spec.kotlin.option.-help.description")),
                    TerminalOptionSpec(listOf("-e"), commandSpecText("spec.kotlin.option.-e.description"), requiresValue = true),
                    TerminalOptionSpec(
                        listOf("-classpath", "-cp"),
                        commandSpecText("spec.kotlin.option.-classpath.description"),
                        requiresValue = true,
                    ),
                    TerminalOptionSpec(listOf("-include-runtime"), commandSpecText("spec.kotlin.option.-include-runtime.description")),
                    TerminalOptionSpec(listOf("-nowarn"), commandSpecText("spec.kotlin.option.-nowarn.description")),
                    TerminalOptionSpec(listOf("-verbose"), commandSpecText("spec.kotlin.option.-verbose.description")),
                ),
        )

    fun kotlinc(): TerminalCommandSpec =
        TerminalCommandSpec(
            name = "kotlinc",
            description = commandSpecText("spec.kotlinc.description"),
            aliases = listOf("kotlinc-jvm", "kotlinc-js", "kotlinc-native"),
            options =
                listOf(
                    TerminalOptionSpec(listOf("-version", "--version", "-v"), commandSpecText("spec.kotlinc.option.-version.description")),
                    TerminalOptionSpec(listOf("-help", "-h"), commandSpecText("spec.kotlinc.option.-help.description")),
                    TerminalOptionSpec(
                        names = listOf("-d"),
                        description = commandSpecText("spec.kotlinc.option.-d.description"),
                        requiresValue = true,
                        valuePathKind = TerminalPathArgumentKind.FILE_OR_DIRECTORY,
                    ),
                    TerminalOptionSpec(
                        listOf("-classpath", "-cp"),
                        commandSpecText("spec.kotlinc.option.-classpath.description"),
                        requiresValue = true,
                    ),
                    TerminalOptionSpec(listOf("-include-runtime"), commandSpecText("spec.kotlinc.option.-include-runtime.description")),
                    TerminalOptionSpec(
                        names = listOf("-jvm-target"),
                        description = commandSpecText("spec.kotlinc.option.-jvm-target.description"),
                        requiresValue = true,
                        valueCandidates = listOf("1.8", "11", "17", "21", "22", "23", "24", "25"),
                    ),
                    TerminalOptionSpec(
                        listOf("-language-version"),
                        commandSpecText("spec.kotlinc.option.-language-version.description"),
                        requiresValue = true,
                    ),
                    TerminalOptionSpec(
                        listOf("-api-version"),
                        commandSpecText("spec.kotlinc.option.-api-version.description"),
                        requiresValue = true,
                    ),
                    TerminalOptionSpec(
                        listOf("-opt-in"),
                        commandSpecText("spec.kotlinc.option.-opt-in.description"),
                        requiresValue = true,
                    ),
                    TerminalOptionSpec(
                        listOf("-Xcontext-receivers"),
                        commandSpecText("spec.kotlinc.option.-Xcontext-receivers.description"),
                    ),
                    TerminalOptionSpec(
                        listOf("-Xcontext-parameters"),
                        commandSpecText("spec.kotlinc.option.-Xcontext-parameters.description"),
                    ),
                    TerminalOptionSpec(listOf("-Xmulti-platform"), commandSpecText("spec.kotlinc.option.-Xmulti-platform.description")),
                    TerminalOptionSpec(listOf("-Werror"), commandSpecText("spec.kotlinc.option.-Werror.description")),
                    TerminalOptionSpec(listOf("-nowarn"), commandSpecText("spec.kotlinc.option.-nowarn.description")),
                    TerminalOptionSpec(listOf("-verbose"), commandSpecText("spec.kotlinc.option.-verbose.description")),
                ),
        )

    fun adb(): TerminalCommandSpec =
        TerminalCommandSpec(
            name = "adb",
            description = commandSpecText("spec.adb.description"),
            subcommands =
                listOf(
                    TerminalCommandSpec("devices", commandSpecText("spec.adb.devices.description")),
                    TerminalCommandSpec("logcat", commandSpecText("spec.adb.logcat.description")),
                    TerminalCommandSpec(
                        "install",
                        commandSpecText("spec.adb.install.description"),
                        positionalArgumentPathKind = TerminalPathArgumentKind.FILE,
                    ),
                    TerminalCommandSpec("uninstall", commandSpecText("spec.adb.uninstall.description")),
                    TerminalCommandSpec("shell", commandSpecText("spec.adb.shell.description")),
                    TerminalCommandSpec(
                        "push",
                        commandSpecText("spec.adb.push.description"),
                        positionalArgumentPathKind = TerminalPathArgumentKind.FILE_OR_DIRECTORY,
                    ),
                    TerminalCommandSpec(
                        "pull",
                        commandSpecText("spec.adb.pull.description"),
                        positionalArgumentPathKind = TerminalPathArgumentKind.FILE_OR_DIRECTORY,
                    ),
                    TerminalCommandSpec("reboot", commandSpecText("spec.adb.reboot.description")),
                    TerminalCommandSpec("reverse", commandSpecText("spec.adb.reverse.description")),
                    TerminalCommandSpec("forward", commandSpecText("spec.adb.forward.description")),
                    TerminalCommandSpec("start-server", commandSpecText("spec.adb.start-server.description")),
                    TerminalCommandSpec("kill-server", commandSpecText("spec.adb.kill-server.description")),
                    TerminalCommandSpec("connect", commandSpecText("spec.adb.connect.description")),
                    TerminalCommandSpec("disconnect", commandSpecText("spec.adb.disconnect.description")),
                    TerminalCommandSpec("tcpip", commandSpecText("spec.adb.tcpip.description")),
                ),
            options =
                listOf(
                    TerminalOptionSpec(listOf("-s"), commandSpecText("spec.adb.option.-s.description"), requiresValue = true),
                    TerminalOptionSpec(listOf("-d"), commandSpecText("spec.adb.option.-d.description")),
                    TerminalOptionSpec(listOf("-e"), commandSpecText("spec.adb.option.-e.description")),
                    TerminalOptionSpec(listOf("--help", "-h"), commandSpecText("spec.adb.option.--help.description")),
                    TerminalOptionSpec(listOf("--version"), commandSpecText("spec.adb.option.--version.description")),
                ),
        )

    fun ketra(): TerminalCommandSpec =
        TerminalCommandSpec(
            name = "ketra",
            description = commandSpecText("spec.ketra.description"),
            options =
                listOf(
                    TerminalOptionSpec(listOf("--help", "-h"), commandSpecText("spec.ketra.option.--help.description")),
                    TerminalOptionSpec(listOf("--version", "-v"), commandSpecText("spec.ketra.option.--version.description")),
                    TerminalOptionSpec(
                        listOf("--profile", "-p"),
                        commandSpecText("spec.ketra.option.--profile.description"),
                        requiresValue = true,
                    ),
                    TerminalOptionSpec(
                        names = listOf("--directory", "-d"),
                        description = commandSpecText("spec.ketra.option.--directory.description"),
                        requiresValue = true,
                        valuePathKind = TerminalPathArgumentKind.DIRECTORY,
                    ),
                ),
        )
}

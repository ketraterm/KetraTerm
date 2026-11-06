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

/** Selected curl and OpenSSH client options and operand forms. */
internal object NetworkCommandSpecs {
    private val urlDomain = TerminalCompletionValueDomain("curl.url")
    private val destinationDomain = TerminalCompletionValueDomain("ssh.destination")
    private val remoteCommandDomain = TerminalCompletionValueDomain("ssh.command")
    private val remoteArgumentDomain = TerminalCompletionValueDomain("ssh.argument")

    fun curl(): TerminalCommandSpec =
        TerminalCommandSpec(
            name = "curl",
            description = commandSpecText("spec.curl.description"),
            positionalArguments =
                listOf(
                    TerminalArgumentSpec(
                        commandSpecText("spec.curl.argument.url.name"),
                        isVariadic = true,
                        valueDomain = urlDomain,
                    ),
                ),
            options =
                listOf(
                    TerminalOptionSpec(
                        names = listOf("--help", "-h"),
                        description = commandSpecText("spec.curl.option.--help.description"),
                    ),
                    TerminalOptionSpec(
                        names = listOf("--version", "-V"),
                        description = commandSpecText("spec.curl.option.--version.description"),
                    ),
                    TerminalOptionSpec(
                        names = listOf("--request", "-X"),
                        description = commandSpecText("spec.curl.option.--request.description"),
                        requiresValue = true,
                        valueName = commandSpecText("spec.curl.option.--request.valueName"),
                        valueCandidates = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"),
                    ),
                    TerminalOptionSpec(
                        names = listOf("--header", "-H"),
                        description = commandSpecText("spec.curl.option.--header.description"),
                        requiresValue = true,
                        valueName = commandSpecText("spec.curl.option.--header.valueName"),
                    ),
                    TerminalOptionSpec(
                        names = listOf("--data", "-d"),
                        description = commandSpecText("spec.curl.option.--data.description"),
                        requiresValue = true,
                        valueName = commandSpecText("spec.curl.option.--data.valueName"),
                    ),
                    TerminalOptionSpec(
                        names = listOf("--data-binary"),
                        description = commandSpecText("spec.curl.option.--data-binary.description"),
                        requiresValue = true,
                        valueName = commandSpecText("spec.curl.option.--data-binary.valueName"),
                    ),
                    TerminalOptionSpec(
                        names = listOf("--json"),
                        description = commandSpecText("spec.curl.option.--json.description"),
                        requiresValue = true,
                        valueName = commandSpecText("spec.curl.option.--json.valueName"),
                    ),
                    TerminalOptionSpec(
                        names = listOf("--output", "-o"),
                        description = commandSpecText("spec.curl.option.--output.description"),
                        requiresValue = true,
                        valueName = commandSpecText("spec.curl.option.--output.valueName"),
                        valuePathKind = TerminalPathArgumentKind.FILE,
                    ),
                    TerminalOptionSpec(
                        names = listOf("--upload-file", "-T"),
                        description = commandSpecText("spec.curl.option.--upload-file.description"),
                        requiresValue = true,
                        valueName = commandSpecText("spec.curl.option.--upload-file.valueName"),
                        valuePathKind = TerminalPathArgumentKind.FILE,
                    ),
                    TerminalOptionSpec(
                        names = listOf("--config", "-K"),
                        description = commandSpecText("spec.curl.option.--config.description"),
                        requiresValue = true,
                        valueName = commandSpecText("spec.curl.option.--config.valueName"),
                        valuePathKind = TerminalPathArgumentKind.FILE,
                    ),
                    TerminalOptionSpec(
                        names = listOf("--user", "-u"),
                        description = commandSpecText("spec.curl.option.--user.description"),
                        requiresValue = true,
                        valueName = commandSpecText("spec.curl.option.--user.valueName"),
                    ),
                    TerminalOptionSpec(
                        names = listOf("--connect-timeout"),
                        description = commandSpecText("spec.curl.option.--connect-timeout.description"),
                        requiresValue = true,
                        valueName = commandSpecText("spec.curl.option.--connect-timeout.valueName"),
                    ),
                    TerminalOptionSpec(
                        names = listOf("--max-time", "-m"),
                        description = commandSpecText("spec.curl.option.--max-time.description"),
                        requiresValue = true,
                        valueName = commandSpecText("spec.curl.option.--max-time.valueName"),
                    ),
                    TerminalOptionSpec(
                        names = listOf("--retry"),
                        description = commandSpecText("spec.curl.option.--retry.description"),
                        requiresValue = true,
                        valueName = commandSpecText("spec.curl.option.--retry.valueName"),
                    ),
                    TerminalOptionSpec(
                        names = listOf("--location", "-L"),
                        description = commandSpecText("spec.curl.option.--location.description"),
                    ),
                    TerminalOptionSpec(
                        names = listOf("--head", "-I"),
                        description = commandSpecText("spec.curl.option.--head.description"),
                    ),
                    TerminalOptionSpec(
                        names = listOf("--include", "-i"),
                        description = commandSpecText("spec.curl.option.--include.description"),
                    ),
                    TerminalOptionSpec(
                        names = listOf("--fail", "-f"),
                        description = commandSpecText("spec.curl.option.--fail.description"),
                    ),
                    TerminalOptionSpec(
                        names = listOf("--silent", "-s"),
                        description = commandSpecText("spec.curl.option.--silent.description"),
                    ),
                    TerminalOptionSpec(
                        names = listOf("--show-error", "-S"),
                        description = commandSpecText("spec.curl.option.--show-error.description"),
                    ),
                    TerminalOptionSpec(
                        names = listOf("--verbose", "-v"),
                        description = commandSpecText("spec.curl.option.--verbose.description"),
                    ),
                    TerminalOptionSpec(
                        names = listOf("--insecure", "-k"),
                        description = commandSpecText("spec.curl.option.--insecure.description"),
                    ),
                ),
        )

    fun ssh(): TerminalCommandSpec =
        TerminalCommandSpec(
            name = "ssh",
            description = commandSpecText("spec.ssh.description"),
            positionalArguments =
                listOf(
                    TerminalArgumentSpec(
                        commandSpecText("spec.ssh.argument.destination.name"),
                        valueDomain = destinationDomain,
                    ),
                    TerminalArgumentSpec(
                        commandSpecText("spec.ssh.argument.command.name"),
                        isOptional = true,
                        valueDomain = remoteCommandDomain,
                    ),
                    TerminalArgumentSpec(
                        commandSpecText("spec.ssh.argument.args.name"),
                        isOptional = true,
                        isVariadic = true,
                        valueDomain = remoteArgumentDomain,
                    ),
                ),
            options =
                listOf(
                    TerminalOptionSpec(
                        names = listOf("-p"),
                        description = commandSpecText("spec.ssh.option.-p.description"),
                        requiresValue = true,
                        valueName = commandSpecText("spec.ssh.option.-p.valueName"),
                    ),
                    TerminalOptionSpec(
                        names = listOf("-l"),
                        description = commandSpecText("spec.ssh.option.-l.description"),
                        requiresValue = true,
                        valueName = commandSpecText("spec.ssh.option.-l.valueName"),
                    ),
                    TerminalOptionSpec(
                        names = listOf("-i"),
                        description = commandSpecText("spec.ssh.option.-i.description"),
                        requiresValue = true,
                        valueName = commandSpecText("spec.ssh.option.-i.valueName"),
                        valuePathKind = TerminalPathArgumentKind.FILE,
                    ),
                    TerminalOptionSpec(
                        names = listOf("-F"),
                        description = commandSpecText("spec.ssh.option.-F.description"),
                        requiresValue = true,
                        valueName = commandSpecText("spec.ssh.option.-F.valueName"),
                        valuePathKind = TerminalPathArgumentKind.FILE,
                    ),
                    TerminalOptionSpec(
                        names = listOf("-E"),
                        description = commandSpecText("spec.ssh.option.-E.description"),
                        requiresValue = true,
                        valueName = commandSpecText("spec.ssh.option.-E.valueName"),
                        valuePathKind = TerminalPathArgumentKind.FILE,
                    ),
                    TerminalOptionSpec(
                        names = listOf("-J"),
                        description = commandSpecText("spec.ssh.option.-J.description"),
                        requiresValue = true,
                        valueName = commandSpecText("spec.ssh.option.-J.valueName"),
                    ),
                    TerminalOptionSpec(
                        names = listOf("-L"),
                        description = commandSpecText("spec.ssh.option.-L.description"),
                        requiresValue = true,
                        valueName = commandSpecText("spec.ssh.option.-L.valueName"),
                    ),
                    TerminalOptionSpec(
                        names = listOf("-R"),
                        description = commandSpecText("spec.ssh.option.-R.description"),
                        requiresValue = true,
                        valueName = commandSpecText("spec.ssh.option.-R.valueName"),
                    ),
                    TerminalOptionSpec(
                        names = listOf("-D"),
                        description = commandSpecText("spec.ssh.option.-D.description"),
                        requiresValue = true,
                        valueName = commandSpecText("spec.ssh.option.-D.valueName"),
                    ),
                    TerminalOptionSpec(
                        names = listOf("-o"),
                        description = commandSpecText("spec.ssh.option.-o.description"),
                        requiresValue = true,
                        valueName = commandSpecText("spec.ssh.option.-o.valueName"),
                    ),
                    TerminalOptionSpec(
                        names = listOf("-N"),
                        description = commandSpecText("spec.ssh.option.-N.description"),
                    ),
                    TerminalOptionSpec(
                        names = listOf("-t"),
                        description = commandSpecText("spec.ssh.option.-t.description"),
                    ),
                    TerminalOptionSpec(
                        names = listOf("-T"),
                        description = commandSpecText("spec.ssh.option.-T.description"),
                    ),
                    TerminalOptionSpec(
                        names = listOf("-A"),
                        description = commandSpecText("spec.ssh.option.-A.description"),
                    ),
                    TerminalOptionSpec(
                        names = listOf("-a"),
                        description = commandSpecText("spec.ssh.option.-a.description"),
                    ),
                    TerminalOptionSpec(
                        names = listOf("-4"),
                        description = commandSpecText("spec.ssh.option.-4.description"),
                    ),
                    TerminalOptionSpec(
                        names = listOf("-6"),
                        description = commandSpecText("spec.ssh.option.-6.description"),
                    ),
                    TerminalOptionSpec(
                        names = listOf("-v"),
                        description = commandSpecText("spec.ssh.option.-v.description"),
                    ),
                    TerminalOptionSpec(
                        names = listOf("-V"),
                        description = commandSpecText("spec.ssh.option.-V.description"),
                    ),
                ),
        )
}

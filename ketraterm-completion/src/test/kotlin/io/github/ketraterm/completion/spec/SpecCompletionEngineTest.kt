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
package io.github.ketraterm.completion.spec

import io.github.ketraterm.completion.api.*
import io.github.ketraterm.completion.model.TerminalCommandSpec
import io.github.ketraterm.completion.model.TerminalCommandSpecs
import io.github.ketraterm.completion.model.TerminalOptionSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpecCompletionEngineTest {
    @Test
    fun `empty command line suggests top-level commands`() {
        val candidates = engine().complete(request(""))

        assertEquals(listOf("git", "gradle"), candidates.map { it.replacementText })
        assertEquals(TerminalCompletionCandidateKind.COMMAND, candidates[0].kind)
        assertEquals(0, candidates[0].replacementStartOffset)
        assertEquals(0, candidates[0].replacementEndOffset)
    }

    @Test
    fun `command prefix filters top-level commands`() {
        val candidates = engine().complete(request("gr"))

        assertEquals(listOf("gradle"), candidates.map { it.replacementText })
        assertEquals(0, candidates.single().replacementStartOffset)
        assertEquals(2, candidates.single().replacementEndOffset)
    }

    @Test
    fun `exact command token does not return no-op command suggestion`() {
        val candidates = engine().complete(request("git"))

        assertTrue(candidates.isEmpty())
    }

    @Test
    fun `subcommand prefix resolves command path`() {
        val candidates = engine().complete(request("git c"))

        assertEquals(listOf("commit", "checkout"), candidates.map { it.replacementText })
        assertTrue(candidates.all { it.kind == TerminalCompletionCandidateKind.SUBCOMMAND })
        assertEquals(4, candidates[0].replacementStartOffset)
        assertEquals(5, candidates[0].replacementEndOffset)
    }

    @Test
    fun `whitespace after command suggests subcommands with empty replacement range`() {
        val candidates = engine().complete(request("git "))

        assertEquals(listOf("status", "commit", "checkout"), candidates.map { it.replacementText })
        assertEquals(4, candidates[0].replacementStartOffset)
        assertEquals(4, candidates[0].replacementEndOffset)
    }

    @Test
    fun `exact subcommand token does not return no-op subcommand suggestion`() {
        val candidates = engine().complete(request("git status"))

        assertTrue(candidates.isEmpty())
    }

    @Test
    fun `option prefix returns matching command options before the terminator`() {
        val candidates = engine().complete(request("git --v"))

        assertEquals(listOf("--version"), candidates.map { it.replacementText })
        assertTrue(candidates.all { it.kind == TerminalCompletionCandidateKind.OPTION })
    }

    @Test
    fun `option terminator stops option completion`() {
        assertTrue(engine().complete(request("git --")).isEmpty())
        assertTrue(engine().complete(request("git -- --v")).isEmpty())
    }

    @Test
    fun `option terminator prevents option values from completing`() {
        assertTrue(engine().complete(request("gradle -- --console r")).isEmpty())
    }

    @Test
    fun `mutually exclusive options are not suggested after a conflicting option`() {
        val engine =
            TerminalCompletionEngines.fromSources(
                TerminalCompletionSources.fromSpecs(
                    listOf(
                        TerminalCommandSpec(
                            name = "tool",
                            options =
                                listOf(
                                    TerminalOptionSpec(listOf("--quiet", "-q"), exclusiveGroupIds = listOf("verbosity")),
                                    TerminalOptionSpec(listOf("--verbose", "-v"), exclusiveGroupIds = listOf("verbosity")),
                                    TerminalOptionSpec(listOf("--color")),
                                ),
                        ),
                    ),
                ),
            )

        val candidates = engine.complete(request("tool -q -"))

        assertEquals(listOf("--color"), candidates.map { it.replacementText })
    }

    @Test
    fun `option value candidates complete value token`() {
        val candidates = engine().complete(request("gradle --console r"))

        assertEquals(listOf("rich"), candidates.map { it.replacementText })
        assertTrue(candidates.all { it.kind == TerminalCompletionCandidateKind.ARGUMENT })
        assertEquals(17, candidates.single().replacementStartOffset)
        assertEquals(18, candidates.single().replacementEndOffset)
    }

    @Test
    fun `option value candidates complete attached value token`() {
        val commandLine = "gradle --console=ri"
        val candidates = engine().complete(request(commandLine))

        assertEquals(listOf("rich"), candidates.map { it.replacementText })
        assertEquals(commandLine.indexOf('=') + 1, candidates.single().replacementStartOffset)
        assertEquals(commandLine.length, candidates.single().replacementEndOffset)
    }

    @Test
    fun `default specs expose static option value domains`() {
        val engine =
            TerminalCompletionEngines.fromSources(
                TerminalCompletionSources.fromSpecs(TerminalCommandSpecs.defaults()),
            )

        val candidates = engine.complete(request("aws --output t"))

        assertEquals(listOf("text", "table"), candidates.map { it.replacementText })
    }

    @Test
    fun `exact option token does not return no-op option suggestion`() {
        val candidates = engine().complete(request("git --help"))

        assertTrue(candidates.isEmpty())
    }

    @Test
    fun `cursor inside token replaces the whole active token`() {
        val candidates = engine().complete(request("git che", cursorOffset = 6))

        assertEquals(listOf("checkout"), candidates.map { it.replacementText })
        assertEquals(4, candidates.single().replacementStartOffset)
        assertEquals(7, candidates.single().replacementEndOffset)
    }

    @Test
    fun `aliases resolve command paths but candidates use canonical names`() {
        val candidates = engine().complete(request("gradlew t"))

        assertEquals(listOf("test", "tasks"), candidates.map { it.replacementText })
    }

    @Test
    fun `repeatable subcommands suggest sibling tasks after an existing task`() {
        val candidates = engine().complete(request("./gradlew clean bu"))

        assertEquals(listOf("build"), candidates.map { it.replacementText })
        assertEquals(TerminalCompletionCandidateKind.SUBCOMMAND, candidates.single().kind)
        assertEquals(16, candidates.single().replacementStartOffset)
        assertEquals(18, candidates.single().replacementEndOffset)
    }

    @Test
    fun `repeatable subcommands omit already used sibling tasks after whitespace`() {
        val candidates = engine().complete(request("./gradlew clean "))

        assertEquals(listOf("build", "test", "tasks"), candidates.map { it.replacementText })
    }

    @Test
    fun `leading environment assignments preserve command completion`() {
        val candidates = engine().complete(request("NODE_ENV=test gr"))

        assertEquals(listOf("gradle"), candidates.map { it.replacementText })
        assertEquals(14, candidates.single().replacementStartOffset)
        assertEquals(16, candidates.single().replacementEndOffset)
    }

    @Test
    fun `leading environment assignments preserve command body completion`() {
        val candidates = engine().complete(request("NODE_ENV=test git c"))

        assertEquals(listOf("commit", "checkout"), candidates.map { it.replacementText })
        assertEquals(18, candidates[0].replacementStartOffset)
        assertEquals(19, candidates[0].replacementEndOffset)
    }

    @Test
    fun `command and option path resolution is case-insensitive`() {
        val candidates = engine().complete(request("GIT -c repo st"))

        assertEquals(listOf("status"), candidates.map { it.replacementText })
    }

    @Test
    fun `quoted active token is unquoted for matching and replaced as one shell token`() {
        val candidates = engine().complete(request("git \"ch"))

        assertEquals(listOf("checkout"), candidates.map { it.replacementText })
        assertEquals(4, candidates.single().replacementStartOffset)
        assertEquals(7, candidates.single().replacementEndOffset)
    }

    @Test
    fun `max candidates caps returned results after ranking`() {
        val candidates = engine().complete(request("git ", maxCandidates = 2))

        assertEquals(listOf("status", "commit"), candidates.map { it.replacementText })
    }

    @Test
    fun `unknown command returns no command-body suggestions`() {
        assertTrue(engine().complete(request("unknown --")).isEmpty())
    }

    @Test
    fun `inherited options path resolution with nested subcommands`() {
        val dockerSpec =
            TerminalCommandSpec(
                name = "docker",
                subcommands =
                    listOf(
                        TerminalCommandSpec(
                            name = "compose",
                            subcommands =
                                listOf(
                                    TerminalCommandSpec("up"),
                                    TerminalCommandSpec("down"),
                                    TerminalCommandSpec("ps"),
                                ),
                        ),
                    ),
                options =
                    listOf(
                        TerminalOptionSpec(listOf("--context"), requiresValue = true),
                    ),
            )
        val engine =
            TerminalCompletionEngines.fromSources(
                TerminalCompletionSources.fromSpecs(listOf(dockerSpec)),
            )

        val candidates = engine.complete(request("docker compose --context my-context p"))
        assertEquals(listOf("ps"), candidates.map { it.replacementText })
    }

    @Test
    fun `default specs registration and matching`() {
        val engine =
            TerminalCompletionEngines.fromSources(
                TerminalCompletionSources.fromSpecs(TerminalCommandSpecs.defaults()),
            )

        // Test cargo matching
        val cargoCandidates = engine.complete(request("cargo bui"))
        assertEquals(listOf("build"), cargoCandidates.map { it.replacementText })

        // Test kubectl matching
        val kubectlCandidates = engine.complete(request("kubectl ge"))
        assertEquals(listOf("get"), kubectlCandidates.map { it.replacementText })

        // Test gh matching
        val ghCandidates = engine.complete(request("gh work"))
        assertEquals(listOf("workflow"), ghCandidates.map { it.replacementText })

        // Test pip matching
        val pipCandidates = engine.complete(request("pip inst"))
        assertEquals(listOf("install"), pipCandidates.map { it.replacementText })

        // Test go matching
        val goCandidates = engine.complete(request("go fm"))
        assertEquals(listOf("fmt"), goCandidates.map { it.replacementText })

        // Test aws matching
        val awsCandidates = engine.complete(request("aws s"))
        assertEquals(listOf("s3", "sts"), awsCandidates.map { it.replacementText })

        // Test ketra matching
        val ketraCandidates = engine.complete(request("ketra --prof"))
        assertEquals(listOf("--profile"), ketraCandidates.map { it.replacementText })
    }

    private fun engine(): TerminalCompletionEngine =
        TerminalCompletionEngines.fromSources(
            TerminalCompletionSources.fromSpecs(
                listOf(
                    TerminalCommandSpec(
                        name = "git",
                        description = "version control",
                        subcommands =
                            listOf(
                                TerminalCommandSpec("status", "show status"),
                                TerminalCommandSpec("commit", "record changes"),
                                TerminalCommandSpec("checkout", "switch branches"),
                            ),
                        options =
                            listOf(
                                TerminalOptionSpec(listOf("--help", "-h"), "show help"),
                                TerminalOptionSpec(listOf("--version"), "show version"),
                            ),
                    ),
                    TerminalCommandSpec(
                        name = "gradle",
                        aliases = listOf("gradlew", "./gradlew"),
                        repeatableSubcommands = true,
                        subcommands =
                            listOf(
                                TerminalCommandSpec("clean", "delete build outputs"),
                                TerminalCommandSpec("build", "assemble and test the project"),
                                TerminalCommandSpec("test", "run tests"),
                                TerminalCommandSpec("tasks", "list tasks"),
                            ),
                        options =
                            listOf(
                                TerminalOptionSpec(
                                    names = listOf("--console"),
                                    description = "console output style",
                                    requiresValue = true,
                                    valueCandidates = listOf("auto", "plain", "rich", "verbose"),
                                ),
                            ),
                    ),
                ),
            ),
        )

    private fun request(
        commandLine: String,
        cursorOffset: Int = commandLine.length,
        maxCandidates: Int = 8,
    ): TerminalCompletionRequest =
        TerminalCompletionRequest(
            commandLine = commandLine,
            cursorOffset = cursorOffset,
            maxCandidates = maxCandidates,
        )
}

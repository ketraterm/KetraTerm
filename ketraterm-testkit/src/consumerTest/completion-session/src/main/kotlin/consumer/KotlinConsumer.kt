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
@file:OptIn(io.github.ketraterm.completion.api.ExperimentalTerminalCompletionApi::class)

package consumer

import io.github.ketraterm.completion.api.*
import io.github.ketraterm.completion.session.SessionShellCompletionProvider
import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.session.*
import io.github.ketraterm.transport.*
import kotlinx.coroutines.runBlocking

fun main() =
    runBlocking {
        var closed = false
        val connector =
            object : TerminalConnector {
                override fun start(listener: TerminalConnectorListener) = Unit

                override fun write(
                    bytes: ByteArray,
                    offset: Int,
                    length: Int,
                ) = error("Completion used transport")

                override fun resize(
                    columns: Int,
                    rows: Int,
                ) = Unit

                override fun close() {
                    closed = true
                }
            }
        val state = TerminalShellIntegrationState()
        val commandLine = TerminalShellCommandLineState(TerminalShellCommandLineSnapshot("gi", 2, 2, 0))
        val host = TerminalShellIntegrationFactory.host(state, commandLine)
        TerminalSession
            .create(
                TerminalBuffers.create(80, 4),
                connector,
                shellIntegration =
                    TerminalShellIntegrationFactory { context ->
                        context.shellCompletion.publishCommands(
                            TerminalShellCommandInventory(
                                listOf(
                                    TerminalShellCompletionCandidate("git", "git", "", TerminalShellCompletionKind.COMMAND),
                                    TerminalShellCompletionCandidate("gs", "gs", "", TerminalShellCompletionKind.COMMAND, "git status"),
                                ),
                            ),
                        )
                        host.create(context)
                    },
            ).use { session ->
                JavaConsumer.verify(session)
                val provider = SessionShellCompletionProvider(session)
                val request = TerminalCompletionRequest("gi", 2, shellCapabilities = TerminalShellCapabilities.POSIX)
                val answer = checkNotNull(provider.complete(request, TerminalCompletionContext.resolve(request), 1))
                check(answer.candidates.single().replacementText == "git")
                check(provider.commandAliases() == mapOf("gs" to "git status"))
            }
        check(closed)
    }

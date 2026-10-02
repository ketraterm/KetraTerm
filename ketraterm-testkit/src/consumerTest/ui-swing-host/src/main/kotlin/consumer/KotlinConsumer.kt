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
package consumer

import io.github.ketraterm.completion.api.*
import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.session.*
import io.github.ketraterm.transport.*
import io.github.ketraterm.ui.swing.api.SwingTerminal
import io.github.ketraterm.ui.swing.host.*
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionRequest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.swing.SwingUtilities

private class Connector : TerminalConnector {
    var closed = false

    override fun start(listener: TerminalConnectorListener) = Unit

    override fun write(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ) = Unit

    override fun resize(
        columns: Int,
        rows: Int,
    ) = Unit

    override fun close() {
        closed = true
    }
}

fun main() =
    runBlocking {
        JavaConsumer.verify()
        val engine =
            TerminalCompletionEngine { request ->
                check(request.commandLine == "git st")
                flowOf(listOf(TerminalCompletionCandidate("status", 4, 6, "host", TerminalCompletionCandidateKind.SUBCOMMAND)))
            }
        val provider = SwingCompletionSuggestionProvider(engine)
        // A host-owned controller consumes our provider without automatic coordination.
        val results = provider.suggestions(SwingShellSuggestionRequest("git st", 6, 6, 0)).single()
        check(results.single().replacementText == "status" && results.single().replacementStartOffset == 4)
        val buffer = TerminalBuffers.create(80, 3, 0)
        val connector = Connector()
        val scope = CoroutineScope(SupervisorJob())
        try {
            TerminalSession.create(buffer, buffer, connector).use { session ->
                SwingUtilities.invokeAndWait {
                    val terminal = SwingTerminal()
                    val target = JavaConsumer.NativeTarget()
                    val binding = SwingLiveCompletionBinding(session, scope, { false })
                    try {
                        terminal.bind(session)
                        binding.attach(terminal, target)
                        target.requestSuggestions(TerminalShellCommandLineSnapshot("git st", 6, 6, 0))
                        check(target.requested.commandText == "git st")
                        binding.close()
                        check(target.hides > 0 && target.requested == null && !connector.closed)
                    } finally {
                        binding.close()
                        terminal.dispose()
                    }
                }
            }
        } finally {
            scope.cancel()
        }
        check(connector.closed)
    }

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
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionInteraction
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionRequest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.awt.Color
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
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
        val nativeItem = Any()
        val engine =
            TerminalCompletionEngine { request ->
                check(request.commandLine == "git st")
                flowOf(
                    listOf(
                        TerminalCompletionCandidate(
                            "status",
                            4,
                            6,
                            "host",
                            TerminalCompletionCandidateKind.SUBCOMMAND,
                            feedbackToken = nativeItem,
                        ),
                    ),
                )
            }
        val provider = SwingCompletionSuggestionProvider(engine, { SwingCompletionContext.EMPTY }, mapOf("host" to "Product source"))
        // A host-owned controller consumes our provider without automatic coordination.
        val results = provider.suggestions(SwingShellSuggestionRequest("git st", 6)).single()
        check(results.single().replacementText == "status" && results.single().replacementStartOffset == 4)
        check(results.single().source == "host" && results.single().sourceDisplayText == "Product source")
        check(results.single().feedbackToken === nativeItem)
        check(
            JavaConsumer
                .labeledProvider(
                    engine,
                ).suggestions(SwingShellSuggestionRequest("git st", 6))
                .single()
                .single()
                .sourceDisplayText ==
                "Product source",
        )
        val buffer = TerminalBuffers.create(80, 3, 0)
        val connector = Connector()
        TerminalSession.create(buffer, buffer, connector).use { session ->
            val (terminal, searchBar, target) =
                onEdt {
                    val terminal = SwingTerminal()
                    val searchBar = SwingTerminalSearchBar(terminal, JavaConsumer.hostMessages())
                    JavaConsumer.configureSearchHints(searchBar)
                    check(!searchBar.performAction(SwingTerminalSearchAction.CLOSE))
                    searchBar.open()
                    check(searchBar.performAction(SwingTerminalSearchAction.TOGGLE_REGEX))
                    check(searchBar.performAction(SwingTerminalSearchAction.CLOSE))
                    Triple(terminal, searchBar, JavaConsumer.NativeTarget())
                }
            try {
                onEdt {
                    terminal.setShellSuggestionProvider(provider)
                    terminal.setShellSuggestionTarget(target)
                    check(terminal.hasShellSuggestionProvider)
                    val light = JavaConsumer.lightSearchColors()
                    searchBar.refreshColors(light)
                    check(searchBar.component.foreground == Color.BLACK)
                    searchBar.refreshColors(
                        light.copy {
                            it.foreground = Color.WHITE
                            it.panelBackground = Color.BLACK
                        },
                    )
                    searchBar.refreshColors()
                    check(searchBar.component.foreground == Color.WHITE)
                }
                terminal.bindAndAwait(session)
                onEdt {
                    SwingShellSuggestionInteraction(SwingShellSuggestionRequest("git st", 6)).use { interaction ->
                        target.requestSuggestions(interaction)
                        check(target.requested.request.commandText == "git st")
                        terminal.setShellSuggestionTarget(null)
                        check(target.hides > 0 && target.requested == null && !connector.closed)
                    }
                    terminal.unbind()
                    check(terminal.hasShellSuggestionProvider && !connector.closed)
                }
                terminal.bindAndAwait(session)
                onEdt {
                    terminal.refreshShellSuggestions()
                    terminal.setShellSuggestionProvider(null)
                    check(!terminal.hasShellSuggestionProvider)
                }
            } finally {
                onEdt {
                    searchBar.close()
                    terminal.dispose()
                }
            }
        }
        check(connector.closed)
    }

private suspend fun SwingTerminal.bindAndAwait(session: TerminalSession) {
    val bound = CompletableDeferred<Unit>()
    onEdt {
        bind(session) { failure ->
            if (failure == null) bound.complete(Unit) else bound.completeExceptionally(failure)
        }
    }
    withTimeout(20_000) { bound.await() }
}

private fun <T> onEdt(action: () -> T): T {
    val task = FutureTask(Callable(action))
    SwingUtilities.invokeAndWait(task)
    return task.get()
}

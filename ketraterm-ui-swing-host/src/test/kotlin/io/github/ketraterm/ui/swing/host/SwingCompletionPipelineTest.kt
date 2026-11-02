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
package io.github.ketraterm.ui.swing.host

import io.github.ketraterm.completion.api.*
import io.github.ketraterm.completion.model.TerminalCompletionDomainValue
import io.github.ketraterm.completion.model.TerminalGitCompletionSnapshot
import io.github.ketraterm.ui.swing.api.SwingTerminal
import io.github.ketraterm.ui.swing.settings.SwingSettings
import io.github.ketraterm.ui.swing.suggestion.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.swing.Swing
import kotlin.test.*

/** Exercises real engine ranking, Swing adaptation, request ownership, and acceptance together. */
class SwingCompletionPipelineTest {
    @Test
    fun `repository refresh rejects old results even when command text is unchanged and automatic popup is off`() =
        runBlocking {
            withTimeout(10_000) {
                for (shell in listOf(TerminalShellCapabilities.POSIX, TerminalShellCapabilities.POWERSHELL)) {
                    val entered = CompletableDeferred<Unit>()
                    val release = CompletableDeferred<Unit>()
                    val finished = CompletableDeferred<Unit>()
                    val engine =
                        TerminalCompletionEngines.fromSources(
                            listOf(
                                TerminalCompletionSourceEntry(
                                    TerminalCompletionSources.git("git") { request, _ ->
                                        if (request.workingDirectoryUri == "file:///first") {
                                            entered.complete(Unit)
                                            // A host lookup may finish after cancellation; its result must stay obsolete.
                                            withContext(NonCancellable) { release.await() }
                                            branches("feature/first")
                                        } else {
                                            branches("feature/second")
                                        }
                                    },
                                ),
                            ),
                        )
                    val observed =
                        TerminalCompletionEngine { request ->
                            engine.completions(request).onCompletion {
                                if (request.workingDirectoryUri == "file:///first") finished.complete(Unit)
                            }
                        }
                    var context = SwingCompletionContext(shellCapabilities = shell, workingDirectoryUri = "file:///first")
                    val provider = SwingCompletionSuggestionProvider(observed, { context })
                    val accepted = ArrayList<SwingShellSuggestionAcceptance>()
                    val terminal = onEdt { terminal() }
                    try {
                        val old = onEdt { begin(terminal, provider, "git switch feature/", accepted = accepted) }
                        entered.await()
                        onEdt {
                            context = context.copy(workingDirectoryUri = "file:///second")
                            terminal.refreshShellSuggestions()
                            assertFalse(old.isActive)
                        }
                        val current = onEdt { begin(terminal, provider, "git switch feature/", accepted = accepted) }
                        val publication = publication(current)
                        release.complete(Unit)
                        finished.await()
                        onEdt {
                            assertEquals(listOf("feature/second"), current.snapshot.suggestions.map { it.replacementText })
                            assertTrue(old.snapshot.suggestions.isEmpty())
                            assertEquals(SwingShellSuggestionAcceptanceResult.STALE_CONTEXT, old.tryAccept(old.snapshot, 0))
                            assertEquals(SwingShellSuggestionAcceptanceResult.ACCEPTED, current.tryAccept(publication, 0))
                            assertEquals("git switch feature/second", edited(accepted.single()))
                            assertEquals(context, accepted.single().suggestion.interactionContext)
                        }
                    } finally {
                        release.complete(Unit)
                        onEdt { terminal.dispose() }
                    }
                }
            }
        }

    @Test
    fun `refresh invalidates a completed explicit popup before it can be accepted`() =
        runBlocking {
            withTimeout(10_000) {
                val engine =
                    TerminalCompletionEngines.fromSources(
                        listOf(
                            TerminalCompletionSourceEntry(
                                TerminalCompletionSources.git("git") { _, _ -> branches("feature/old") },
                            ),
                        ),
                    )
                val terminal = onEdt { terminal() }
                val accepted = ArrayList<SwingShellSuggestionAcceptance>()
                try {
                    val interaction =
                        onEdt {
                            begin(terminal, SwingCompletionSuggestionProvider(engine), "git switch fe", accepted = accepted)
                        }
                    val old = publication(interaction)
                    onEdt {
                        terminal.refreshShellSuggestions()
                        assertEquals(SwingShellSuggestionAcceptanceResult.STALE_CONTEXT, interaction.tryAccept(old, 0))
                        assertTrue(accepted.isEmpty())
                    }
                } finally {
                    onEdt { terminal.dispose() }
                }
            }
        }

    @Test
    fun `shortening and moving the cursor accepts the live branch once without path or history takeover`() =
        runBlocking {
            withTimeout(10_000) {
                for (shell in listOf(TerminalShellCapabilities.POSIX, TerminalShellCapabilities.POWERSHELL)) {
                    var nativeQueries = 0
                    val learning =
                        TerminalCompletionLearningStore().apply {
                            repeat(10) { recordCommandResult("git switch feature/old", true, null, null, System.currentTimeMillis()) }
                            recordCommandResult("git switch feature/local", true, null, null, System.currentTimeMillis())
                        }
                    val engine =
                        TerminalCompletionEngines.fromSources(
                            listOf(
                                TerminalCompletionSourceEntry(TerminalCompletionSources.git("git") { _, _ -> branches("feature/local") }),
                                TerminalCompletionSourceEntry(
                                    TerminalCompletionSources.path { listOf(TerminalFileEntry("local.txt", false)) },
                                ),
                            ),
                            learningStore = learning,
                            shellProvider =
                                TerminalCompletionShellProvider { _, _, _ ->
                                    nativeQueries++
                                    emptyList()
                                },
                        )
                    val provider = SwingCompletionSuggestionProvider(engine, { SwingCompletionContext(shellCapabilities = shell) })
                    val terminal = onEdt { terminal() }
                    val accepted = ArrayList<SwingShellSuggestionAcceptance>()
                    try {
                        val old = onEdt { begin(terminal, provider, "git switch feature/loc", accepted = accepted) }
                        val oldPublication = publication(old)
                        val line = "git switch feature/lox --quiet"
                        val current = onEdt { begin(terminal, provider, line, line.indexOf('x'), accepted) }
                        val results = publication(current)
                        onEdt {
                            assertEquals(SwingShellSuggestionAcceptanceResult.STALE_CONTEXT, old.tryAccept(oldPublication, 0))
                            assertEquals("git", results.suggestions.first().source)
                            assertEquals(1, results.suggestions.count { it.displayText == "feature/local" })
                            assertTrue(results.suggestions.none { it.kind == "PATH" })
                            assertEquals(SwingShellSuggestionAcceptanceResult.ACCEPTED, current.tryAccept(results, 0))
                            assertEquals("git switch feature/local --quiet", edited(accepted.single()))
                            val replacement = assertNotNull(accepted.single().suggestion.replacementFor(accepted.single().request))
                            assertEquals(1, replacement.deleteAfterCursorCount)
                        }
                        assertEquals(0, nativeQueries)
                    } finally {
                        onEdt { terminal.dispose() }
                    }
                }
            }
        }

    @Test
    fun `native command authority merges duplicate history and excludes unavailable commands through acceptance`() =
        runBlocking {
            withTimeout(10_000) {
                for (shell in listOf(TerminalShellCapabilities.POSIX, TerminalShellCapabilities.POWERSHELL)) {
                    val token = Any()
                    val learning =
                        TerminalCompletionLearningStore().apply {
                            recordCommandResult("cargo build", true, null, null, System.currentTimeMillis())
                            recordCommandResult("cat", true, null, null, System.currentTimeMillis())
                        }
                    val engine =
                        TerminalCompletionEngines.fromSources(
                            emptyList(),
                            learningStore = learning,
                            shellProvider =
                                TerminalCompletionShellProvider { _, context, _ ->
                                    listOf(
                                        TerminalCompletionCandidate(
                                            "cat",
                                            context.replacementStartOffset,
                                            context.replacementEndOffset,
                                            "native",
                                            TerminalCompletionCandidateKind.COMMAND,
                                            feedbackToken = token,
                                        ),
                                    )
                                },
                        )
                    val feedback = ArrayList<SwingShellSuggestionFeedback>()
                    val provider =
                        SwingCompletionSuggestionProvider(
                            engine,
                            { SwingCompletionContext(shellCapabilities = shell) },
                            { feedback += it },
                        )
                    val terminal = onEdt { terminal() }
                    val accepted = ArrayList<SwingShellSuggestionAcceptance>()
                    try {
                        val interaction = onEdt { begin(terminal, provider, "ca", accepted = accepted) }
                        val result = publication(interaction)
                        onEdt {
                            assertEquals(listOf("cat"), result.suggestions.map { it.replacementText })
                            assertEquals(SwingShellSuggestionAcceptanceResult.ACCEPTED, interaction.tryAccept(result, 0))
                            assertEquals("cat", edited(accepted.single()))
                            assertSame(token, feedback.single().suggestion.feedbackToken)
                        }
                    } finally {
                        onEdt { terminal.dispose() }
                    }
                }
            }
        }

    private fun terminal() =
        SwingTerminal(settingsProvider = {
            SwingSettings.create {
                it.smartSuggestionsEnabled = true
                it.shellSuggestionsEnabled = false
            }
        })

    private fun begin(
        terminal: SwingTerminal,
        provider: SwingShellSuggestionProvider,
        line: String,
        cursor: Int = line.length,
        accepted: MutableList<SwingShellSuggestionAcceptance>,
    ): SwingShellSuggestionInteraction {
        val interaction =
            assertNotNull(
                terminal.beginShellSuggestionInteraction(
                    SwingShellSuggestionRequest(line, cursor),
                    editTarget =
                        SwingShellSuggestionEditTarget {
                            SwingShellSuggestionHandler { acceptance ->
                                accepted += acceptance
                                SwingShellSuggestionAcceptanceResult.ACCEPTED
                            }
                        },
                ),
            )
        terminal.requestShellSuggestions(interaction, provider)
        return interaction
    }

    private suspend fun publication(interaction: SwingShellSuggestionInteraction): SwingShellSuggestionSnapshot {
        val ready = CompletableDeferred<SwingShellSuggestionSnapshot>()
        val listener =
            SwingShellSuggestionInteractionListener {
                if (it.snapshot.suggestions.isNotEmpty()) ready.complete(it.snapshot)
            }
        onEdt {
            interaction.addChangeListener(listener)
            listener.onInteractionChanged(interaction)
        }
        return try {
            ready.await()
        } finally {
            onEdt { interaction.removeChangeListener(listener) }
        }
    }

    private fun branches(vararg names: String) =
        TerminalGitCompletionSnapshot(
            localBranches = names.map { TerminalCompletionDomainValue(it) },
        )

    private fun edited(acceptance: SwingShellSuggestionAcceptance): String =
        with(acceptance) {
            request.commandText.replaceRange(suggestion.replacementStartOffset, suggestion.replacementEndOffset, suggestion.replacementText)
        }

    private suspend fun <T> onEdt(action: () -> T): T = withContext(Dispatchers.Swing) { action() }
}

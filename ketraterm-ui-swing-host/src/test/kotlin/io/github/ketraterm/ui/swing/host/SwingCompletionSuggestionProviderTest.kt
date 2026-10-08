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
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionAccentRole
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionFeedbackHandler
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import kotlin.test.*

class SwingCompletionSuggestionProviderTest {
    @Test
    fun `opening captures context and feedback before deferred engine work`() =
        runBlocking {
            val initial = SwingCompletionContext(profileId = "bash", workingDirectoryUri = "file:///first")
            var context = initial
            var reads = 0
            var calls = 0
            val feedback = SwingShellSuggestionFeedbackHandler { }
            val provider =
                SwingCompletionSuggestionProvider(
                    engine = { request ->
                        assertFalse(SwingUtilities.isEventDispatchThread())
                        assertEquals("file:///first", request.workingDirectoryUri)
                        calls++
                        flowOf(listOf(TerminalCompletionCandidate("status", 4, 5, "spec", TerminalCompletionCandidateKind.SUBCOMMAND)))
                    },
                    contextProvider = {
                        assertTrue(SwingUtilities.isEventDispatchThread())
                        reads++
                        context
                    },
                    feedbackHandler = feedback,
                )
            val source = withContext(Dispatchers.Swing) { provider.open(request("git s", 5)) }
            assertEquals(1, reads)
            assertEquals(0, calls)
            assertSame(feedback, source.feedbackHandler)
            context = initial.copy(workingDirectoryUri = "file:///second")
            val suggestions = withContext(Dispatchers.Default) { source.suggestions.last() }
            assertEquals(1, reads)
            assertEquals(1, calls)
            assertSame(initial, suggestions.single().interactionContext)
        }

    @Test
    fun `provider feedback token survives adaptation independently of request context`() =
        runBlocking {
            val token = Any()
            val context = SwingCompletionContext(profileId = "bash")
            val provider =
                SwingCompletionSuggestionProvider(
                    engine = {
                        flowOf(
                            listOf(
                                TerminalCompletionCandidate(
                                    "status",
                                    4,
                                    5,
                                    "custom-source",
                                    TerminalCompletionCandidateKind.SUBCOMMAND,
                                    feedbackToken = token,
                                ),
                            ),
                        )
                    },
                    contextProvider = { context },
                )
            val suggestion = provider.suggestions(request("git s", 5)).last().single()
            assertSame(token, suggestion.feedbackToken)
            assertSame(context, suggestion.interactionContext)
        }

    @Test
    fun `host source labels are detached bounded and do not alter source identity`(): Unit =
        runBlocking {
            val labels = mutableMapOf("product-source" to "x".repeat(126) + "😀suffix")
            val engine =
                TerminalCompletionEngine {
                    flowOf(listOf(TerminalCompletionCandidate("value", 0, 1, "product-source", TerminalCompletionCandidateKind.ARGUMENT)))
                }
            val provider = SwingCompletionSuggestionProvider(engine, { SwingCompletionContext.EMPTY }, labels)
            labels["product-source"] = "changed"
            val suggestion = provider.suggestions(request("x", cursorOffset = 1)).last().single()
            assertEquals("product-source", suggestion.source)
            assertEquals("x".repeat(126) + "…", suggestion.sourceDisplayText)
            assertFailsWith<IllegalArgumentException> {
                SwingCompletionSuggestionProvider(engine, { SwingCompletionContext.EMPTY }, mapOf("product-source" to "  "))
            }
        }

    @Test
    fun `context is captured once off EDT and retained for progressive results`() =
        runBlocking {
            val initial = SwingCompletionContext(profileId = "bash", workingDirectoryUri = "file:///first")
            val current = AtomicReference(initial)
            var reads = 0
            val provider =
                SwingCompletionSuggestionProvider(
                    engine =
                        { request ->
                            assertEquals("file:///first", request.workingDirectoryUri)
                            current.set(initial.copy(workingDirectoryUri = "file:///second"))
                            flowOf(
                                listOf(TerminalCompletionCandidate("status", 4, 5, "spec", TerminalCompletionCandidateKind.SUBCOMMAND)),
                                listOf(TerminalCompletionCandidate("stash", 4, 5, "spec", TerminalCompletionCandidateKind.SUBCOMMAND)),
                            )
                        },
                    contextProvider = {
                        assertFalse(SwingUtilities.isEventDispatchThread())
                        reads++
                        current.get()
                    },
                )
            val results = withContext(Dispatchers.Default) { provider.suggestions(request("git s", 5)).toList() }
            assertEquals(1, reads)
            assertEquals(2, results.size)
            results.forEach { assertSame(initial, it.single().interactionContext) }
            assertEquals("file:///second", current.get().workingDirectoryUri)
        }

    @Test
    fun `forwards live host context and adapts candidates`() =
        runBlocking {
            lateinit var captured: TerminalCompletionRequest
            val requestContext =
                SwingCompletionContext(
                    profileId = "bash",
                    workingDirectoryUri = "file:///repo",
                    shellCapabilities = TerminalShellCapabilities.POSIX,
                )
            val provider =
                SwingCompletionSuggestionProvider(
                    engine =
                        { request ->
                            captured = request
                            flowOf(
                                listOf(
                                    TerminalCompletionCandidate(
                                        replacementText = "status",
                                        replacementStartOffset = 4,
                                        replacementEndOffset = 7,
                                        source = "spec",
                                        kind = TerminalCompletionCandidateKind.SUBCOMMAND,
                                        displayText = "status",
                                        detail = "show status",
                                        matchedRanges =
                                            TerminalCompletionMatchRanges.fromPackedOffsets(
                                                "status",
                                                intArrayOf(0, 2),
                                            ),
                                    ),
                                ),
                            )
                        },
                    contextProvider = { requestContext },
                )

            val suggestions = provider.suggestions(request("git ste", cursorOffset = 6)).last()

            assertEquals("bash", captured.profileId)
            assertEquals("file:///repo", captured.workingDirectoryUri)
            assertEquals(TerminalShellCapabilities.POSIX, captured.shellCapabilities)
            assertEquals("status", suggestions.single().replacementText)
            assertEquals(4, suggestions.single().replacementStartOffset)
            assertEquals(7, suggestions.single().replacementEndOffset)
            assertEquals("show status", suggestions.single().detail)
            assertEquals("SUBCOMMAND", suggestions.single().kind)
            assertEquals("Built-in", suggestions.single().sourceDisplayText)
            assertSame(requestContext, suggestions.single().interactionContext)
            assertContentEquals(intArrayOf(0, 2), suggestions.single().matchedRanges.copyPackedOffsets())
        }

    @Test
    fun `maps provider identifiers once into bounded renderer-neutral labels`() =
        runBlocking {
            val sources =
                listOf(
                    "spec",
                    "learned",
                    "observed",
                    "intellij-git-branch",
                    "intellij-gradle-task",
                    "intellij-project-file",
                    "path",
                    "intellij-git-status-path",
                    "intellij-custom_source",
                    "legitimate-provider",
                    "pathology",
                    "custom-${"x".repeat(500)}",
                )
            val provider =
                SwingCompletionSuggestionProvider(
                    {
                        flowOf(
                            sources.map { source ->
                                TerminalCompletionCandidate(
                                    replacementText = source,
                                    replacementStartOffset = 0,
                                    replacementEndOffset = 0,
                                    source = source,
                                    kind = TerminalCompletionCandidateKind.ARGUMENT,
                                )
                            },
                        )
                    },
                )

            val suggestions = provider.suggestions(request("x", cursorOffset = 1)).last()
            val labels = suggestions.map { it.sourceDisplayText }

            assertEquals(
                listOf(
                    "Built-in",
                    "Learned",
                    "Learned",
                    "Intellij git branch",
                    "Intellij gradle task",
                    "Intellij project file",
                    "Path",
                    "Intellij git status path",
                    "Intellij custom source",
                    "Legitimate provider",
                    "Pathology",
                ),
                labels.dropLast(1),
            )
            assertTrue(labels.last().startsWith("Custom "))
            assertTrue(labels.last().endsWith("…"))
            assertTrue(labels.last().length <= 128)
            assertEquals(SwingShellSuggestionAccentRole.HISTORY, suggestions[1].accentRole)
            assertEquals(SwingShellSuggestionAccentRole.HISTORY, suggestions[2].accentRole)
        }

    @Test
    fun `invalid UTF-16 cursor is rejected before engine invocation`() =
        runBlocking {
            var invoked = false
            val provider =
                SwingCompletionSuggestionProvider(
                    {
                        invoked = true
                        flowOf(emptyList())
                    },
                )

            assertTrue(provider.suggestions(request("😀", cursorOffset = 1)).last().isEmpty())
            assertEquals(false, invoked)
        }

    private fun request(
        commandText: String,
        cursorOffset: Int,
    ): SwingShellSuggestionRequest =
        SwingShellSuggestionRequest(
            commandText = commandText,
            cursorOffset = cursorOffset,
        )
}

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
package io.github.ketraterm.ui.swing.suggestion

import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import javax.swing.SwingUtilities

class SwingShellSuggestionInteractionTest {
    @ParameterizedTest
    @EnumSource(SwingShellSuggestionAcceptanceResult::class)
    fun `detached acceptance reports the actual result exactly once`(result: SwingShellSuggestionAcceptanceResult) =
        onEdt {
            val token = Any()
            val candidate = suggestion("status").copy(feedbackToken = token)
            val handler = RecordingHandler(result)
            val feedback = ArrayList<SwingShellSuggestionFeedback>()
            val interaction = SwingShellSuggestionInteraction(request(), handler, { feedback += it })
            interaction.publish(listOf(candidate))
            val publication = interaction.snapshot

            assertEquals(result, interaction.tryAccept(publication, 0))

            assertEquals(1, handler.attempts)
            assertEquals(1, handler.closes)
            assertFalse(interaction.isActive)
            assertTrue(interaction.isClosed)
            assertEquals(
                if (result == SwingShellSuggestionAcceptanceResult.ACCEPTED) {
                    SwingShellSuggestionCloseReason.ACCEPTED
                } else {
                    SwingShellSuggestionCloseReason.REJECTED
                },
                interaction.closeReason,
            )
            assertEquals(
                if (result == SwingShellSuggestionAcceptanceResult.ACCEPTED) {
                    SwingShellSuggestionFeedbackKind.ACCEPTED
                } else {
                    SwingShellSuggestionFeedbackKind.REJECTED
                },
                feedback.single().kind,
            )
            assertEquals(result, feedback.single().acceptanceResult)
            assertSame(candidate, feedback.single().suggestion)
            assertSame(token, feedback.single().suggestion.feedbackToken)
            assertEquals(SwingShellSuggestionAcceptanceResult.STALE_CONTEXT, interaction.tryAccept(publication, 0))
            assertFalse(interaction.publish(listOf(suggestion("late"))))
            interaction.close()
            assertEquals(1, handler.attempts)
            assertEquals(1, handler.closes)
            assertEquals(1, feedback.size)
        }

    @Test
    fun `publication owns candidates while selection reuses their immutable list`() =
        onEdt {
            val candidates = mutableListOf(suggestion("first"), suggestion("second"))
            val interaction = SwingShellSuggestionInteraction(request())
            interaction.publish(candidates)
            val publication = interaction.snapshot
            candidates.clear()

            assertEquals(listOf("first", "second"), publication.suggestions.map { it.replacementText })
            assertThrows(UnsupportedOperationException::class.java) {
                (publication.suggestions as MutableList<SwingShellSuggestion>).clear()
            }
            assertTrue(interaction.select(publication, 1))
            assertSame(publication.suggestions, interaction.snapshot.suggestions)
            assertEquals("second", interaction.snapshot.selectedSuggestion?.replacementText)
            assertTrue(interaction.select(publication, 0), "Selection does not invalidate the candidate publication")
            interaction.close()
        }

    @Test
    fun `equal reranking and foreign snapshots cannot authorize a stale action`() =
        onEdt {
            val handler = RecordingHandler()
            val feedback = ArrayList<SwingShellSuggestionFeedback>()
            val interaction = SwingShellSuggestionInteraction(request(), handler, { feedback += it })
            val candidates = listOf(suggestion("status"))
            interaction.publish(candidates)
            val obsolete = interaction.snapshot
            interaction.publish(candidates)
            val current = interaction.snapshot
            val foreign = SwingShellSuggestionInteraction(request())
            foreign.publish(candidates)

            assertNotSame(obsolete.suggestions, current.suggestions)
            for (snapshot in listOf(obsolete, foreign.snapshot)) {
                assertFalse(interaction.select(snapshot, 0))
                assertEquals(SwingShellSuggestionAcceptanceResult.STALE_CONTEXT, interaction.tryAccept(snapshot, 0))
                assertFalse(interaction.dismiss(snapshot))
            }
            assertTrue(interaction.isActive)
            assertSame(current, interaction.snapshot)
            assertEquals(0, handler.attempts)
            assertTrue(feedback.isEmpty())
            interaction.close()
            foreign.close()
        }

    @Test
    fun `retained selection uses the latest provider item identity after reranking`() =
        onEdt {
            val originalToken = Any()
            val latestToken = Any()
            val original = suggestion("status").copy(feedbackToken = originalToken)
            val latest = original.copy(feedbackToken = latestToken)
            val feedback = ArrayList<SwingShellSuggestionFeedback>()
            val interaction = SwingShellSuggestionInteraction(request(), RecordingHandler(), { feedback += it })
            interaction.publish(listOf(original), selectedIndex = 0)
            val obsolete = interaction.snapshot

            interaction.publish(listOf(suggestion("switch"), latest))

            assertEquals(1, interaction.snapshot.selectedIndex)
            assertSame(latest, interaction.snapshot.selectedSuggestion)
            assertEquals(SwingShellSuggestionAcceptanceResult.STALE_CONTEXT, interaction.tryAccept(obsolete, 0))
            assertEquals(SwingShellSuggestionAcceptanceResult.ACCEPTED, interaction.tryAccept(interaction.snapshot, 1))
            assertSame(latestToken, feedback.single().suggestion.feedbackToken)
        }

    @Test
    fun `dismissal of an obsolete publication cannot penalize a newer provider item`() =
        onEdt {
            val latestToken = Any()
            val handler = RecordingHandler()
            val feedback = ArrayList<SwingShellSuggestionFeedback>()
            val interaction = SwingShellSuggestionInteraction(request(), handler, { feedback += it })
            interaction.publish(listOf(suggestion("status")), selectedIndex = 0)
            val obsolete = interaction.snapshot
            interaction.publish(listOf(suggestion("status").copy(feedbackToken = latestToken)))
            val current = interaction.snapshot

            assertFalse(interaction.dismiss(obsolete))

            assertTrue(interaction.isActive)
            assertSame(current, interaction.snapshot)
            assertEquals(0, handler.closes)
            assertTrue(feedback.isEmpty())
            assertTrue(interaction.dismiss(current))
            assertEquals(SwingShellSuggestionFeedbackKind.DISMISSED, feedback.single().kind)
            assertSame(latestToken, feedback.single().suggestion.feedbackToken)
            assertEquals(1, handler.closes)
        }

    @Test
    fun `reentrant publication supersedes remaining older observer notifications`() =
        onEdt {
            val interaction = SwingShellSuggestionInteraction(request())
            val observed = ArrayList<String>()
            var replace = true
            interaction.addChangeListener {
                if (replace) {
                    replace = false
                    interaction.publish(listOf(suggestion("new")))
                }
            }
            interaction.addChangeListener {
                observed +=
                    it.snapshot.suggestions
                        .single()
                        .replacementText
            }

            interaction.publish(listOf(suggestion("old")))

            assertEquals(listOf("new"), observed)
            interaction.close()
        }

    @Test
    fun `reentrant presentation replacement cannot redirect acceptance or feedback`() =
        onEdt {
            val original = suggestion("original")
            val handler = RecordingHandler()
            val feedback = ArrayList<SwingShellSuggestionFeedback>()
            val replacementFeedback = ArrayList<SwingShellSuggestionFeedback>()
            val interaction = SwingShellSuggestionInteraction(request(), handler, { feedback += it })
            interaction.publish(listOf(original))
            val publication = interaction.snapshot
            var replacement: SwingShellSuggestionInteraction? = null
            interaction.addChangeListener {
                if (!it.isActive && !it.isClosed) {
                    assertEquals(SwingShellSuggestionAcceptanceResult.STALE_CONTEXT, it.tryAccept(publication, 0))
                    replacement =
                        SwingShellSuggestionInteraction(request(), feedbackHandler = { event ->
                            replacementFeedback += event
                        }).also { newer ->
                            newer.publish(listOf(suggestion("replacement")))
                        }
                }
            }

            assertEquals(SwingShellSuggestionAcceptanceResult.ACCEPTED, interaction.tryAccept(publication, 0))

            assertEquals(1, handler.attempts)
            assertSame(original, feedback.single().suggestion)
            assertTrue(checkNotNull(replacement).isActive)
            assertTrue(replacementFeedback.isEmpty())
            checkNotNull(replacement).close()
        }

    @Test
    fun `invalidation during accepting revokes the captured edit before admission`() =
        onEdt {
            var attempts = 0
            val handler =
                SwingShellSuggestionHandler {
                    attempts++
                    SwingShellSuggestionAcceptanceResult.ACCEPTED
                }
            val feedback = ArrayList<SwingShellSuggestionFeedback>()
            val interaction = SwingShellSuggestionInteraction(request(), handler, { feedback += it })
            interaction.publish(listOf(suggestion("status")))
            interaction.addChangeListener {
                if (!it.isActive && !it.isClosed) it.close(SwingShellSuggestionCloseReason.INVALIDATED)
            }

            assertEquals(SwingShellSuggestionAcceptanceResult.STALE_CONTEXT, interaction.tryAccept(interaction.snapshot, 0))

            assertEquals(0, attempts)
            assertEquals(SwingShellSuggestionCloseReason.REJECTED, interaction.closeReason)
            assertEquals(SwingShellSuggestionFeedbackKind.REJECTED, feedback.single().kind)
            assertEquals(SwingShellSuggestionAcceptanceResult.STALE_CONTEXT, feedback.single().acceptanceResult)
        }

    @Test
    fun `closure from inside the editing authority preserves its admitted result`() =
        onEdt {
            lateinit var interaction: SwingShellSuggestionInteraction
            var attempts = 0
            var closes = 0
            val handler =
                object : SwingShellSuggestionHandler {
                    override fun tryAccept(acceptance: SwingShellSuggestionAcceptance): SwingShellSuggestionAcceptanceResult {
                        attempts++
                        interaction.close(SwingShellSuggestionCloseReason.INVALIDATED)
                        return SwingShellSuggestionAcceptanceResult.ACCEPTED
                    }

                    override fun close() {
                        closes++
                    }
                }
            val feedback = ArrayList<SwingShellSuggestionFeedback>()
            interaction = SwingShellSuggestionInteraction(request(), handler, { feedback += it })
            interaction.publish(listOf(suggestion("status")))
            val publication = interaction.snapshot

            assertEquals(SwingShellSuggestionAcceptanceResult.ACCEPTED, interaction.tryAccept(publication, 0))

            assertTrue(interaction.isClosed)
            assertEquals(SwingShellSuggestionCloseReason.ACCEPTED, interaction.closeReason)
            assertEquals(SwingShellSuggestionFeedbackKind.ACCEPTED, feedback.single().kind)
            assertEquals(SwingShellSuggestionAcceptanceResult.ACCEPTED, feedback.single().acceptanceResult)
            assertEquals(SwingShellSuggestionAcceptanceResult.STALE_CONTEXT, interaction.tryAccept(publication, 0))
            interaction.close()
            assertEquals(1, attempts)
            assertEquals(1, closes)
            assertEquals(1, feedback.size)
        }

    @Test
    fun `acceptance cancellation closes the capability without emitting preference feedback`() =
        onEdt {
            val failure = CancellationException("editor cancelled")
            var attempts = 0
            var closes = 0
            val handler =
                object : SwingShellSuggestionHandler {
                    override fun tryAccept(acceptance: SwingShellSuggestionAcceptance): SwingShellSuggestionAcceptanceResult {
                        attempts++
                        throw failure
                    }

                    override fun close() {
                        closes++
                    }
                }
            val feedback = ArrayList<SwingShellSuggestionFeedback>()
            val interaction = SwingShellSuggestionInteraction(request(), handler, { feedback += it })
            interaction.publish(listOf(suggestion("status")))
            val publication = interaction.snapshot

            assertSame(failure, assertThrows(CancellationException::class.java) { interaction.tryAccept(publication, 0) })

            assertEquals(SwingShellSuggestionCloseReason.FAILED, interaction.closeReason)
            assertEquals(SwingShellSuggestionAcceptanceResult.STALE_CONTEXT, interaction.tryAccept(publication, 0))
            interaction.close()
            assertEquals(1, attempts)
            assertEquals(1, closes)
            assertTrue(feedback.isEmpty())
        }

    @Test
    fun `feedback failure propagates after final admission and cannot retry the edit`() =
        onEdt {
            val failure = IllegalStateException("feedback failed")
            val handler = RecordingHandler()
            var feedbackCalls = 0
            val interaction =
                SwingShellSuggestionInteraction(request(), handler, {
                    feedbackCalls++
                    throw failure
                })
            interaction.publish(listOf(suggestion("status")))
            val publication = interaction.snapshot

            assertSame(failure, assertThrows(IllegalStateException::class.java) { interaction.tryAccept(publication, 0) })

            assertTrue(interaction.isClosed)
            assertEquals(SwingShellSuggestionCloseReason.ACCEPTED, interaction.closeReason)
            assertEquals(SwingShellSuggestionAcceptanceResult.STALE_CONTEXT, interaction.tryAccept(publication, 0))
            assertEquals(1, handler.attempts)
            assertEquals(1, handler.closes)
            assertEquals(1, feedbackCalls)
        }

    @Test
    fun `source receives admitted feedback exactly once when the owner observer fails`() =
        onEdt {
            val failure = IllegalStateException("owner feedback failed")
            val handler = RecordingHandler()
            val ownerFeedback = ArrayList<SwingShellSuggestionFeedback>()
            val sourceFeedback = ArrayList<SwingShellSuggestionFeedback>()
            val interaction =
                SwingShellSuggestionInteraction(request(), handler, {
                    ownerFeedback += it
                    throw failure
                })
            interaction.beginSource()
            interaction.attachFeedback { sourceFeedback += it }
            interaction.publish(listOf(suggestion("status")))
            val publication = interaction.snapshot

            assertSame(failure, assertThrows(IllegalStateException::class.java) { interaction.tryAccept(publication, 0) })

            assertSame(ownerFeedback.single(), sourceFeedback.single())
            assertEquals(SwingShellSuggestionFeedbackKind.ACCEPTED, sourceFeedback.single().kind)
            assertEquals(SwingShellSuggestionAcceptanceResult.ACCEPTED, sourceFeedback.single().acceptanceResult)
            assertEquals(SwingShellSuggestionCloseReason.ACCEPTED, interaction.closeReason)
            assertEquals(SwingShellSuggestionAcceptanceResult.STALE_CONTEXT, interaction.tryAccept(publication, 0))
            interaction.close()
            assertEquals(1, handler.attempts)
            assertEquals(1, handler.closes)
            assertEquals(1, ownerFeedback.size)
            assertEquals(1, sourceFeedback.size)
            assertTrue(failure.suppressed.isEmpty())
        }

    @Test
    fun `passive closure is neutral while explicit selected dismissal reports preference`() =
        onEdt {
            val feedback = ArrayList<SwingShellSuggestionFeedback>()
            for (reason in listOf(
                SwingShellSuggestionCloseReason.INVALIDATED,
                SwingShellSuggestionCloseReason.SUPERSEDED,
                SwingShellSuggestionCloseReason.DISPOSED,
                SwingShellSuggestionCloseReason.CANCELLED,
            )) {
                val handler = RecordingHandler()
                val interaction = SwingShellSuggestionInteraction(request(), handler, { feedback += it })
                interaction.publish(listOf(suggestion("status")), selectedIndex = 0)
                interaction.close(reason)
                assertEquals(reason, interaction.closeReason)
                assertEquals(1, handler.closes)
            }
            assertTrue(feedback.isEmpty())
            val dismissed = SwingShellSuggestionInteraction(request(), feedbackHandler = { feedback += it })
            dismissed.publish(listOf(suggestion("status")), selectedIndex = 0)
            assertTrue(dismissed.dismiss(dismissed.snapshot))
            assertFalse(dismissed.dismiss(dismissed.snapshot))
            assertEquals(SwingShellSuggestionFeedbackKind.DISMISSED, feedback.single().kind)
            assertNull(feedback.single().acceptanceResult)
        }

    @Test
    fun `construction publication and admission enforce EDT ownership`() {
        assertFalse(SwingUtilities.isEventDispatchThread())
        assertThrows(IllegalStateException::class.java) { SwingShellSuggestionInteraction(request()) }
        lateinit var interaction: SwingShellSuggestionInteraction
        onEdt {
            interaction = SwingShellSuggestionInteraction(request())
            interaction.publish(listOf(suggestion("status")))
        }
        try {
            assertThrows(IllegalStateException::class.java) { interaction.publish(listOf(suggestion("late"))) }
            assertThrows(IllegalStateException::class.java) { interaction.tryAccept(interaction.snapshot, 0) }
            assertThrows(IllegalStateException::class.java) { interaction.dismiss(interaction.snapshot) }
            assertThrows(IllegalStateException::class.java) { interaction.close() }
        } finally {
            onEdt { interaction.close() }
        }
    }

    private class RecordingHandler(
        private val result: SwingShellSuggestionAcceptanceResult = SwingShellSuggestionAcceptanceResult.ACCEPTED,
    ) : SwingShellSuggestionHandler {
        var attempts = 0
        var closes = 0

        override fun tryAccept(acceptance: SwingShellSuggestionAcceptance): SwingShellSuggestionAcceptanceResult {
            attempts++
            return result
        }

        override fun close() {
            closes++
        }
    }

    private fun request() = SwingShellSuggestionRequest("git s", 5)

    private fun suggestion(text: String) = SwingShellSuggestion(text, 4, 5, "test", "SUBCOMMAND")

    private fun onEdt(action: () -> Unit) = SwingUtilities.invokeAndWait(action)
}

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
package io.github.ketraterm.app.ui

import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.session.*
import io.github.ketraterm.testkit.MockConnector
import io.github.ketraterm.ui.swing.api.SwingHostServices
import io.github.ketraterm.ui.swing.api.TerminalUiDispatcher
import io.github.ketraterm.ui.swing.settings.SwingSettings
import io.github.ketraterm.ui.swing.suggestion.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.awt.event.FocusEvent
import java.awt.event.KeyEvent
import java.util.concurrent.FutureTask
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.*

@OptIn(ExperimentalShellSuggestionsApi::class, ExperimentalCoroutinesApi::class)
class SharedCompletionSuggestionsTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `a lone candidate never flashes in the popup and progressive results publish only alternatives`(finite: Boolean) =
        withFixture { f ->
            val candidates = f.ranked.value
            f.ranked.value = candidates.take(1)
            if (finite) f.results = flowOf(f.ranked.value)
            f.popupAutomatic = true
            f.enable()
            f.advance()
            assertTrue(f.popupPublications.isEmpty())
            assertFalse(f.terminal.currentShellSuggestionState().visible)
            assertSame(
                candidates.first(),
                f.terminal.inlineShellSuggestionPresentation.value
                    ?.selectedSuggestion,
            )
            if (!finite) {
                f.ranked.value = candidates
                f.dispatcher.scheduler.runCurrent()
                assertEquals(candidates.drop(1), f.popupPublications.last())
                f.ranked.value = candidates.take(1)
                f.dispatcher.scheduler.runCurrent()
                assertFalse(f.terminal.currentShellSuggestionState().visible)
            }
            assertTrue(f.popupPublications.none { candidates.first() in it })
            assertEquals(1, f.collected)
            assertEquals(1, f.opened.size)
            assertTrue(f.feedback.isEmpty())
        }

    @ParameterizedTest
    @ValueSource(strings = ["disabled", "rewrite", "spaces", "shell-text"])
    fun `single result remains in popup when inline is disabled or cannot actually render`(reason: String) =
        withFixture { f ->
            f.ranked.value =
                listOf(
                    when (reason) {
                        "rewrite" -> SwingShellSuggestion("show", 0, 5, "shell", "COMMAND")
                        "spaces" -> SwingShellSuggestion(" ", 5, 5, "shell", "ARGUMENT")
                        else -> f.ranked.value.first()
                    },
                )
            if (reason == "shell-text") f.connector.feedFromHost("hell\u001b[4D".toByteArray())
            f.popupAutomatic = true
            f.enabled = reason != "disabled"
            f.terminal.reloadSettings()
            f.advance()
            assertNull(f.terminal.inlineShellSuggestionPresentation.value)
            assertEquals(f.ranked.value, f.popupPublications.last())
            assertEquals(1, f.collected)
        }

    @Test
    fun `inline dismissal allows an explicit popup without reviving the same inline result`() =
        withFixture { f ->
            f.ranked.value = f.ranked.value.take(1)
            f.popupAutomatic = true
            f.enable()
            f.advance()
            assertTrue(f.press(KeyEvent.VK_ESCAPE).isConsumed)
            f.dispatcher.scheduler.runCurrent()
            assertNull(f.terminal.inlineShellSuggestionPresentation.value)
            val popup = f.popup()
            f.dispatcher.scheduler.runCurrent()
            assertEquals(f.ranked.value, popup.snapshot.suggestions)
            assertTrue(f.terminal.currentShellSuggestionState().visible)
            assertNull(f.terminal.inlineShellSuggestionPresentation.value)
            assertEquals(1, f.feedback.size)
            assertTrue(f.connector.writtenBytes.isEmpty())
        }

    @Test
    fun `source failure retires both consumers and an explicit retry opens fresh work`() =
        withFixture { f ->
            val release = CompletableDeferred<Unit>()
            val failure = IllegalStateException("source failed")
            val failures = mutableListOf<Exception>()
            f.terminal.setShellSuggestionFailureHandler { _, error -> failures += error }
            f.results =
                flow {
                    emit(f.ranked.value)
                    release.await()
                    throw failure
                }
            val popup = f.popup()
            f.enable()
            f.advance()
            assertEquals(1, f.collected)
            release.complete(Unit)
            f.dispatcher.scheduler.runCurrent()
            assertFalse(popup.isActive)
            assertNull(f.terminal.inlineShellSuggestionPresentation.value)
            assertEquals(1, failures.size)
            failures.forEach { assertEquals("source failed", it.message) }
            f.results = flowOf(f.ranked.value)
            val retry = f.popup()
            f.dispatcher.scheduler.runCurrent()
            assertTrue(retry.isActive)
            assertEquals(2, f.collected)
            assertNotNull(f.terminal.inlineShellSuggestionPresentation.value)
        }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `both surfaces use the common debounce regardless of independent inline delay`(inlineFirst: Boolean) =
        withFixture { f ->
            f.popupAutomatic = true
            f.popupDelay = 75
            f.inlineDelay = if (inlineFirst) 0 else 150
            f.terminal.setSharedShellSuggestionProvider(f.completions)
            f.enable()
            f.advance()
            assertEquals(1, f.opened.size)
            assertNotNull(f.terminal.inlineShellSuggestionPresentation.value)
            assertTrue(f.terminal.currentShellSuggestionState().visible)
            f.advance()
            assertEquals(1, f.collected)
            assertEquals(1, f.opened.size)
            assertNotNull(f.terminal.inlineShellSuggestionPresentation.value)
            assertEquals(1, f.terminal.currentShellSuggestionState().count)
        }

    @ParameterizedTest
    @ValueSource(strings = ["options", "directory", "edit"])
    fun `different options or edit authority never reuse a completed source`(change: String) =
        withFixture { f ->
            f.results = flowOf(f.ranked.value)
            if (change == "options") {
                val popup =
                    assertNotNull(
                        f.terminal.beginShellSuggestionInteraction(
                            f.request.copy(includeCommandNames = false),
                            SwingShellSuggestionTrigger.EXPLICIT,
                        ),
                    )
                f.terminal.presentShellSuggestions(popup)
                f.terminal.requestShellSuggestions(popup, f.completions)
            } else {
                f.popup()
            }
            f.dispatcher.scheduler.runCurrent()
            assertEquals(1, f.collected)
            when (change) {
                "directory" -> f.history.recordCurrentWorkingDirectory("file:///another")
                "edit" -> f.setLine("git st")
            }
            f.dispatcher.scheduler.runCurrent()
            f.enable()
            f.terminal.refreshShellSuggestions()
            f.popup()
            f.advance()
            assertEquals(2, f.collected)
            assertEquals(2, f.opened.size)
        }

    @Test
    fun `an empty finite source closes both requests without retaining popup observation`() =
        withFixture { f ->
            f.results = flowOf(emptyList())
            val popup = f.popup()
            f.enable()
            f.advance()
            assertFalse(popup.isActive)
            assertNull(f.terminal.inlineShellSuggestionPresentation.value)
            assertFalse(f.terminal.currentShellSuggestionState().visible)
            assertEquals(1, f.collected)
        }

    @Test
    fun `one finite evaluation publishes both surfaces and Down selects the first alternative`() =
        withFixture { f ->
            f.results = flowOf(f.ranked.value)
            f.enable()
            val popup = f.popup()
            f.advance()
            assertEquals(listOf(f.request), f.opened)
            assertEquals(1, f.collected)
            assertEquals(f.ranked.value.drop(1), popup.snapshot.suggestions)
            assertEquals(-1, popup.snapshot.selectedIndex)
            assertSame(
                f.ranked.value.first(),
                f.terminal.inlineShellSuggestionPresentation.value
                    ?.selectedSuggestion,
            )

            assertTrue(f.press(KeyEvent.VK_DOWN).isConsumed)
            f.dispatcher.scheduler.runCurrent()
            assertSame(f.ranked.value[1], popup.snapshot.selectedSuggestion)
            assertNull(f.terminal.inlineShellSuggestionPresentation.value)
            assertEquals(f.ranked.value.drop(1), popup.snapshot.suggestions, "Navigation must not reinsert the omitted row")
            assertTrue(f.press(KeyEvent.VK_TAB).isConsumed)
            f.dispatcher.scheduler.runCurrent()
            assertEquals("\u007fstash", f.connector.writtenBytes.toString(Charsets.UTF_8))
            assertSame(f.ranked.value[1], f.feedback.single { it.kind == SwingShellSuggestionFeedbackKind.ACCEPTED }.suggestion)
        }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `disabling visible inline restores the full passive popup without another evaluation`(onlyCandidate: Boolean) =
        withFixture { f ->
            if (onlyCandidate) f.ranked.value = f.ranked.value.take(1)
            f.results = flowOf(f.ranked.value)
            val popup = f.popup()
            f.enable()
            f.advance()
            assertEquals(f.ranked.value.drop(1), popup.snapshot.suggestions)
            f.enabled = false
            f.terminal.reloadSettings()
            f.dispatcher.scheduler.runCurrent()
            assertEquals(f.ranked.value, popup.snapshot.suggestions)
            assertFalse(popup.hasExplicitSelection)
            assertEquals(1, f.collected)
        }

    @Test
    fun `explicit request options replace the entire shared ranking`() =
        withFixture { f ->
            f.results = flowOf(f.ranked.value)
            f.enable()
            f.advance()
            val refreshed = f.ranked.value.reversed()
            f.results = flowOf(refreshed)
            val popup =
                assertNotNull(
                    f.terminal.beginShellSuggestionInteraction(
                        f.request.copy(includeCommandNames = false),
                        SwingShellSuggestionTrigger.EXPLICIT,
                    ),
                )
            f.terminal.presentShellSuggestions(popup)
            f.terminal.requestShellSuggestions(popup, f.completions)
            f.dispatcher.scheduler.runCurrent()
            assertEquals(2, f.collected)
            assertEquals(refreshed.drop(1), popup.snapshot.suggestions)
            assertSame(
                refreshed.first(),
                f.terminal.inlineShellSuggestionPresentation.value
                    ?.selectedSuggestion,
            )
            assertNotNull(f.terminal.inlineShellSuggestionPresentation.value)
        }

    @Test
    fun `invisible inline spaces preserve popup candidates and shell Tab`() =
        withFixture { f ->
            f.ranked.value = listOf(SwingShellSuggestion(" ", 5, 5, "spec", "COMMAND"))
            val popup = f.popup()
            f.enable()
            f.advance()
            assertNull(f.terminal.inlineShellSuggestionPresentation.value)
            assertEquals(f.ranked.value, popup.snapshot.suggestions)
            assertEquals(SwingShellSuggestionAcceptanceResult.UNAVAILABLE, f.terminal.acceptInlineShellSuggestion())
            f.press(KeyEvent.VK_TAB)
            f.dispatcher.scheduler.runCurrent()
            assertEquals("\t", f.connector.writtenBytes.toString(Charsets.UTF_8))
        }

    @Test
    fun `Escape dismisses a lone inline candidate without resurrecting its hidden popup duplicate`() =
        withFixture { f ->
            f.ranked.value = f.ranked.value.take(1)
            f.results = flowOf(f.ranked.value)
            val popup = f.popup()
            f.enable()
            f.advance()
            assertNotNull(f.terminal.inlineShellSuggestionPresentation.value)
            assertFalse(f.terminal.currentShellSuggestionState().visible)
            assertTrue(f.press(KeyEvent.VK_ESCAPE).isConsumed)
            f.dispatcher.scheduler.runCurrent()
            assertFalse(popup.isActive)
            assertNull(f.terminal.inlineShellSuggestionPresentation.value)
            assertFalse(f.terminal.currentShellSuggestionState().visible)
            assertEquals(SwingShellSuggestionFeedbackKind.DISMISSED, f.feedback.single().kind)
            assertTrue(f.connector.writtenBytes.isEmpty())
        }

    @Test
    fun `closing the shared request cancels its source and leaves its valid inline result`() =
        withFixture { f ->
            f.popup()
            f.enable()
            f.advance()
            assertEquals(1, f.collected)
            f.terminal.hideShellSuggestions()
            f.dispatcher.scheduler.runCurrent()
            assertEquals(1, f.finished)
            assertNotNull(f.terminal.inlineShellSuggestionPresentation.value)
            assertEquals(SwingShellSuggestionAcceptanceResult.ACCEPTED, f.terminal.acceptInlineShellSuggestion())
        }

    @Test
    fun `late inline keeps the full popup when browsing has already started`() =
        withFixture { f ->
            val popup = f.popup()
            f.dispatcher.scheduler.runCurrent()
            assertTrue(f.press(KeyEvent.VK_DOWN).isConsumed)
            f.enable()
            f.advance()
            assertEquals(1, f.collected)
            assertEquals(f.ranked.value, popup.snapshot.suggestions)
            assertSame(f.ranked.value.first(), popup.snapshot.selectedSuggestion)
            assertNull(f.terminal.inlineShellSuggestionPresentation.value)
            assertTrue(f.press(KeyEvent.VK_TAB).isConsumed)
            f.dispatcher.scheduler.runCurrent()
            assertEquals("\u007fstatus", f.connector.writtenBytes.toString(Charsets.UTF_8))
        }

    @Test
    fun `progressive results update both surfaces from the same ranking`() =
        withFixture { f ->
            val popup = f.popup()
            f.enable()
            f.advance()
            val reordered = f.ranked.value.reversed()
            f.ranked.value = reordered
            f.dispatcher.scheduler.runCurrent()
            assertEquals(1, f.collected)
            assertSame(
                reordered.first(),
                f.terminal.inlineShellSuggestionPresentation.value
                    ?.selectedSuggestion,
            )
            assertEquals(reordered.drop(1), popup.snapshot.suggestions)
        }

    @Test
    fun `inline uses the first ranked completion without popup or pane history and preserves feedback`() =
        withFixture { f ->
            val top = f.ranked.value.first()
            f.enable()
            f.dispatcher.scheduler.runCurrent()
            f.dispatcher.scheduler.advanceTimeBy(74)
            f.dispatcher.scheduler.runCurrent()
            assertEquals(SwingShellSuggestionAcceptanceResult.UNAVAILABLE, f.terminal.acceptInlineShellSuggestion())
            f.advance()
            assertFalse(f.terminal.currentShellSuggestionState().visible)
            assertEquals(SwingShellSuggestionAcceptanceResult.ACCEPTED, f.terminal.acceptInlineShellSuggestion())
            f.dispatcher.scheduler.runCurrent()
            assertEquals("\u007fstatus", f.connector.writtenBytes.toString(Charsets.UTF_8))
            val feedback = f.feedback.single { it.kind == SwingShellSuggestionFeedbackKind.ACCEPTED }
            assertSame(top, feedback.suggestion)
            assertEquals(0, feedback.index)
            assertEquals(f.request, feedback.request)
        }

    @Test
    fun `refreshing the same command routes inline acceptance to the latest source`() =
        withFixture { f ->
            f.enable()
            f.advance()
            assertEquals(1, f.opened.size)
            f.popup()
            f.dispatcher.scheduler.runCurrent()
            assertEquals(2, f.opened.size)

            assertEquals(SwingShellSuggestionAcceptanceResult.ACCEPTED, f.terminal.acceptInlineShellSuggestion())
            assertEquals(listOf(1), f.feedbackSources)
            assertEquals(SwingShellSuggestionFeedbackKind.ACCEPTED, f.feedback.single().kind)
        }

    @Test
    fun `progressive ranking updates replace inline and empty results clear it`() =
        withFixture { f ->
            f.enable()
            f.advance()

            f.advance()
            assertEquals(
                listOf(f.ranked.value.first()),
                f.terminal.inlineShellSuggestionPresentation.value
                    ?.suggestions,
            )
            val next = SwingShellSuggestion("stash", 4, 5, "learned", "COMMAND")
            f.ranked.value = listOf(next, f.ranked.value.first())
            f.advance()
            assertEquals(
                listOf(next),
                f.terminal.inlineShellSuggestionPresentation.value
                    ?.suggestions,
            )
            f.ranked.value = emptyList()
            f.advance()
            assertEquals(SwingShellSuggestionAcceptanceResult.UNAVAILABLE, f.terminal.acceptInlineShellSuggestion())
            assertTrue(f.connector.writtenBytes.isEmpty())
        }

    @ParameterizedTest
    @ValueSource(strings = ["rewrite", "exact", "invalid", "control"])
    fun `ineligible top result never falls back to a different ranked item`(kind: String) =
        withFixture { f ->
            val first =
                when (kind) {
                    "rewrite" -> SwingShellSuggestion("show", 0, 5, "shell", "COMMAND")
                    "exact" -> SwingShellSuggestion("s", 4, 5, "shell", "COMMAND")
                    "invalid" -> SwingShellSuggestion("status", 4, 50, "shell", "COMMAND")
                    else -> SwingShellSuggestion("status\nwhoami", 4, 5, "shell", "COMMAND")
                }
            f.ranked.value = listOf(first, f.ranked.value.first())
            f.enable()
            f.advance()
            assertEquals(SwingShellSuggestionAcceptanceResult.UNAVAILABLE, f.terminal.acceptInlineShellSuggestion())
            val popup = f.popup()
            f.dispatcher.scheduler.runCurrent()
            assertEquals(f.ranked.value, popup.snapshot.suggestions)
            assertEquals(2, f.collected)
            assertTrue(f.connector.writtenBytes.isEmpty())
        }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `default off and disabling or removing the provider cancels owned work`(alreadyVisible: Boolean) =
        withFixture { f ->
            f.advance()
            assertEquals(SwingShellSuggestionAcceptanceResult.UNAVAILABLE, f.terminal.acceptInlineShellSuggestion())
            f.enable()
            if (alreadyVisible) f.advance()
            f.enabled = false
            f.terminal.reloadSettings()
            f.advance()
            assertEquals(SwingShellSuggestionAcceptanceResult.UNAVAILABLE, f.terminal.acceptInlineShellSuggestion())
            f.enable()
            f.terminal.setSharedShellSuggestionProvider(null)
            f.advance()
            assertEquals(SwingShellSuggestionAcceptanceResult.UNAVAILABLE, f.terminal.acceptInlineShellSuggestion())
            assertFalse(f.session.isClosed)
        }

    @Test
    fun `Escape dismissal survives unchanged output and focus until the command changes`() =
        withFixture { f ->
            f.enable()
            f.advance()
            val escape = f.press(KeyEvent.VK_ESCAPE)
            assertTrue(escape.isConsumed)
            f.connector.feedFromHost("\u001b[0m".toByteArray())
            f.focus()
            f.advance()
            assertEquals(SwingShellSuggestionAcceptanceResult.UNAVAILABLE, f.terminal.acceptInlineShellSuggestion())
            f.setLine("git st")
            f.dispatcher.scheduler.runCurrent()
            f.setLine("git s")
            f.advance()
            assertEquals(SwingShellSuggestionAcceptanceResult.ACCEPTED, f.terminal.acceptInlineShellSuggestion())
        }

    @ParameterizedTest
    @ValueSource(strings = ["blank", "middle", "shell", "focus", "selection", "running", "master"])
    fun `ineligible shell and view states do not start suggestions`(state: String) =
        withFixture { f ->
            f.dispatcher.scheduler.runCurrent()
            when (state) {
                "blank" -> f.setLine("")
                "middle" -> f.line.value = assertNotNull(f.line.value).copy(cursorOffset = 2, cursorColumn = 2)
                "shell" -> f.line.value = assertNotNull(f.line.value).copy(requiresShellConfirmation = true)
                "focus" -> f.terminal.focusListeners.forEach { it.focusLost(FocusEvent(f.terminal, FocusEvent.FOCUS_LOST)) }
                "selection" -> assertTrue(f.terminal.selectAll())
                "running" -> f.history.recordCommandStart(1000, true, "git sleep", "file:///project")
                "master" -> {
                    f.smart = false
                    f.terminal.reloadSettings()
                }
            }
            f.enable()
            f.advance()
            assertEquals(SwingShellSuggestionAcceptanceResult.UNAVAILABLE, f.terminal.acceptInlineShellSuggestion())
            assertTrue(f.connector.writtenBytes.isEmpty())
        }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `late ranked completion cannot steal a deliberate popup choice`(choosePopup: Boolean) =
        withFixture { f ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.results =
                flow {
                    entered.complete(Unit)
                    release.await()
                    emit(f.ranked.value)
                }
            f.enable()
            f.advance()
            assertTrue(entered.isCompleted)
            val popup = assertNotNull(f.terminal.beginActiveShellSuggestionInteraction())
            popup.publish(f.ranked.value, selectedIndex = 0)
            f.terminal.presentShellSuggestions(popup)
            if (choosePopup) popup.select(popup.snapshot, 1)
            release.complete(Unit)
            f.advance()
            f.press(KeyEvent.VK_TAB)
            f.dispatcher.scheduler.runCurrent()
            assertEquals(if (choosePopup) "\u007fstash" else "\t", f.connector.writtenBytes.toString(Charsets.UTF_8))
        }

    @Test
    fun `accepting a directory then Enter executes that directory without its child completion`() =
        withFixture { f ->
            f.setLine("cd x")
            val parent = SwingShellSuggestion("xyz/", 3, 4, "path", "PATH")
            f.ranked.value = listOf(parent)
            f.enable()
            f.advance()
            assertTrue(f.press(KeyEvent.VK_RIGHT).isConsumed)
            f.dispatcher.scheduler.runCurrent()
            assertEquals("\u007fxyz/", f.connector.writtenBytes.toString(Charsets.UTF_8))
            f.setLine("cd xyz/")
            val child = SwingShellSuggestion("xyz/abc/", 3, 7, "path", "PATH")
            f.ranked.value = listOf(child)
            f.advance()
            val popup = assertNotNull(f.terminal.beginActiveShellSuggestionInteraction())
            popup.publish(listOf(child), selectedIndex = 0)
            f.terminal.presentShellSuggestions(popup)
            f.dispatcher.scheduler.runCurrent()
            f.press(KeyEvent.VK_ENTER)
            f.dispatcher.scheduler.runCurrent()
            assertEquals("\u007fxyz/\r", f.connector.writtenBytes.toString(Charsets.UTF_8))
        }

    @Test
    fun `typing cancels a pending completion before it can publish stale inline text`() =
        withFixture { f ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val completed = CompletableDeferred<Unit>()
            f.results =
                flow {
                    entered.complete(Unit)
                    try {
                        release.await()
                        emit(f.ranked.value)
                    } finally {
                        completed.complete(Unit)
                    }
                }
            f.enable()
            f.advance()
            assertTrue(entered.isCompleted)
            f.results = flowOf(emptyList())
            f.setLine("echo done")
            f.dispatcher.scheduler.runCurrent()
            assertTrue(completed.isCompleted)
            release.complete(Unit)
            f.advance()
            assertEquals(SwingShellSuggestionAcceptanceResult.UNAVAILABLE, f.terminal.acceptInlineShellSuggestion())
            assertTrue(f.connector.writtenBytes.isEmpty())
        }

    private fun withFixture(test: (Fixture) -> Unit) {
        val task = FutureTask { Fixture().use(test) }
        SwingUtilities.invokeAndWait(task)
        task.get()
    }

    private class Fixture : AutoCloseable {
        val dispatcher = StandardTestDispatcher()
        val connector = MockConnector()
        val history = TerminalShellIntegrationState()
        val request = SwingShellSuggestionRequest("git s", 5)
        val line = TerminalShellCommandLineState(TerminalShellCommandLineSnapshot("git s", 5, 5, 0))
        val session =
            TerminalSession.create(
                terminal = TerminalBuffers.create(30, 4),
                connector = connector,
                shellIntegration = TerminalShellIntegrationFactory.host(history, line),
                workerDispatcher = dispatcher,
                ioDispatcher = dispatcher,
            )
        val ranked =
            MutableStateFlow(
                listOf(
                    SwingShellSuggestion("status", 4, 5, "spec", "COMMAND"),
                    SwingShellSuggestion("stash", 4, 5, "learned", "COMMAND"),
                ),
            )
        var results: Flow<List<SwingShellSuggestion>> = ranked
        val feedback = mutableListOf<SwingShellSuggestionFeedback>()
        val feedbackSources = mutableListOf<Int>()
        val provider =
            object : SwingShellSuggestionProvider {
                override fun suggestions(request: SwingShellSuggestionRequest) = results

                override fun open(request: SwingShellSuggestionRequest): SwingShellSuggestionSource {
                    opened += request
                    val sourceIndex = opened.lastIndex
                    val captured = results
                    return SwingShellSuggestionSource(
                        flow {
                            collected++
                            try {
                                emitAll(captured)
                            } finally {
                                finished++
                            }
                        },
                        SwingShellSuggestionFeedbackHandler {
                            feedback += it
                            feedbackSources += sourceIndex
                        },
                    )
                }
            }
        val opened = mutableListOf<SwingShellSuggestionRequest>()
        var collected = 0
        var finished = 0
        var enabled = false
        var smart = true
        var popupAutomatic = false
        var popupDelay = 75
        var inlineDelay = 75
        val popupPublications = mutableListOf<List<SwingShellSuggestion>>()
        val terminal =
            SuggestionTestTerminal.create(
                {
                    SwingSettings.create {
                        it.smartSuggestionsEnabled = smart
                        it.shellSuggestionsEnabled = popupAutomatic
                        it.shellSuggestionDebounceMillis = popupDelay
                        it.inlineShellSuggestionsEnabled = enabled
                        it.inlineShellSuggestionDebounceMillis = inlineDelay
                    }
                },
                SwingHostServices.create {
                    it.uiDispatcher = TerminalUiDispatcher { task -> task.run() }
                    it.shellSuggestionKeymap = SwingShellSuggestionKeymap.STANDARD
                    it.shellSuggestionViewFactory =
                        SwingShellSuggestionViewFactory {
                            object : SwingShellSuggestionView {
                                override val component = JPanel()

                                override fun update(snapshot: SwingShellSuggestionViewSnapshot) {
                                    if (snapshot.visibleSuggestions.isNotEmpty()) popupPublications += snapshot.visibleSuggestions.toList()
                                }
                            }
                        }
                },
                dispatcher,
            )

        val completions = provider

        init {
            session.start(30, 4)
            terminal.size = terminal.preferredGridSize(30, 4)
            terminal.bind(session)
            connector.feedFromHost("git s".toByteArray())
            focus()
            dispatcher.scheduler.runCurrent()
            history.recordCurrentWorkingDirectory("file:///project")
            terminal.setSharedShellSuggestionProvider(completions)
        }

        fun focus() = terminal.focusListeners.forEach { it.focusGained(FocusEvent(terminal, FocusEvent.FOCUS_GAINED)) }

        fun setLine(text: String) {
            connector.feedFromHost(("\r\u001b[2K" + text).toByteArray())
            line.value = TerminalShellCommandLineSnapshot(text, text.length, text.length, 0)
        }

        fun enable() {
            enabled = true
            terminal.reloadSettings()
        }

        fun popup(): SwingShellSuggestionInteraction {
            val interaction = assertNotNull(terminal.beginActiveShellSuggestionInteraction())
            terminal.presentShellSuggestions(interaction)
            terminal.requestShellSuggestions(interaction, completions)
            return interaction
        }

        fun advance() {
            dispatcher.scheduler.advanceTimeBy(75)
            dispatcher.scheduler.runCurrent()
        }

        fun press(code: Int): KeyEvent {
            val event = KeyEvent(terminal, KeyEvent.KEY_PRESSED, 0L, 0, code, KeyEvent.CHAR_UNDEFINED)
            terminal.keyListeners.forEach { it.keyPressed(event) }
            return event
        }

        override fun close() {
            terminal.dispose()

            session.close()
            dispatcher.scheduler.runCurrent()
        }
    }
}

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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.awt.event.FocusEvent
import java.awt.event.KeyEvent
import java.util.concurrent.FutureTask
import javax.swing.SwingUtilities
import kotlin.test.*

@OptIn(ExperimentalShellSuggestionsApi::class, ExperimentalCoroutinesApi::class)
class InlineCompletionSuggestionsTest {
    @Test
    fun `inline uses the first ranked completion without popup or pane history and preserves feedback`() =
        withFixture { f ->
            val top = f.ranked.value.first()
            val source = f.inlineProvider.open(SwingInlineShellSuggestionRequest(f.request, null))
            assertSame(f.feedbackHandler, source.feedbackHandler)
            f.enable()
            f.dispatcher.scheduler.runCurrent()
            f.dispatcher.scheduler.advanceTimeBy(74)
            f.dispatcher.scheduler.runCurrent()
            assertEquals(SwingShellSuggestionAcceptanceResult.UNAVAILABLE, f.terminal.acceptInlineShellSuggestion())
            f.advance()
            assertFalse(f.terminal.hasShellSuggestionProvider)
            assertEquals(SwingShellSuggestionAcceptanceResult.ACCEPTED, f.terminal.acceptInlineShellSuggestion())
            f.dispatcher.scheduler.runCurrent()
            assertEquals("\u007fstatus", f.connector.writtenBytes.toString(Charsets.UTF_8))
            val feedback = f.feedback.single { it.kind == SwingShellSuggestionFeedbackKind.ACCEPTED }
            assertSame(top, feedback.suggestion)
            assertEquals(0, feedback.index)
            assertEquals(f.request, feedback.request)
        }

    @Test
    fun `progressive ranking updates replace inline and empty results clear it`() =
        withFixture { f ->
            f.enable()
            f.advance()
            val interaction = assertNotNull(f.terminal.requestActiveInlineShellSuggestions())
            f.advance()
            assertEquals(listOf(f.ranked.value.first()), interaction.snapshot.suggestions)
            val next = SwingShellSuggestion("stash", 4, 5, "learned", "COMMAND")
            f.ranked.value = listOf(next, f.ranked.value.first())
            f.advance()
            assertEquals(listOf(next), interaction.snapshot.suggestions)
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
            f.terminal.setInlineShellSuggestionProvider(null)
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
            assertTrue(f.press(KeyEvent.VK_TAB).isConsumed)
            f.dispatcher.scheduler.runCurrent()
            assertEquals("\u007f" + if (choosePopup) "stash" else "status", f.connector.writtenBytes.toString(Charsets.UTF_8))
        }

    @Test
    fun `accepting a directory then Enter executes that directory without its child completion`() =
        withFixture { f ->
            f.setLine("cd x")
            val parent = SwingShellSuggestion("xyz/", 3, 4, "path", "PATH")
            f.ranked.value = listOf(parent)
            f.enable()
            f.advance()
            assertTrue(f.press(KeyEvent.VK_TAB).isConsumed)
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
        val feedbackHandler = SwingShellSuggestionFeedbackHandler { feedback += it }
        val provider =
            object : SwingShellSuggestionProvider {
                override fun suggestions(request: SwingShellSuggestionRequest) = results

                override fun open(request: SwingShellSuggestionRequest) = SwingShellSuggestionSource(results, feedbackHandler)
            }
        val inlineProvider = InlineCompletionSuggestions(provider)
        var enabled = false
        var smart = true
        val terminal =
            SuggestionTestTerminal.create(
                {
                    SwingSettings.create {
                        it.smartSuggestionsEnabled = smart
                        it.shellSuggestionsEnabled = false
                        it.inlineShellSuggestionsEnabled = enabled
                    }
                },
                SwingHostServices.create {
                    it.uiDispatcher = TerminalUiDispatcher { task -> task.run() }
                    it.shellSuggestionKeymap = SwingShellSuggestionKeymap.STANDARD
                },
                dispatcher,
            )

        init {
            session.start(30, 4)
            terminal.size = terminal.preferredGridSize(30, 4)
            terminal.bind(session)
            connector.feedFromHost("git s".toByteArray())
            focus()
            dispatcher.scheduler.runCurrent()
            history.recordCurrentWorkingDirectory("file:///project")
            terminal.setInlineShellSuggestionProvider(inlineProvider)
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

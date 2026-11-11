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
import io.github.ketraterm.ui.swing.suggestion.ExperimentalShellSuggestionsApi
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestion
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionAcceptanceResult
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionKeymap
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.awt.event.FocusEvent
import java.awt.event.KeyEvent
import java.util.concurrent.FutureTask
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalShellSuggestionsApi::class, ExperimentalCoroutinesApi::class)
class InlineHistorySuggestionsTest {
    @Test
    fun `latest matching completed command in this directory is accepted through the session`() =
        withFixture { f ->
            f.remember("git status --short")
            f.remember("git status")
            f.remember("git stash", cwd = "file:///elsewhere")
            f.remember("git show\nunsafe")
            f.enable()
            f.dispatcher.scheduler.runCurrent()
            f.dispatcher.scheduler.advanceTimeBy(74)
            f.dispatcher.scheduler.runCurrent()
            assertEquals(SwingShellSuggestionAcceptanceResult.UNAVAILABLE, f.terminal.acceptInlineShellSuggestion())
            f.advance()
            assertEquals(SwingShellSuggestionAcceptanceResult.ACCEPTED, f.terminal.acceptInlineShellSuggestion())
            f.dispatcher.scheduler.runCurrent()
            assertEquals("\u007f".repeat(5) + "git status", f.connector.writtenBytes.toString(Charsets.UTF_8))
        }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `default off and disabling or closing cancels owned work`(alreadyVisible: Boolean) =
        withFixture { f ->
            f.remember("git status")
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
            f.remember("git status")
            f.enable()
            f.advance()
            val escape = KeyEvent(f.terminal, KeyEvent.KEY_PRESSED, 0L, 0, KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED)
            f.terminal.keyListeners.forEach { it.keyPressed(escape) }
            assertTrue(escape.isConsumed)
            f.connector.feedFromHost("\u001b[0m".toByteArray())
            f.focus()
            f.advance()
            assertEquals(SwingShellSuggestionAcceptanceResult.UNAVAILABLE, f.terminal.acceptInlineShellSuggestion())
            f.setLine("git st")
            f.dispatcher.scheduler.runCurrent()
            assertEquals("git st", f.session.activeShellCommandLine()?.commandText)
            f.setLine("git s")
            f.advance()
            assertEquals(SwingShellSuggestionAcceptanceResult.ACCEPTED, f.terminal.acceptInlineShellSuggestion())
        }

    @ParameterizedTest
    @ValueSource(strings = ["blank", "middle", "shell", "focus", "selection", "running", "master"])
    fun `ineligible shell and view states do not start suggestions`(state: String) =
        withFixture { f ->
            f.remember("git status")
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
    fun `history stays independent until the user chooses the popup`(choosePopup: Boolean) =
        withFixture { f ->
            f.remember("git status --short")
            f.remember("git stash list")
            f.enable()
            f.advance()
            val popup = assertNotNull(f.terminal.beginActiveShellSuggestionInteraction())
            popup.publish(listOf(SwingShellSuggestion("git status", 0, 5, "test", "COMMAND")), selectedIndex = 0)
            f.terminal.presentShellSuggestions(popup)
            f.dispatcher.scheduler.runCurrent()
            if (choosePopup) popup.select(popup.snapshot, 0)
            val tab = KeyEvent(f.terminal, KeyEvent.KEY_PRESSED, 0L, 0, KeyEvent.VK_TAB, KeyEvent.CHAR_UNDEFINED)
            f.terminal.keyListeners.forEach { it.keyPressed(tab) }
            assertTrue(tab.isConsumed)
            f.dispatcher.scheduler.runCurrent()
            assertEquals(
                "\u007f".repeat(5) + if (choosePopup) "git status" else "git stash list",
                f.connector.writtenBytes.toString(Charsets.UTF_8),
            )
        }

    @ParameterizedTest
    @ValueSource(strings = ["empty", "exact", "directory", "bound"])
    fun `history matching has no fallback outside its bounds`(state: String) =
        withFixture { f ->
            when (state) {
                "exact" -> f.remember("git s")
                "directory" -> f.remember("git status", "file:///elsewhere")
                "bound" -> {
                    f.remember("git status")
                    repeat(200) { f.remember("echo $it") }
                }
            }
            f.enable()
            f.advance()
            assertEquals(SwingShellSuggestionAcceptanceResult.UNAVAILABLE, f.terminal.acceptInlineShellSuggestion())
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
        val line = TerminalShellCommandLineState(TerminalShellCommandLineSnapshot("git s", 5, 5, 0))
        val session =
            TerminalSession.create(
                terminal = TerminalBuffers.create(30, 4),
                connector = connector,
                shellIntegration = TerminalShellIntegrationFactory.host(history, line),
                workerDispatcher = dispatcher,
                ioDispatcher = dispatcher,
            )
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
        private var recordLine = 100L

        init {
            session.start(30, 4)
            terminal.size = terminal.preferredGridSize(30, 4)
            terminal.bind(session)
            connector.feedFromHost("git s".toByteArray())
            focus()
            dispatcher.scheduler.runCurrent()
            history.recordCurrentWorkingDirectory("file:///project")
            terminal.setInlineShellSuggestionProvider(InlineHistorySuggestions(session))
        }

        fun focus() = terminal.focusListeners.forEach { it.focusGained(FocusEvent(terminal, FocusEvent.FOCUS_GAINED)) }

        fun remember(
            text: String,
            cwd: String = "file:///project",
        ) {
            history.recordPromptStart(recordLine++)
            history.recordCommandStart(recordLine++, true, text, cwd)
            history.recordCommandFinished(recordLine++, 0)
        }

        fun setLine(text: String) {
            connector.feedFromHost(("\r\u001b[2K" + text).toByteArray())
            line.value = TerminalShellCommandLineSnapshot(text, text.length, text.length, 0)
        }

        fun enable() {
            enabled = true
            terminal.reloadSettings()
        }

        fun advance() {
            dispatcher.scheduler.advanceUntilIdle()
        }

        override fun close() {
            terminal.dispose()
            session.close()
            dispatcher.scheduler.runCurrent()
        }
    }
}

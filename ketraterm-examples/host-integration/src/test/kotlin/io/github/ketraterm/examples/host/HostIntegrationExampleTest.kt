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
@file:OptIn(ExperimentalShellSuggestionsApi::class)

package io.github.ketraterm.examples.host

import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.pty.PtyOptions
import io.github.ketraterm.session.*
import io.github.ketraterm.transport.TerminalConnector
import io.github.ketraterm.transport.TerminalConnectorListener
import io.github.ketraterm.ui.swing.host.SwingTerminalHostAction
import io.github.ketraterm.ui.swing.settings.TerminalClipboardHandler
import io.github.ketraterm.ui.swing.suggestion.ExperimentalShellSuggestionsApi
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestion
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionAcceptanceResult
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionCloseReason
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.awt.Component
import java.awt.Container
import java.awt.Toolkit
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.io.ByteArrayOutputStream
import javax.swing.JButton
import javax.swing.JList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class HostIntegrationExampleTest {
    @Test
    fun `host model supplies bounded output clipboard search and live settings without OSC`() =
        runBlocking {
            Fixture().useSuspending { fixture ->
                fixture.completedCommand("alpha e\u0301 👩‍💻\r\nbeta")
                assertEquals(
                    TerminalCommandOutputText("alpha e\u0301 👩‍💻\nbeta", truncated = false, historyIncomplete = false),
                    fixture.pane.lastOutput(),
                )
                withContext(Dispatchers.Swing) {
                    descendants(fixture.pane.component).filterIsInstance<JButton>().single { it.text == "Copy last output" }.doClick(0)
                }
                awaitCondition { fixture.clipboard.text == "alpha e\u0301 👩‍💻\nbeta" }
                withContext(Dispatchers.Swing) {
                    assertTrue(fixture.pane.performAction(SwingTerminalHostAction.OPEN_SEARCH))
                    fixture.pane.terminal.search("beta")
                }
                awaitCondition {
                    fixture.pane.terminal.searchState.value
                        .let { !it.isSearching && it.resultCount == 1 }
                }
                val before = withContext(Dispatchers.Swing) { fixture.pane.terminal.visibleGridSize() }
                val changed = CompletableDeferred<Unit>()
                withContext(Dispatchers.Swing) {
                    fixture.pane.updateSettings(lightTheme = true, fontSize = 24) { failure ->
                        if (failure == null) changed.complete(Unit) else changed.completeExceptionally(failure)
                    }
                }
                withTimeout(5.seconds) { changed.await() }
                val after = withContext(Dispatchers.Swing) { fixture.pane.terminal.visibleGridSize() }
                assertTrue(after.width < before.width)
                assertEquals("alpha e\u0301 👩‍💻\nbeta", fixture.pane.lastOutput()?.text)
                fixture.clipboard.text = "host paste"
                withContext(Dispatchers.Swing) { assertTrue(fixture.pane.performAction(SwingTerminalHostAction.PASTE_CLIPBOARD)) }
                awaitCondition { fixture.connector.text() == "host paste" }
                withContext(Dispatchers.Swing) { fixture.pane.close() }
                assertFalse(fixture.session.isClosed)
                fixture.state.recordCurrentWorkingDirectory("file:///still-host-owned")
                assertEquals("file:///still-host-owned", fixture.session.currentWorkingDirectoryUri())
            }
        }

    @Test
    fun `host list preserves guarded edits and rejects a stale command revision`() =
        runBlocking {
            Fixture().useSuspending { fixture ->
                fixture.prompt("ca")
                val candidate = SwingShellSuggestion("cat", 0, 2, "host-test", "COMMAND")
                val accepted =
                    withContext(Dispatchers.Swing) {
                        fixture.pane.terminal.setShellSuggestionProvider(null)
                        val interaction = assertNotNull(fixture.pane.terminal.beginActiveShellSuggestionInteraction())
                        assertTrue(interaction.publish(listOf(candidate), 0))
                        fixture.pane.terminal.presentShellSuggestions(interaction)
                        val list = descendants(fixture.pane.terminal).filterIsInstance<JList<*>>().single()
                        assertEquals("cat", (list.model.getElementAt(0) as SwingShellSuggestion).displayText)
                        interaction.tryAccept(interaction.snapshot, 0)
                    }
                assertEquals(SwingShellSuggestionAcceptanceResult.ACCEPTED, accepted)
                awaitCondition { fixture.connector.text().isNotEmpty() }
                awaitCondition { fixture.session.captureCommandEdit() != null }
                val stale =
                    withContext(Dispatchers.Swing) {
                        fixture.prompt("ca")
                        val interaction = assertNotNull(fixture.pane.terminal.beginActiveShellSuggestionInteraction())
                        assertTrue(interaction.publish(listOf(candidate), 0))
                        interaction to interaction.snapshot
                    }
                val written = fixture.connector.text()
                fixture.commandLine.value = TerminalShellCommandLineSnapshot("cancel", 6, 8, 0)
                withContext(Dispatchers.Swing) {
                    assertEquals(SwingShellSuggestionAcceptanceResult.STALE_CONTEXT, stale.first.tryAccept(stale.second, 0))
                }
                assertEquals(written, fixture.connector.text())
            }
        }

    @Test
    fun `disposing the pane cancels a pending host clipboard read without closing the session`() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val clipboard =
                object : MemoryClipboard() {
                    override suspend fun readTextAsync(): String {
                        started.complete(Unit)
                        release.await()
                        return "late paste"
                    }
                }
            Fixture(clipboard).useSuspending { fixture ->
                withContext(Dispatchers.Swing) { assertTrue(fixture.pane.performAction(SwingTerminalHostAction.PASTE_CLIPBOARD)) }
                withTimeout(5.seconds) { started.await() }
                withContext(Dispatchers.Swing) { fixture.pane.close() }
                release.complete(Unit)
                withContext(Dispatchers.Swing) { assertFalse(fixture.pane.performAction(SwingTerminalHostAction.PASTE_CLIPBOARD)) }
                assertEquals("", fixture.connector.text())
                assertFalse(fixture.session.isClosed)
            }
        }

    @Test
    fun `closing before binding prevents connector startup even when binding also completes`() =
        runBlocking {
            for (bindingAlsoReady in listOf(false, true)) {
                val connector = DeterministicConnector()
                val session = TerminalSession.create(TerminalBuffers.create(80, 24), connector)
                try {
                    val bound = CompletableDeferred<Unit>()
                    val closed = CompletableDeferred<Unit>()
                    val startup = async { startBoundSession(session, bound, closed) }
                    closed.complete(Unit)
                    if (bindingAlsoReady) bound.complete(Unit)
                    assertFalse(withTimeout(5.seconds) { startup.await() })
                    assertFalse(connector.started)
                    assertEquals(TerminalSessionState.Created, session.state.value)
                } finally {
                    withContext(Dispatchers.IO) { session.close() }
                }
            }
        }

    @Test
    @Tag("desktop")
    fun `native list press and drag cannot change keyboard acceptance outside the controller`() =
        runBlocking {
            Fixture().useSuspending { fixture ->
                fixture.prompt("ca")
                withContext(Dispatchers.Swing) {
                    val terminal = fixture.pane.terminal
                    terminal.setShellSuggestionProvider(null)
                    val interaction = assertNotNull(terminal.beginActiveShellSuggestionInteraction())
                    assertTrue(
                        interaction.publish(
                            listOf(
                                SwingShellSuggestion("cat", 0, 2, "host-test", "COMMAND"),
                                SwingShellSuggestion("cargo", 0, 2, "host-test", "COMMAND"),
                            ),
                            0,
                        ),
                    )
                    terminal.presentShellSuggestions(interaction)
                    val selectFirst = KeyEvent(terminal, KeyEvent.KEY_PRESSED, 0L, 0, KeyEvent.VK_HOME, KeyEvent.CHAR_UNDEFINED)
                    terminal.keyListeners.forEach { it.keyPressed(selectFirst) }
                    assertTrue(selectFirst.isConsumed)
                    assertTrue(interaction.hasExplicitSelection)
                    val list = descendants(terminal).filterIsInstance<JList<*>>().single()
                    list.fixedCellHeight = 24
                    list.setSize(360, 80)

                    fun mouse(
                        id: Int,
                        row: Int,
                        modifiers: Int = 0,
                    ) {
                        val y = if (row < 0) -20 else row * 24 + 12
                        val button = if (id == MouseEvent.MOUSE_DRAGGED) MouseEvent.NOBUTTON else MouseEvent.BUTTON1
                        list.dispatchEvent(MouseEvent(list, id, 0L, modifiers, 8, y, 8, y, 1, false, button))
                    }
                    mouse(MouseEvent.MOUSE_PRESSED, 1, InputEvent.BUTTON1_DOWN_MASK)
                    mouse(MouseEvent.MOUSE_DRAGGED, -1, InputEvent.BUTTON1_DOWN_MASK)
                    mouse(MouseEvent.MOUSE_RELEASED, -1)
                    assertEquals(0, list.selectedIndex)
                    assertEquals(0, interaction.snapshot.selectedIndex)
                    mouse(MouseEvent.MOUSE_PRESSED, 0, Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx or InputEvent.BUTTON1_DOWN_MASK)
                    mouse(MouseEvent.MOUSE_DRAGGED, -1, InputEvent.BUTTON1_DOWN_MASK)
                    mouse(MouseEvent.MOUSE_RELEASED, -1)
                    assertEquals(0, list.selectedIndex)
                    val accept = KeyEvent(terminal, KeyEvent.KEY_PRESSED, 0L, 0, KeyEvent.VK_TAB, KeyEvent.CHAR_UNDEFINED)
                    terminal.keyListeners.forEach { it.keyPressed(accept) }
                    assertTrue(accept.isConsumed)
                    assertEquals(SwingShellSuggestionCloseReason.ACCEPTED, interaction.closeReason)
                    assertEquals("cat", interaction.snapshot.selectedSuggestion?.replacementText)
                }
                awaitCondition { fixture.connector.text().isNotEmpty() }
                assertFalse(fixture.connector.text().contains("cargo"))
            }
        }

    private class Fixture(
        val clipboard: MemoryClipboard = MemoryClipboard(),
    ) {
        val connector = DeterministicConnector()
        val state = TerminalShellIntegrationState()
        val commandLine = TerminalShellCommandLineState()
        lateinit var context: TerminalShellIntegrationContext
        lateinit var pane: HostTerminalPane
        private val host = TerminalShellIntegrationFactory.host(state, commandLine)
        val session =
            TerminalSession.create(
                TerminalBuffers.create(100, 28, maxHistory = 1_000),
                connector,
                inputPolicy = PtyOptions.defaultInputPolicy(),
                shellIntegration =
                    TerminalShellIntegrationFactory {
                        context = it
                        host.create(it)
                    },
            )

        suspend fun useSuspending(action: suspend (Fixture) -> Unit) {
            try {
                val bound = CompletableDeferred<Unit>()
                withContext(Dispatchers.Swing) {
                    pane = HostTerminalPane(session, hostProfile(listOf("bash")), clipboard)
                    pane.terminal.size = pane.terminal.preferredSize
                    pane.terminal.bind(session) { failure ->
                        if (failure == null) bound.complete(Unit) else bound.completeExceptionally(failure)
                    }
                }
                withTimeout(5.seconds) { bound.await() }
                session.start()
                action(this)
            } finally {
                withContext(NonCancellable + Dispatchers.Swing) { if (::pane.isInitialized) pane.close() }
                withContext(NonCancellable + Dispatchers.IO) { session.close() }
                assertTrue(connector.closed)
            }
        }

        fun completedCommand(text: String) {
            marker(state::recordPromptStart)
            connector.feed("$ ")
            marker(state::recordPromptEnd)
            connector.feed("run\r\n")
            marker { state.recordCommandStart(it, includeLine = true, commandText = "run") }
            connector.feed(text + "\r\n")
            marker { state.recordCommandFinished(it, 0) }
            marker(state::recordPromptStart)
        }

        fun prompt(text: String) {
            commandLine.value = null
            connector.feed("\r\u001b[2K$ $text")
            context.withTerminalState {
                var column = 0
                var row = 0
                context.readRenderFrame {
                    column = it.cursor.column
                    row = it.cursor.row
                }
                commandLine.value = TerminalShellCommandLineSnapshot(text, text.length, column, row)
            }
        }

        private fun marker(action: (Long) -> Unit) {
            context.withTerminalState {
                var lineId = 0L
                context.readRenderFrame { lineId = it.lineId(it.cursor.row) }
                action(lineId)
            }
        }
    }

    private open class MemoryClipboard : TerminalClipboardHandler {
        @Volatile var text: String? = null

        override fun copyText(text: String) {
            this.text = text
        }

        override fun readText(): String? = text
    }

    private class DeterministicConnector : TerminalConnector {
        private lateinit var listener: TerminalConnectorListener
        private val written = ByteArrayOutputStream()

        @Volatile var closed = false

        @Volatile var started = false

        override fun start(listener: TerminalConnectorListener) {
            this.listener = listener
            started = true
        }

        override fun write(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) = synchronized(written) { written.write(bytes, offset, length) }

        override fun resize(
            columns: Int,
            rows: Int,
        ) = Unit

        override fun close() {
            closed = true
        }

        fun feed(text: String) {
            val bytes = text.encodeToByteArray()
            listener.onBytes(bytes, 0, bytes.size)
        }

        fun text(): String = synchronized(written) { written.toString(Charsets.UTF_8) }
    }
}

internal suspend fun awaitCondition(condition: suspend () -> Boolean) {
    withTimeout(10.seconds) { while (!condition()) delay(10.milliseconds) }
}

private fun descendants(component: Component): Sequence<Component> =
    sequence {
        yield(component)
        if (component is Container) for (child in component.components) yieldAll(descendants(child))
    }

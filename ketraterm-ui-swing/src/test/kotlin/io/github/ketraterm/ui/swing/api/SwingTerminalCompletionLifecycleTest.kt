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
package io.github.ketraterm.ui.swing.api

import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.session.*
import io.github.ketraterm.transport.TerminalConnector
import io.github.ketraterm.transport.TerminalConnectorListener
import io.github.ketraterm.ui.swing.settings.SwingSettings
import io.github.ketraterm.ui.swing.settings.SwingSettingsProvider
import io.github.ketraterm.ui.swing.suggestion.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import java.awt.event.FocusEvent
import java.io.ByteArrayOutputStream
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class SwingTerminalCompletionLifecycleTest {
    @Test
    fun `default command editing follows the bound session even when both commands are equal`() =
        onEdtTest {
            val first = SessionFixture(StandardTestDispatcher(testScheduler))
            val second = SessionFixture(StandardTestDispatcher(testScheduler))
            val terminal = terminal(testScheduler)
            try {
                terminal.bind(first.session)
                val old = assertNotNull(terminal.beginActiveShellSuggestionInteraction())
                old.publish(listOf(suggestion))
                val oldPublication = old.snapshot

                terminal.bind(second.session)
                assertEquals(SwingShellSuggestionAcceptanceResult.STALE_CONTEXT, old.tryAccept(oldPublication, 0))
                val current = assertNotNull(terminal.beginActiveShellSuggestionInteraction())
                current.publish(listOf(suggestion))
                assertEquals(SwingShellSuggestionAcceptanceResult.ACCEPTED, current.tryAccept(current.snapshot, 0))
                runCurrent()

                assertEquals("", first.connector.output.toString(Charsets.UTF_8))
                assertEquals("\u007f\u007f\u007f\u007f\u007freplacement", second.connector.output.toString(Charsets.UTF_8))
            } finally {
                terminal.dispose()
                first.close()
                second.close()
                runCurrent()
            }
        }

    @Test
    fun `provider replacement cancels its in-flight source and rejects old publications`() =
        onEdtTest {
            val terminal = terminal(testScheduler)
            val entered = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val oldProvider =
                SwingShellSuggestionProvider {
                    flow {
                        entered.complete(Unit)
                        try {
                            awaitCancellation()
                        } finally {
                            cancelled.complete(Unit)
                        }
                    }
                }
            val replacement = SwingShellSuggestionProvider { flowOf(emptyList()) }
            try {
                terminal.setShellSuggestionProvider(oldProvider)
                val interaction = assertNotNull(terminal.beginShellSuggestionInteraction(request))
                terminal.requestShellSuggestions(interaction, oldProvider)
                entered.await()
                terminal.setShellSuggestionProvider(replacement)
                cancelled.await()

                assertTrue(interaction.isClosed)
                assertFalse(interaction.publish(listOf(suggestion)))
                assertTrue(terminal.hasShellSuggestionProvider)
            } finally {
                terminal.dispose()
            }
        }

    @Test
    fun `acceptance retains the source feedback captured before reentrant provider replacement`() =
        onEdtTest {
            val terminal = terminal(testScheduler)
            val feedback = ArrayList<String>()
            val replacement =
                object : SwingShellSuggestionProvider {
                    override fun suggestions(request: SwingShellSuggestionRequest): Flow<List<SwingShellSuggestion>> = emptyFlow()

                    override fun open(request: SwingShellSuggestionRequest) =
                        SwingShellSuggestionSource(emptyFlow(), { feedback += "replacement" })
                }
            val original =
                object : SwingShellSuggestionProvider {
                    override fun suggestions(request: SwingShellSuggestionRequest): Flow<List<SwingShellSuggestion>> = emptyFlow()

                    override fun open(request: SwingShellSuggestionRequest) =
                        SwingShellSuggestionSource(
                            flow { awaitCancellation() },
                            {
                                feedback += "original"
                                terminal.setShellSuggestionProvider(replacement)
                            },
                        )
                }
            try {
                terminal.setShellSuggestionProvider(original)
                val interaction =
                    assertNotNull(
                        terminal.beginShellSuggestionInteraction(
                            request,
                            editTarget =
                                SwingShellSuggestionEditTarget {
                                    SwingShellSuggestionHandler { SwingShellSuggestionAcceptanceResult.ACCEPTED }
                                },
                        ),
                    )
                terminal.requestShellSuggestions(interaction, original)
                interaction.publish(listOf(suggestion))

                assertEquals(SwingShellSuggestionAcceptanceResult.ACCEPTED, interaction.tryAccept(interaction.snapshot, 0))
                assertEquals(listOf("original"), feedback)
                assertTrue(terminal.hasShellSuggestionProvider)
            } finally {
                terminal.dispose()
            }
        }

    @Test
    fun `popup hiding can disable or dispose during provider replacement without restarting observation`() =
        onEdtTest {
            for (dispose in listOf(false, true)) {
                val source = MutableStateFlow<TerminalShellCommandLineSnapshot?>(snapshot)
                val session = SessionFixture(StandardTestDispatcher(testScheduler), source)
                val view = RecordingView()
                val terminal = terminal(testScheduler, view = view)
                try {
                    terminal.bind(session.session)
                    terminal.setShellSuggestionProvider { emptyFlow() }
                    val interaction =
                        assertNotNull(terminal.beginShellSuggestionInteraction(request, editTarget = SwingShellSuggestionEditTarget.NONE))
                    interaction.publish(listOf(suggestion))
                    terminal.presentShellSuggestions(interaction)
                    runCurrent()
                    view.onHide = {
                        if (dispose) terminal.dispose() else terminal.setShellSuggestionProvider(null)
                    }

                    terminal.setShellSuggestionProvider { emptyFlow() }
                    runCurrent()

                    assertFalse(terminal.hasShellSuggestionProvider)
                    assertEquals(
                        0,
                        source.subscriptionCount.value,
                        "dispose=$dispose, interactionClosed=${interaction.isClosed}, view=${view.snapshot.visibleSuggestions}",
                    )
                    assertTrue(interaction.isClosed)
                    assertTrue(view.snapshot.visibleSuggestions.isEmpty())
                } finally {
                    terminal.dispose()
                    session.close()
                    runCurrent()
                }
            }
        }

    @Test
    fun `automatic observation requires configuration and follows settings bind and unbind`() =
        onEdtTest {
            val source = MutableStateFlow<TerminalShellCommandLineSnapshot?>(snapshot)
            val session = SessionFixture(StandardTestDispatcher(testScheduler), source)
            var settings = settings()
            val terminal = terminal(testScheduler, settingsProvider = { settings })
            try {
                terminal.bind(session.session)
                runCurrent()
                assertEquals(0, source.subscriptionCount.value)

                terminal.setShellSuggestionProvider { emptyFlow() }
                runCurrent()
                assertEquals(1, source.subscriptionCount.value)

                settings = settings.copy { it.shellSuggestionsEnabled = false }
                terminal.reloadSettings()
                runCurrent()
                assertEquals(0, source.subscriptionCount.value)

                settings = settings.copy { it.shellSuggestionsEnabled = true }
                terminal.reloadSettings()
                runCurrent()
                assertEquals(1, source.subscriptionCount.value)

                terminal.unbind()
                runCurrent()
                assertEquals(0, source.subscriptionCount.value)
                assertTrue(terminal.hasShellSuggestionProvider)

                terminal.bind(session.session)
                runCurrent()
                assertEquals(1, source.subscriptionCount.value)

                terminal.setShellSuggestionProvider(null)
                runCurrent()
                assertEquals(0, source.subscriptionCount.value)
            } finally {
                terminal.dispose()
                session.close()
                runCurrent()
            }
        }

    @Test
    fun `custom automatic target receives captured editing and preserves authority while hiding on acceptance`() =
        onEdtTest {
            val session = SessionFixture(StandardTestDispatcher(testScheduler))
            val terminal = terminal(testScheduler)
            val target = RecordingTarget()
            try {
                terminal.setShellSuggestionTarget(target)
                terminal.bind(session.session)
                focus(terminal, true)
                runCurrent()
                advanceTimeBy(75.milliseconds)
                runCurrent()
                val interaction = assertNotNull(target.interactions.singleOrNull())
                interaction.publish(listOf(suggestion))
                assertTrue(target.visible)

                assertEquals(SwingShellSuggestionAcceptanceResult.ACCEPTED, interaction.tryAccept(interaction.snapshot, 0))
                runCurrent()

                assertFalse(target.visible)
                assertEquals("\u007f\u007f\u007f\u007f\u007freplacement", session.connector.output.toString(Charsets.UTF_8))
            } finally {
                terminal.dispose()
                session.close()
                runCurrent()
            }
        }

    @Test
    fun `rebinding cancels a custom automatic request and starts from the new session`() =
        onEdtTest {
            val first = SessionFixture(StandardTestDispatcher(testScheduler))
            val second = SessionFixture(StandardTestDispatcher(testScheduler), command = "ls -a")
            val terminal = terminal(testScheduler)
            val target = RecordingTarget()
            try {
                terminal.setShellSuggestionTarget(target)
                terminal.bind(first.session)
                focus(terminal, true)
                runCurrent()
                advanceTimeBy(75.milliseconds)
                runCurrent()
                val previous = target.interactions.single()

                terminal.bind(second.session)
                assertTrue(previous.isClosed)
                assertFalse(target.visible)
                runCurrent()
                advanceTimeBy(75.milliseconds)
                runCurrent()

                assertEquals(listOf("git s", "ls -a"), target.interactions.map { it.request.commandText })
                assertTrue(target.visible)
            } finally {
                terminal.dispose()
                first.close()
                second.close()
                runCurrent()
            }
        }

    @Test
    fun `local remote and writer termination cancel automatic work while retaining configured provider`() =
        onEdtTest {
            for (termination in listOf("local", "remote", "writer")) {
                val session = SessionFixture(StandardTestDispatcher(testScheduler))
                val terminal = terminal(testScheduler)
                val target = RecordingTarget()
                try {
                    terminal.setShellSuggestionProvider { emptyFlow() }
                    terminal.setShellSuggestionTarget(target)
                    terminal.bind(session.session)
                    focus(terminal, true)
                    runCurrent()
                    advanceTimeBy(75.milliseconds)
                    runCurrent()
                    val interaction = target.interactions.single()
                    when (termination) {
                        "local" -> session.session.close()
                        "remote" -> session.connector.listener.onClosed(0)
                        "writer" -> {
                            session.connector.writeFailure = IllegalStateException("write failed")
                            assertEquals(TerminalInputAdmission.ACCEPTED, session.session.submitBytes(byteArrayOf(1)))
                        }
                    }
                    runCurrent()

                    assertTrue(session.session.isClosed)
                    assertTrue(interaction.isClosed)
                    assertFalse(target.visible)
                    assertTrue(terminal.hasShellSuggestionProvider)
                    terminal.refreshShellSuggestions()
                    focus(terminal, false)
                    focus(terminal, true)
                    advanceTimeBy(100.milliseconds)
                    runCurrent()
                    assertEquals(1, target.interactions.size)
                } finally {
                    terminal.dispose()
                    session.close()
                    runCurrent()
                }
            }
        }

    @Test
    fun `reentrant target replacement while hiding cannot resume the abandoned target`() =
        onEdtTest {
            val session = SessionFixture(StandardTestDispatcher(testScheduler))
            val terminal = terminal(testScheduler)
            val original = RecordingTarget()
            val abandoned = RecordingTarget()
            val current = RecordingTarget()
            try {
                terminal.setShellSuggestionTarget(original)
                terminal.bind(session.session)
                focus(terminal, true)
                runCurrent()
                advanceTimeBy(75.milliseconds)
                runCurrent()
                original.onHide = {
                    original.onHide = null
                    terminal.setShellSuggestionTarget(current)
                }

                terminal.setShellSuggestionTarget(abandoned)
                runCurrent()
                advanceTimeBy(75.milliseconds)
                runCurrent()

                assertEquals(1, original.interactions.size)
                assertTrue(original.interactions.single().isClosed)
                assertTrue(abandoned.interactions.isEmpty())
                assertEquals(1, current.interactions.size)
                assertTrue(current.visible)
            } finally {
                terminal.dispose()
                session.close()
                runCurrent()
            }
        }

    @Test
    fun `closing an old interaction cannot hide a new interaction on the same custom target`() =
        onEdtTest {
            val session = SessionFixture(StandardTestDispatcher(testScheduler))
            val terminal = terminal(testScheduler)
            val target = RecordingTarget()
            try {
                terminal.setShellSuggestionTarget(target)
                terminal.bind(session.session)
                val previous = startAutomatic(terminal, target)
                var replacement: SwingShellSuggestionInteraction? = null
                previous.addChangeListener {
                    if (it.isClosed && replacement == null) replacement = startAutomatic(terminal, target)
                }

                terminal.hideShellSuggestions()

                assertTrue(previous.isClosed)
                assertTrue(assertNotNull(replacement).isActive)
                assertTrue(target.visible)
                assertSame(replacement, target.interactions.last())
            } finally {
                terminal.dispose()
                session.close()
                runCurrent()
            }
        }

    @Test
    fun `natural closure cleanup cannot hide a custom interaction created by the embedded hide callback`() =
        onEdtTest {
            val session = SessionFixture(StandardTestDispatcher(testScheduler))
            val view = RecordingView()
            val terminal = terminal(testScheduler, view)
            val target = RecordingTarget()
            try {
                terminal.setShellSuggestionTarget(target)
                terminal.bind(session.session)
                val previous = startAutomatic(terminal, target)
                previous.publish(listOf(suggestion))
                terminal.presentShellSuggestions(previous)
                var replacement: SwingShellSuggestionInteraction? = null
                view.onHide = { replacement = startAutomatic(terminal, target) }

                previous.close()

                assertTrue(previous.isClosed)
                assertTrue(assertNotNull(replacement).isActive)
                assertTrue(target.visible)
                assertSame(replacement, target.interactions.last())
            } finally {
                terminal.dispose()
                session.close()
                runCurrent()
            }
        }

    @Test
    fun `target replacement cannot hide a target restored with a new request by its hide callback`() =
        onEdtTest {
            val session = SessionFixture(StandardTestDispatcher(testScheduler))
            val terminal = terminal(testScheduler)
            val target = RecordingTarget()
            val abandoned = RecordingTarget()
            try {
                terminal.setShellSuggestionTarget(target)
                terminal.bind(session.session)
                val previous = startAutomatic(terminal, target)
                var replacement: SwingShellSuggestionInteraction? = null
                target.onHide = {
                    target.onHide = null
                    terminal.setShellSuggestionTarget(target)
                    replacement = startAutomatic(terminal, target)
                }

                terminal.setShellSuggestionTarget(abandoned)

                assertTrue(previous.isClosed)
                assertTrue(assertNotNull(replacement).isActive)
                assertTrue(target.visible)
                assertTrue(abandoned.interactions.isEmpty())
                assertSame(replacement, target.interactions.last())
            } finally {
                terminal.dispose()
                session.close()
                runCurrent()
            }
        }

    @Test
    fun `acceptance and dismissal suppress unchanged automatic commands until a shell edit`() =
        onEdtTest {
            for (accept in listOf(false, true)) {
                val session = SessionFixture(StandardTestDispatcher(testScheduler))
                val terminal = terminal(testScheduler)
                val target = RecordingTarget()
                try {
                    terminal.setShellSuggestionTarget(target)
                    terminal.bind(session.session)
                    focus(terminal, true)
                    runCurrent()
                    advanceTimeBy(75.milliseconds)
                    runCurrent()
                    val interaction = target.interactions.single()
                    interaction.publish(listOf(suggestion), selectedIndex = 0)
                    if (accept) {
                        assertEquals(SwingShellSuggestionAcceptanceResult.ACCEPTED, interaction.tryAccept(interaction.snapshot, 0))
                    } else {
                        assertTrue(interaction.dismiss(interaction.snapshot))
                    }
                    focus(terminal, false)
                    focus(terminal, true)
                    terminal.refreshShellSuggestions()
                    runCurrent()
                    advanceTimeBy(100.milliseconds)
                    runCurrent()
                    assertEquals(1, target.interactions.size)

                    session.model.value = snapshot
                    runCurrent()
                    advanceTimeBy(75.milliseconds)
                    runCurrent()
                    assertEquals(2, target.interactions.size)
                } finally {
                    terminal.dispose()
                    session.close()
                    runCurrent()
                }
            }
        }

    private class SessionFixture(
        dispatcher: CoroutineDispatcher,
        legacySource: MutableStateFlow<TerminalShellCommandLineSnapshot?>? = null,
        command: String = "git s",
    ) : AutoCloseable {
        val connector = RecordingConnector()
        val model = TerminalShellCommandLineState(TerminalShellCommandLineSnapshot(command, command.length, command.length, 0))
        val session =
            TerminalSession.create(
                TerminalBuffers.create(30, 4),
                connector,
                workerDispatcher = dispatcher,
                ioDispatcher = dispatcher,
                shellIntegration =
                    if (legacySource == null) {
                        TerminalShellIntegrationFactory.host(TerminalShellIntegrationState(), model)
                    } else {
                        TerminalShellIntegrationFactory.host(TerminalShellIntegrationState(), legacySource)
                    },
            )

        init {
            session.start(30, 4)
        }

        override fun close() = session.close()
    }

    private class RecordingConnector : TerminalConnector {
        lateinit var listener: TerminalConnectorListener
        val output = ByteArrayOutputStream()
        var writeFailure: Exception? = null

        override fun start(listener: TerminalConnectorListener) {
            this.listener = listener
        }

        override fun write(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) {
            writeFailure?.let { throw it }
            output.write(bytes, offset, length)
        }

        override fun resize(
            columns: Int,
            rows: Int,
        ) = Unit

        override fun close() = Unit
    }

    private class RecordingTarget : SwingShellSuggestionTarget {
        val interactions = ArrayList<SwingShellSuggestionInteraction>()
        var visible = false
        var onHide: (() -> Unit)? = null

        override fun requestSuggestions(interaction: SwingShellSuggestionInteraction) {
            assertTrue(SwingUtilities.isEventDispatchThread())
            interactions += interaction
            visible = true
        }

        override fun hideSuggestions() {
            assertTrue(SwingUtilities.isEventDispatchThread())
            visible = false
            onHide?.invoke()
        }
    }

    private class RecordingView : SwingShellSuggestionView {
        override val component = JPanel()
        var snapshot = SwingShellSuggestionViewSnapshot.EMPTY
        var onHide: (() -> Unit)? = null

        override fun update(snapshot: SwingShellSuggestionViewSnapshot) {
            this.snapshot = snapshot
            if (snapshot.visibleSuggestions.isEmpty()) {
                val callback = onHide
                onHide = null
                callback?.invoke()
            }
        }
    }

    private companion object {
        val snapshot = TerminalShellCommandLineSnapshot("git s", 5, 5, 0)
        val request = SwingShellSuggestionRequest("git s", 5)
        val suggestion = SwingShellSuggestion("replacement", 0, 5, "test", "COMMAND")

        fun settings() =
            SwingSettings.create {
                it.smartSuggestionsEnabled = true
                it.shellSuggestionsEnabled = true
                it.cursorBlinkMillis = 0
                it.useSystemFallbackFonts = false
            }

        fun terminal(
            scheduler: TestCoroutineScheduler,
            view: RecordingView = RecordingView(),
            settingsProvider: SwingSettingsProvider = SwingSettingsProvider { settings() },
        ): SwingTerminal =
            SwingTerminal(
                settingsProvider = settingsProvider,
                hostServices =
                    SwingHostServices.create {
                        it.shellSuggestionViewFactory = { _ -> view }
                        val dispatcher = StandardTestDispatcher(scheduler)
                        it.uiDispatcher = TerminalUiDispatcher { runnable -> dispatcher.dispatch(EmptyCoroutineContext, runnable) }
                    },
                searchDispatcher = StandardTestDispatcher(scheduler),
                suggestionDispatcher = UnconfinedTestDispatcher(scheduler),
            ).also { it.size = it.preferredGridSize(30, 4) }

        fun focus(
            terminal: SwingTerminal,
            focused: Boolean,
        ) {
            val event = FocusEvent(terminal, if (focused) FocusEvent.FOCUS_GAINED else FocusEvent.FOCUS_LOST)
            terminal.focusListeners.forEach { if (focused) it.focusGained(event) else it.focusLost(event) }
        }

        fun startAutomatic(
            terminal: SwingTerminal,
            target: RecordingTarget,
        ): SwingShellSuggestionInteraction =
            assertNotNull(terminal.beginActiveShellSuggestionInteraction(SwingShellSuggestionTrigger.AUTOMATIC)).also {
                target.requestSuggestions(it)
            }

        fun onEdtTest(block: suspend TestScope.() -> Unit) {
            SwingUtilities.invokeAndWait { runTest { block() } }
        }
    }
}

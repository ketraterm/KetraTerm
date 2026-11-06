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
import io.github.ketraterm.input.event.TerminalKey
import io.github.ketraterm.input.event.TerminalKeyEvent
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
import java.awt.event.KeyEvent
import java.io.ByteArrayOutputStream
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class SwingTerminalCompletionLifecycleTest {
    @Test
    fun `only an explicit request confirms a provisional prefix before provider collection and acceptance`() =
        onEdtTest {
            val fixture = SessionFixture(StandardTestDispatcher(testScheduler), nativeCompletion = true)
            val view = RecordingView()
            val terminal = terminal(testScheduler, view)
            val requests = ArrayList<SwingShellSuggestionRequest>()
            var opened = 0
            val confirmed = TerminalShellCompletionResult("git s", 5, 4, 5, emptyList())
            val provider =
                object : SwingShellSuggestionProvider {
                    override fun suggestions(request: SwingShellSuggestionRequest): Flow<List<SwingShellSuggestion>> =
                        flow {
                            requests += request
                            assertSame(confirmed, fixture.session.requestShellCompletions(request.commandText, request.cursorOffset))
                            emit(listOf(suggestion))
                        }.flowOn(StandardTestDispatcher(testScheduler))

                    override fun open(request: SwingShellSuggestionRequest): SwingShellSuggestionSource {
                        opened++
                        return SwingShellSuggestionSource(suggestions(request))
                    }
                }
            try {
                terminal.setShellSuggestionProvider(provider)
                terminal.bind(fixture.session)
                focus(terminal, true)
                runCurrent()
                advanceTimeBy(75.milliseconds)
                runCurrent()
                assertNull(terminal.beginActiveShellSuggestionInteraction(SwingShellSuggestionTrigger.AUTOMATIC))
                assertNull(terminal.beginShellSuggestionInteraction(request, SwingShellSuggestionTrigger.AUTOMATIC))
                runCurrent()
                assertEquals(0, opened)
                assertTrue(requests.isEmpty())
                assertEquals("", fixture.connector.output.toString(Charsets.UTF_8))
                val interaction = assertNotNull(terminal.beginActiveShellSuggestionInteraction())
                terminal.presentShellSuggestions(interaction)
                terminal.requestShellSuggestions(interaction, provider)
                runCurrent()

                assertTrue(requests.isEmpty())
                assertTrue(view.snapshot.visibleSuggestions.isEmpty())
                assertEquals("\u001b[24~", fixture.connector.output.toString(Charsets.UTF_8))

                fixture.respond(confirmed)
                runCurrent()

                assertEquals(listOf(request), requests)
                assertEquals(1, opened)
                assertEquals(listOf(suggestion), view.snapshot.visibleSuggestions)
                assertEquals(
                    "\u001b[24~",
                    fixture.connector.output.toString(Charsets.UTF_8),
                    "Confirmation must not run the completer twice",
                )
                assertEquals(SwingShellSuggestionAcceptanceResult.ACCEPTED, interaction.tryAccept(interaction.snapshot, 0))
            } finally {
                terminal.dispose()
                fixture.close()
                runCurrent()
            }
        }

    @Test
    fun `real editable suffix rejects a provisional prefix without collecting suggestions`() =
        onEdtTest {
            val fixture = SessionFixture(StandardTestDispatcher(testScheduler), nativeCompletion = true)
            val view = RecordingView()
            val terminal = terminal(testScheduler, view)
            var collections = 0
            val provider =
                object : SwingShellSuggestionProvider {
                    override fun suggestions(request: SwingShellSuggestionRequest): Flow<List<SwingShellSuggestion>> =
                        flow {
                            collections++
                            emit(listOf(suggestion))
                        }.flowOn(StandardTestDispatcher(testScheduler))

                    override fun open(request: SwingShellSuggestionRequest) = SwingShellSuggestionSource(suggestions(request))
                }
            try {
                terminal.bind(fixture.session)
                runCurrent()
                val interaction = assertNotNull(terminal.beginActiveShellSuggestionInteraction())
                terminal.presentShellSuggestions(interaction)
                terminal.requestShellSuggestions(interaction, provider)
                runCurrent()
                assertEquals("\u001b[24~", fixture.connector.output.toString(Charsets.UTF_8))
                assertEquals(0, collections)

                fixture.respond(TerminalShellCompletionResult("git status", 5, 4, 10, emptyList()))
                runCurrent()

                assertEquals(SwingShellSuggestionCloseReason.INVALIDATED, interaction.closeReason)
                assertEquals(0, collections)
                assertTrue(view.snapshot.visibleSuggestions.isEmpty())
                assertFalse(interaction.publish(listOf(suggestion)))
            } finally {
                terminal.dispose()
                fixture.close()
                runCurrent()
            }
        }

    @Test
    fun `cancelling a pending shell confirmation ignores its late reply`() =
        onEdtTest {
            val fixture = SessionFixture(StandardTestDispatcher(testScheduler), nativeCompletion = true)
            val view = RecordingView()
            val terminal = terminal(testScheduler, view)
            var collections = 0
            val provider =
                object : SwingShellSuggestionProvider {
                    override fun suggestions(request: SwingShellSuggestionRequest): Flow<List<SwingShellSuggestion>> =
                        flow {
                            collections++
                            emit(listOf(suggestion))
                        }.flowOn(StandardTestDispatcher(testScheduler))

                    override fun open(request: SwingShellSuggestionRequest) = SwingShellSuggestionSource(suggestions(request))
                }
            try {
                terminal.bind(fixture.session)
                runCurrent()
                val interaction = assertNotNull(terminal.beginActiveShellSuggestionInteraction())
                terminal.presentShellSuggestions(interaction)
                terminal.requestShellSuggestions(interaction, provider)
                runCurrent()
                assertEquals("\u001b[24~", fixture.connector.output.toString(Charsets.UTF_8))
                assertEquals(0, collections)

                terminal.hideShellSuggestions()
                runCurrent()
                fixture.respond(TerminalShellCompletionResult("git s", 5, 4, 5, emptyList()))
                runCurrent()

                assertTrue(interaction.isClosed)
                assertEquals(0, collections)
                assertTrue(view.snapshot.visibleSuggestions.isEmpty())
                assertFalse(interaction.publish(listOf(suggestion)))
            } finally {
                terminal.dispose()
                fixture.close()
                runCurrent()
            }
        }

    @Test
    fun `explicit command suggestions survive pending automatic refresh and settings changes until input changes`() =
        onEdtTest {
            val fixture = SessionFixture(StandardTestDispatcher(testScheduler), command = "gi")
            val view = RecordingView()
            var settings = settings().copy { it.shellSuggestionAutomaticMode = SwingShellSuggestionAutomaticMode.PARAMETERS_ONLY }
            val terminal = terminal(testScheduler, view) { settings }
            val target = RecordingTarget()
            try {
                terminal.setShellSuggestionTarget(target)
                terminal.bind(fixture.session)
                runCurrent()
                focus(terminal, true)
                advanceTimeBy(50.milliseconds)

                val explicit = assertNotNull(terminal.beginActiveShellSuggestionInteraction())
                assertTrue(explicit.request.includeCommandNames)
                val command = SwingShellSuggestion("git", 0, 2, "git", "COMMAND")
                terminal.presentShellSuggestions(explicit)
                explicit.publish(listOf(command))

                advanceTimeBy(25.milliseconds)
                runCurrent()
                assertTrue(explicit.isActive)
                assertEquals(listOf(command), view.snapshot.visibleSuggestions)
                assertTrue(target.interactions.isEmpty())

                settings = settings.copy { it.shellSuggestionsEnabled = false }
                terminal.reloadSettings()
                settings = settings.copy { it.shellSuggestionsEnabled = true }
                terminal.reloadSettings()
                advanceTimeBy(75.milliseconds)
                runCurrent()
                assertTrue(explicit.isActive)
                assertTrue(target.interactions.isEmpty())

                fixture.model.value = TerminalShellCommandLineSnapshot("git ", 4, 4, 0)
                runCurrent()
                assertFalse(explicit.isActive)
                advanceTimeBy(75.milliseconds)
                runCurrent()
                val automatic = target.interactions.single()
                assertEquals("git ", automatic.request.commandText)
                assertFalse(automatic.request.includeCommandNames)
            } finally {
                terminal.dispose()
                fixture.close()
                runCurrent()
            }
        }

    @Test
    fun `explicit empty results retire a pending automatic refresh`() =
        onEdtTest {
            val fixture = SessionFixture(StandardTestDispatcher(testScheduler), command = "gi")
            val terminal = terminal(testScheduler)
            val requests = ArrayList<SwingShellSuggestionRequest>()
            val provider =
                object : SwingShellSuggestionProvider {
                    override fun suggestions(request: SwingShellSuggestionRequest): Flow<List<SwingShellSuggestion>> =
                        flowOf(emptyList<SwingShellSuggestion>()).flowOn(StandardTestDispatcher(testScheduler))

                    override fun open(request: SwingShellSuggestionRequest): SwingShellSuggestionSource {
                        requests += request
                        return SwingShellSuggestionSource(suggestions(request))
                    }
                }
            try {
                terminal.setShellSuggestionProvider(provider)
                terminal.bind(fixture.session)
                runCurrent()
                focus(terminal, true)
                advanceTimeBy(50.milliseconds)

                val explicit = assertNotNull(terminal.beginActiveShellSuggestionInteraction())
                terminal.requestShellSuggestions(explicit, provider)
                runCurrent()
                assertTrue(explicit.isClosed)

                advanceTimeBy(25.milliseconds)
                runCurrent()
                assertEquals(listOf("gi"), requests.map { it.commandText })

                fixture.model.value = TerminalShellCommandLineSnapshot("git", 3, 3, 0)
                runCurrent()
                advanceTimeBy(75.milliseconds)
                runCurrent()
                assertEquals(listOf("gi", "git"), requests.map { it.commandText })
            } finally {
                terminal.dispose()
                fixture.close()
                runCurrent()
            }
        }

    @Test
    fun `releasing backspace after refreshed suggestions still permits acceptance`() =
        onEdtTest {
            val fixture = SessionFixture(StandardTestDispatcher(testScheduler), command = "git st")
            val terminal = terminal(testScheduler)
            try {
                terminal.bind(fixture.session)
                runCurrent()
                val press = KeyEvent(terminal, KeyEvent.KEY_PRESSED, 0L, 0, KeyEvent.VK_BACK_SPACE, '\b')
                terminal.keyListeners.forEach { it.keyPressed(press) }
                runCurrent()
                fixture.model.value = snapshot
                runCurrent()
                val interaction = assertNotNull(terminal.beginActiveShellSuggestionInteraction())
                interaction.publish(listOf(suggestion), selectedIndex = 0)
                val release = KeyEvent(terminal, KeyEvent.KEY_RELEASED, 0L, 0, KeyEvent.VK_BACK_SPACE, '\b')
                terminal.keyListeners.forEach { it.keyReleased(release) }
                runCurrent()
                assertEquals(SwingShellSuggestionAcceptanceResult.ACCEPTED, interaction.tryAccept(interaction.snapshot, 0))
                runCurrent()
                assertEquals("\u007f".repeat(6) + "replacement", fixture.connector.output.toString(Charsets.UTF_8))
            } finally {
                terminal.dispose()
                fixture.close()
                runCurrent()
            }
        }

    @Test
    fun `default command editing follows the bound session even when both commands are equal`() =
        onEdtTest {
            val first = SessionFixture(StandardTestDispatcher(testScheduler))
            val second = SessionFixture(StandardTestDispatcher(testScheduler))
            val terminal = terminal(testScheduler)
            try {
                terminal.bind(first.session)
                runCurrent()
                val old = assertNotNull(terminal.beginActiveShellSuggestionInteraction())
                old.publish(listOf(suggestion))
                val oldPublication = old.snapshot

                terminal.bind(second.session)
                runCurrent()
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
    fun `shortening the shell command cancels its pending source before refreshed acceptance`() =
        onEdtTest {
            val fixture = SessionFixture(StandardTestDispatcher(testScheduler), command = "git sta")
            val terminal = terminal(testScheduler)
            val entered = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val status = SwingShellSuggestion("status", 4, 6, "test", "ARGUMENT")
            val provider =
                object : SwingShellSuggestionProvider {
                    override fun suggestions(request: SwingShellSuggestionRequest): Flow<List<SwingShellSuggestion>> =
                        if (request.commandText ==
                            "git sta"
                        ) {
                            flow {
                                entered.complete(Unit)
                                try {
                                    awaitCancellation()
                                } finally {
                                    cancelled.complete(Unit)
                                }
                            }
                        } else {
                            flowOf(listOf(status))
                        }

                    override fun open(request: SwingShellSuggestionRequest) =
                        SwingShellSuggestionSource(suggestions(request).flowOn(StandardTestDispatcher(testScheduler)))
                }
            try {
                terminal.bind(fixture.session)
                terminal.setShellSuggestionProvider(provider)
                runCurrent()
                val old = assertNotNull(terminal.beginActiveShellSuggestionInteraction())
                terminal.requestShellSuggestions(old, provider)
                entered.await()

                fixture.model.value = TerminalShellCommandLineSnapshot("git st", 6, 6, 0)
                runCurrent()
                cancelled.await()
                assertTrue(old.isClosed)
                assertFalse(old.publish(listOf(status)))

                val refreshed = assertNotNull(terminal.beginActiveShellSuggestionInteraction())
                terminal.requestShellSuggestions(refreshed, provider)
                runCurrent()
                assertEquals(listOf(status), refreshed.snapshot.suggestions)
                assertEquals(SwingShellSuggestionAcceptanceResult.ACCEPTED, refreshed.tryAccept(refreshed.snapshot, 0))
                runCurrent()
                assertEquals("\u007f\u007fstatus", fixture.connector.output.toString(Charsets.UTF_8))
            } finally {
                terminal.dispose()
                fixture.close()
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
                    runCurrent()
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
                runCurrent()
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
                runCurrent()
                focus(terminal, true)
                runCurrent()
                advanceTimeBy(75.milliseconds)
                runCurrent()
                val previous = target.interactions.single()

                terminal.bind(second.session)
                runCurrent()
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
                    runCurrent()
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
                runCurrent()
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
                runCurrent()
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
                runCurrent()
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
                runCurrent()
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
                    runCurrent()
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

                    if (!accept) {
                        session.model.value = snapshot.copy(cursorOffset = 4, cursorColumn = 4)
                        runCurrent()
                        advanceTimeBy(75.milliseconds)
                        runCurrent()
                        assertEquals(1, target.interactions.size, "Cursor movement must retain explicit dismissal")
                    }
                    session.model.value = snapshot.copy(commandText = "git st", cursorOffset = 6, cursorColumn = 6)
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

    @Test
    fun `context refresh closes automatic suggestions immediately and requests again after debounce`() =
        onEdtTest {
            val session = SessionFixture(StandardTestDispatcher(testScheduler))
            val terminal = terminal(testScheduler)
            val target = RecordingTarget()
            try {
                terminal.setShellSuggestionTarget(target)
                terminal.bind(session.session)
                runCurrent()
                focus(terminal, true)
                runCurrent()
                advanceTimeBy(75.milliseconds)
                runCurrent()
                val old = target.interactions.single()

                terminal.refreshShellSuggestions()

                assertTrue(old.isClosed)
                assertFalse(target.visible)
                advanceTimeBy(74.milliseconds)
                runCurrent()
                assertEquals(1, target.interactions.size)
                advanceTimeBy(1.milliseconds)
                runCurrent()
                assertEquals(2, target.interactions.size)
                assertTrue(target.interactions.last().isActive)
            } finally {
                terminal.dispose()
                session.close()
                runCurrent()
            }
        }

    @Test
    fun `context refresh preserves a replacement request opened by the hide callback`() =
        onEdtTest {
            val session = SessionFixture(StandardTestDispatcher(testScheduler))
            val terminal = terminal(testScheduler)
            val target = RecordingTarget()
            try {
                terminal.setShellSuggestionTarget(target)
                terminal.bind(session.session)
                runCurrent()
                focus(terminal, true)
                runCurrent()
                advanceTimeBy(75.milliseconds)
                runCurrent()
                val old = target.interactions.single()
                target.onHide = {
                    target.onHide = null
                    startAutomatic(terminal, target)
                }

                terminal.refreshShellSuggestions()
                advanceTimeBy(100.milliseconds)
                runCurrent()

                assertTrue(old.isClosed)
                assertEquals(2, target.interactions.size)
                assertTrue(target.interactions.last().isActive)
                assertTrue(target.visible)
            } finally {
                terminal.dispose()
                session.close()
                runCurrent()
            }
        }

    private class SessionFixture(
        dispatcher: CoroutineDispatcher,
        legacySource: MutableStateFlow<TerminalShellCommandLineSnapshot?>? = null,
        command: String = "git s",
        nativeCompletion: Boolean = false,
    ) : AutoCloseable {
        val connector = RecordingConnector()
        val model =
            TerminalShellCommandLineState(
                TerminalShellCommandLineSnapshot(command, command.length, command.length, 0, requiresShellConfirmation = nativeCompletion),
            )
        private lateinit var completion: TerminalShellCompletionChannel
        val session =
            TerminalSession.create(
                TerminalBuffers.create(30, 4),
                connector,
                workerDispatcher = dispatcher,
                ioDispatcher = dispatcher,
                cleanupDispatcher = dispatcher,
                shellIntegration =
                    TerminalShellIntegrationFactory { context ->
                        completion = context.shellCompletion
                        if (nativeCompletion) completion.ready("test", listOf(TerminalKeyEvent.key(TerminalKey.F12)))
                        val host =
                            if (legacySource == null) {
                                TerminalShellIntegrationFactory.host(TerminalShellIntegrationState(), model)
                            } else {
                                TerminalShellIntegrationFactory.host(TerminalShellIntegrationState(), legacySource)
                            }
                        host.create(context)
                    },
            )

        init {
            session.start(30, 4)
        }

        fun respond(result: TerminalShellCompletionResult) {
            completion.respond("test", 1, result)
            val bytes = "\u001b]999;reply\u0007".toByteArray()
            connector.listener.onBytes(bytes, 0, bytes.size)
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
                mutationDispatcher = StandardTestDispatcher(scheduler),
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

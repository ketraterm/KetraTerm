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

import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.session.*
import io.github.ketraterm.transport.TerminalConnector
import io.github.ketraterm.transport.TerminalConnectorListener
import io.github.ketraterm.ui.swing.api.SwingHostServices
import io.github.ketraterm.ui.swing.api.SwingTerminal
import io.github.ketraterm.ui.swing.settings.SwingSettings
import io.github.ketraterm.ui.swing.suggestion.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SwingCompletionBindingTest {
    @Test
    fun `empty binding rejects requests and feedback and installs no observation`() =
        runBlocking {
            Fixture().use { fixture ->
                val listeners = fixture.terminal.focusListeners.size
                SwingUtilities.invokeAndWait {
                    fixture.binding.update(null, true)
                    fixture.binding.feedbackHandler.onSuggestionFeedback(feedback())
                    assertFalse(fixture.binding.isEnabled)
                    assertEquals(listeners, fixture.terminal.focusListeners.size)
                }
                assertTrue(
                    fixture.binding.provider
                        .suggestions(request)
                        .toList()
                        .isEmpty(),
                )
            }
        }

    @Test
    fun `manual-only resources accept requests and feedback without automatic observation`() =
        runBlocking {
            Fixture().use { fixture ->
                var requests = 0
                var feedbackCount = 0
                val resources =
                    SwingCompletionResources(
                        provider = {
                            requests++
                            flowOf(listOf(suggestion))
                        },
                        feedbackHandler = { feedbackCount++ },
                    )
                val listeners = fixture.terminal.focusListeners.size
                SwingUtilities.invokeAndWait { fixture.binding.update(resources, false) }
                assertEquals(
                    listOf(listOf(suggestion)),
                    fixture.binding.provider
                        .suggestions(request)
                        .toList(),
                )
                SwingUtilities.invokeAndWait {
                    fixture.binding.feedbackHandler.onSuggestionFeedback(feedback())
                    assertEquals(listeners, fixture.terminal.focusListeners.size)
                    fixture.binding.update(null, false)
                    fixture.binding.feedbackHandler.onSuggestionFeedback(feedback())
                }
                assertTrue(
                    fixture.binding.provider
                        .suggestions(request)
                        .toList()
                        .isEmpty(),
                )
                assertEquals(1, requests)
                assertEquals(1, feedbackCount)
            }
        }

    @Test
    fun `resource replacement rejects an old in-flight result`() =
        runBlocking {
            Fixture().use { fixture ->
                val started = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val old =
                    SwingCompletionResources(provider = {
                        flow {
                            started.complete(Unit)
                            release.await()
                            emit(listOf(suggestion))
                        }
                    }, feedbackHandler = SwingShellSuggestionFeedbackHandler.NONE)
                SwingUtilities.invokeAndWait { fixture.binding.update(old, false) }
                val oldResult =
                    async {
                        fixture.binding.provider
                            .suggestions(request)
                            .toList()
                    }
                started.await()
                val replacement =
                    SwingCompletionResources(provider = { flowOf(emptyList()) }, feedbackHandler = SwingShellSuggestionFeedbackHandler.NONE)
                SwingUtilities.invokeAndWait {
                    fixture.binding.update(null, false)
                    fixture.binding.update(replacement, false)
                }
                release.complete(Unit)
                assertTrue(oldResult.await().isEmpty())
                assertEquals(
                    listOf(emptyList()),
                    fixture.binding.provider
                        .suggestions(request)
                        .toList(),
                )
            }
        }

    @Test
    fun `automatic toggle removes and restores exactly one observer per pane`() {
        Fixture().use { first ->
            Fixture().use { second ->
                SwingUtilities.invokeAndWait {
                    val resources =
                        SwingCompletionResources(
                            provider = { flowOf(emptyList()) },
                            feedbackHandler = SwingShellSuggestionFeedbackHandler.NONE,
                        )
                    val firstCount = first.terminal.focusListeners.size
                    val secondCount = second.terminal.focusListeners.size
                    repeat(3) {
                        first.binding.update(resources, true)
                        first.binding.update(resources, true)
                        second.binding.update(resources, true)
                        assertEquals(firstCount + 1, first.terminal.focusListeners.size)
                        assertEquals(secondCount + 1, second.terminal.focusListeners.size)
                        first.binding.update(resources, false)
                        assertTrue(first.binding.isEnabled)
                        assertEquals(firstCount, first.terminal.focusListeners.size)
                        first.binding.update(null, false)
                        second.binding.update(null, false)
                        assertEquals(secondCount, second.terminal.focusListeners.size)
                    }
                }
            }
        }
    }

    @Test
    fun `close releases automatic observation when popup hiding fails`() = verifyCloseFailure(IllegalStateException("host hiding failed"))

    @Test
    fun `close releases automatic observation when popup hiding is cancelled`() =
        verifyCloseFailure(CancellationException("host hiding cancelled"))

    @Test
    fun `resource replacement releases obsolete observation when popup hiding fails`() =
        verifyReplacementFailure(IllegalStateException("host hiding failed"))

    @Test
    fun `resource replacement releases obsolete observation when popup hiding is cancelled`() =
        verifyReplacementFailure(CancellationException("host hiding cancelled"))

    private fun verifyCloseFailure(failure: Exception) =
        runTest {
            val view = FailingHideView()
            val fixture = Fixture(view, StandardTestDispatcher(testScheduler))
            try {
                val originalFocusListeners = withContext(Dispatchers.Swing) { fixture.terminal.focusListeners.toList() }
                withContext(Dispatchers.Swing) {
                    fixture.binding.update(resources(), true)
                }
                withContext(Dispatchers.Swing) { }
                runCurrent()
                assertEquals(1, fixture.commandLine.subscriptionCount.value, "Automatic observation must be active before close")
                withContext(Dispatchers.Swing) {
                    assertEquals(originalFocusListeners.size + 1, fixture.terminal.focusListeners.size)
                    fixture.terminal.showShellSuggestions(request, listOf(suggestion))
                    assertTrue(view.component.isVisible)
                    view.failure = failure
                    assertSame(failure, assertFailsWith<Exception> { fixture.binding.close() })
                    assertFalse(fixture.binding.isEnabled)
                    assertFalse(view.component.isVisible)
                    assertEquals(SwingShellSuggestionState.EMPTY, fixture.terminal.currentShellSuggestionState())
                    fixture.binding.close()
                    assertEquals(
                        originalFocusListeners,
                        fixture.terminal.focusListeners.toList(),
                        "Close must release its focus registration",
                    )
                    assertIs<TerminalSessionState.Created>(fixture.session.state.value, "Closing a view must preserve the host session")
                }
                withContext(Dispatchers.Swing) { }
                runCurrent()
                assertEquals(0, fixture.commandLine.subscriptionCount.value, "Close must release the shell-model subscription")
                assertTrue(
                    fixture.binding.provider
                        .suggestions(request)
                        .toList()
                        .isEmpty(),
                )
            } finally {
                withContext(Dispatchers.Swing) { view.failure = null }
                fixture.close()
                runCurrent()
            }
        }

    private fun verifyReplacementFailure(failure: Exception) =
        runTest {
            val view = FailingHideView()
            val fixture = Fixture(view, StandardTestDispatcher(testScheduler))
            try {
                var oldFeedback = 0
                var replacementFeedback = 0
                val replacement = resources { replacementFeedback++ }
                val originalFocusListeners = withContext(Dispatchers.Swing) { fixture.terminal.focusListeners.toList() }
                withContext(Dispatchers.Swing) {
                    fixture.binding.update(resources { oldFeedback++ }, true)
                }
                withContext(Dispatchers.Swing) { }
                runCurrent()
                assertEquals(1, fixture.commandLine.subscriptionCount.value, "Automatic observation must be active before replacement")
                withContext(Dispatchers.Swing) {
                    fixture.terminal.showShellSuggestions(request, listOf(suggestion))
                    assertTrue(view.component.isVisible)
                    view.failure = failure
                    assertSame(failure, assertFailsWith<Exception> { fixture.binding.update(replacement, false) })
                    assertTrue(fixture.binding.isEnabled)
                    assertFalse(view.component.isVisible)
                    fixture.binding.feedbackHandler.onSuggestionFeedback(feedback())
                    assertEquals(0, oldFeedback, "Obsolete automatic binding must not receive replacement feedback")
                    assertEquals(1, replacementFeedback)
                    assertEquals(
                        originalFocusListeners,
                        fixture.terminal.focusListeners.toList(),
                        "Manual replacement must remove automatic observation",
                    )
                }
                withContext(Dispatchers.Swing) { }
                runCurrent()
                assertEquals(0, fixture.commandLine.subscriptionCount.value, "Replacement must release the old shell-model subscription")
                withContext(Dispatchers.Swing) {
                    view.failure = null
                    fixture.binding.update(replacement, true)
                    assertEquals(
                        originalFocusListeners.size + 1,
                        fixture.terminal.focusListeners.size,
                        "Recovery must install exactly one observer",
                    )
                    fixture.binding.update(replacement, false)
                    assertEquals(originalFocusListeners, fixture.terminal.focusListeners.toList())
                }
            } finally {
                withContext(Dispatchers.Swing) { view.failure = null }
                fixture.close()
                runCurrent()
            }
        }

    private class FailingHideView : SwingShellSuggestionView {
        override val component: JComponent = JPanel()
        var failure: Exception? = null

        override fun update(snapshot: SwingShellSuggestionViewSnapshot) {
            if (snapshot == SwingShellSuggestionViewSnapshot.EMPTY) failure?.let { throw it }
        }
    }

    private class Fixture(
        view: SwingShellSuggestionView? = null,
        dispatcher: CoroutineDispatcher? = null,
    ) : AutoCloseable {
        val commandLine = MutableStateFlow<TerminalShellCommandLineSnapshot?>(null)
        val session =
            TerminalSession.create(
                TerminalBuffers.create(30, 4),
                connector =
                    object : TerminalConnector {
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

                        override fun close() = Unit
                    },
                workerDispatcher = dispatcher ?: Dispatchers.Default,
                ioDispatcher = dispatcher ?: Dispatchers.IO,
                shellIntegration = TerminalShellIntegrationFactory.host(TerminalShellIntegrationState(), commandLine),
            )
        val binding = SwingCompletionBinding(session)
        val terminal =
            SwingTerminal(
                settingsProvider = { SwingSettings(smartSuggestionsEnabled = true) },
                hostServices =
                    SwingHostServices(
                        shellSuggestionProvider = binding.provider,
                        shellSuggestionFeedbackHandler = binding.feedbackHandler,
                        shellSuggestionViewFactory =
                            view?.let { supplied -> SwingShellSuggestionViewFactory { supplied } }
                                ?: SwingShellSuggestionViewFactory.DEFAULT,
                    ),
            )

        init {
            SwingUtilities.invokeAndWait {
                terminal.bind(session)
                binding.attach(terminal)
            }
        }

        override fun close() {
            SwingUtilities.invokeAndWait {
                binding.close()
                terminal.dispose()
            }
            session.close()
        }
    }

    private companion object {
        val request = SwingShellSuggestionRequest("git s", 5, 5, 0)
        val suggestion = SwingShellSuggestion("git status", 0, 5, "test", "COMMAND")

        fun feedback() = SwingShellSuggestionFeedback(SwingShellSuggestionFeedbackKind.ACCEPTED, suggestion, 0, request)

        fun resources(onFeedback: () -> Unit = {}) =
            SwingCompletionResources(
                provider = { flowOf(listOf(suggestion)) },
                feedbackHandler = { onFeedback() },
            )
    }
}

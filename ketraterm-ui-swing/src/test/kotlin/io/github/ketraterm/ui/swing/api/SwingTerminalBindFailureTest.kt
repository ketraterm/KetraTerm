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
import io.github.ketraterm.host.HostPolicy
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.transport.TerminalConnector
import io.github.ketraterm.transport.TerminalConnectorListener
import io.github.ketraterm.ui.swing.settings.SwingPadding
import io.github.ketraterm.ui.swing.settings.SwingSettings
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionEditTarget
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.ConcurrentLinkedQueue
import javax.swing.SwingUtilities

class SwingTerminalBindFailureTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `off EDT bind rejection completes once and permits retry`(oneSlotAvailable: Boolean) {
        Fixture().use { fixture ->
            fixture.fillQueue(oneSlotAvailable)
            val failures = mutableListOf<Throwable?>()
            val escaped =
                fixture.bindOffEdt {
                    assertTrue(SwingUtilities.isEventDispatchThread())
                    failures.add(it)
                }
            assertNull(escaped, "Admission failure must reach the callback instead of escaping the dispatched action")
            assertEquals(1, failures.size)
            assertInstanceOf(IllegalStateException::class.java, failures.single())
            fixture.flush()
            assertEquals(1, failures.size)
            assertFalse(fixture.target.modeSnapshot.treatAmbiguousAsWide, "Failed binding must invalidate accepted settings")
            fixture.assertDetached()
            fixture.assertSessionsOpen()

            val retried = mutableListOf<Throwable?>()
            edtCall { fixture.view.bind(fixture.target) { retried.add(it) } }
            fixture.flush()
            assertEquals(listOf<Throwable?>(null), retried)
            assertTrue(fixture.target.modeSnapshot.treatAmbiguousAsWide)
        }
    }

    @Test
    fun `throwing rejection callback is not invoked again`() {
        Fixture().use { fixture ->
            fixture.fillQueue()
            val callbackFailure = IllegalArgumentException("Callback failed")
            var calls = 0
            val escaped =
                fixture.bindOffEdt { failure ->
                    assertTrue(SwingUtilities.isEventDispatchThread())
                    assertInstanceOf(IllegalStateException::class.java, failure)
                    calls++
                    throw callbackFailure
                }
            if (escaped != null) assertSame(callbackFailure, escaped)
            fixture.flush()
            assertEquals(1, calls)
            fixture.assertDetached()
            fixture.assertSessionsOpen()
        }
    }

    @Test
    fun `rejection callback can bind a replacement after pending work retires`() {
        Fixture().use { fixture ->
            fixture.fillQueue()
            var failedCalls = 0
            val replacement = mutableListOf<Throwable?>()
            val escaped =
                fixture.bindOffEdt { failure ->
                    assertTrue(SwingUtilities.isEventDispatchThread())
                    assertInstanceOf(IllegalStateException::class.java, failure)
                    failedCalls++
                    fixture.flush()
                    fixture.assertDetached()
                    fixture.view.bind(fixture.replacement) { replacement.add(it) }
                }
            assertNull(escaped)
            fixture.flush()
            assertEquals(1, failedCalls)
            assertEquals(listOf<Throwable?>(null), replacement)
            assertTrue(fixture.replacement.modeSnapshot.treatAmbiguousAsWide)
            fixture.assertBound()
            fixture.assertSessionsOpen()
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `suggestion cleanup rebind preserves the replacement binding`(reject: Boolean) {
        Fixture().use { fixture ->
            var closedInteractions = 0
            val replacement = mutableListOf<Throwable?>()
            edtCall {
                val interaction =
                    checkNotNull(
                        fixture.view.beginShellSuggestionInteraction(
                            SwingShellSuggestionRequest("x", 1),
                            editTarget = SwingShellSuggestionEditTarget.NONE,
                        ),
                    )
                interaction.addChangeListener {
                    if (it.isClosed) {
                        closedInteractions++
                        fixture.flush()
                        fixture.view.bind(fixture.replacement) { failure -> replacement.add(failure) }
                    }
                }
            }
            fixture.flush()
            if (reject) fixture.fillQueue()
            val failures = mutableListOf<Throwable?>()
            val escaped = fixture.bindOffEdt { failures.add(it) }
            assertNull(escaped)
            fixture.flush()
            assertEquals(1, closedInteractions)
            assertEquals(1, failures.size)
            if (reject) {
                assertInstanceOf(IllegalStateException::class.java, failures.single())
            } else {
                assertInstanceOf(CancellationException::class.java, failures.single())
            }
            assertEquals(listOf<Throwable?>(null), replacement)
            fixture.assertBound()
            fixture.assertSessionsOpen()
        }
    }

    @Test
    fun `viewport callback rebind cannot resize the previous session`() {
        Fixture().use { fixture ->
            edtCall { fixture.view.bind(fixture.target) }
            fixture.flush()
            val obsolete = mutableListOf<Throwable?>()
            val replacement = mutableListOf<Throwable?>()
            var viewportCallbacks = 0
            edtCall {
                fixture.onViewportChanged = {
                    fixture.onViewportChanged = null
                    viewportCallbacks++
                    fixture.view.bind(fixture.replacement) { replacement.add(it) }
                }
                fixture.view.size = fixture.view.preferredGridSize(12, 3)
                fixture.view.resizeSessionToVisibleGrid { obsolete.add(it) }
                fixture.flush()
            }
            fixture.flush()
            assertEquals(1, viewportCallbacks)
            assertEquals(1, obsolete.size)
            assertInstanceOf(CancellationException::class.java, obsolete.single())
            assertEquals(listOf<Throwable?>(null), replacement)
            fixture.target.readRenderFrame {
                assertEquals(10, it.columns)
                assertEquals(2, it.rows)
            }
            fixture.replacement.readRenderFrame {
                assertEquals(12, it.columns)
                assertEquals(3, it.rows)
            }
            fixture.assertBound()
            fixture.assertSessionsOpen()
        }
    }

    @Test
    fun `disposal completes pending initialization once and preserves session ownership`() {
        Fixture().use { fixture ->
            val failures = mutableListOf<Throwable?>()
            edtCall {
                fixture.view.bind(fixture.target) {
                    assertTrue(SwingUtilities.isEventDispatchThread())
                    failures.add(it)
                }
                fixture.view.dispose()
            }
            fixture.flush()
            assertEquals(1, failures.size)
            assertInstanceOf(CancellationException::class.java, failures.single())
            assertFalse(fixture.target.modeSnapshot.treatAmbiguousAsWide)
            fixture.assertSessionsOpen()
        }
    }

    @Test
    fun `obsolete initialization failure does not detach a newer binding`() {
        Fixture().use { fixture ->
            val obsolete = mutableListOf<Throwable?>()
            val replacement = mutableListOf<Throwable?>()
            edtCall {
                fixture.view.bind(fixture.target) {
                    assertTrue(SwingUtilities.isEventDispatchThread())
                    obsolete.add(it)
                }
                fixture.view.bind(fixture.replacement) {
                    assertTrue(SwingUtilities.isEventDispatchThread())
                    replacement.add(it)
                }
            }
            fixture.flush()
            assertEquals(1, obsolete.size)
            assertInstanceOf(CancellationException::class.java, obsolete.single())
            assertEquals(listOf<Throwable?>(null), replacement)
            assertFalse(fixture.target.modeSnapshot.treatAmbiguousAsWide)
            assertTrue(fixture.replacement.modeSnapshot.treatAmbiguousAsWide)
            fixture.assertBound()
            fixture.assertSessionsOpen()
        }
    }

    private class Fixture : AutoCloseable {
        private val dispatcher = StandardTestDispatcher()
        private val ui = ConcurrentLinkedQueue<Runnable>()
        var onViewportChanged: (() -> Unit)? = null
        private val first = session()
        val target = session()
        val replacement = session()
        val view =
            edtCall {
                SwingTerminal(
                    { settings() },
                    SwingHostServices.create {
                        it.uiDispatcher = TerminalUiDispatcher { task -> ui.add(task) }
                        it.viewportListener = TerminalViewportListener { _, _, _, _, _ -> onViewportChanged?.invoke() }
                    },
                    searchDispatcher = dispatcher,
                    hyperlinkDispatcher = dispatcher,
                    suggestionDispatcher = dispatcher,
                    mutationDispatcher = dispatcher,
                ).apply { size = preferredGridSize(10, 2) }
            }

        init {
            val completed = mutableListOf<Throwable?>()
            edtCall { view.bind(first) { completed.add(it) } }
            flush()
            assertEquals(listOf<Throwable?>(null), completed)
        }

        fun fillQueue(oneSlotAvailable: Boolean = false) {
            edtCall {
                var accepted = 0
                assertThrows(IllegalStateException::class.java) {
                    repeat(1_024) {
                        view.setHostPolicy(HostPolicy()) {}
                        accepted++
                    }
                }
                assertTrue(accepted > 1)
                assertTrue(ui.isEmpty())
            }
            if (oneSlotAvailable) {
                // Retire one request on the EDT while the next worker step stays queued.
                dispatcher.scheduler.runCurrent()
                assertFalse(ui.isEmpty())
                edtCall { while (true) (ui.poll() ?: break).run() }
            }
        }

        fun bindOffEdt(onComplete: (Throwable?) -> Unit): Throwable? {
            assertFalse(SwingUtilities.isEventDispatchThread())
            assertTrue(ui.isEmpty())
            view.bind(target, onComplete)
            return edtCall { runCatching { checkNotNull(ui.poll()).run() }.exceptionOrNull() }
        }

        fun flush() {
            edtCall {
                repeat(1_024) {
                    dispatcher.scheduler.runCurrent()
                    val task = ui.poll() ?: return@edtCall
                    task.run()
                }
                fail("UI work did not settle")
            }
        }

        fun assertDetached() {
            edtCall { assertThrows(IllegalStateException::class.java) { view.setHostPolicy(HostPolicy()) {} } }
        }

        fun assertBound() {
            val completed = mutableListOf<Throwable?>()
            edtCall { view.setHostPolicy(HostPolicy()) { completed.add(it) } }
            flush()
            assertEquals(listOf<Throwable?>(null), completed)
        }

        fun assertSessionsOpen() {
            assertFalse(first.isClosed)
            assertFalse(target.isClosed)
            assertFalse(replacement.isClosed)
        }

        override fun close() {
            edtCall { view.dispose() }
            flush()
            first.close()
            target.close()
            replacement.close()
            flush()
        }

        private fun session(): TerminalSession =
            TerminalSession
                .create(
                    TerminalBuffers.create(10, 2),
                    object : TerminalConnector {
                        override fun start(listener: TerminalConnectorListener) = Unit

                        override fun resize(
                            columns: Int,
                            rows: Int,
                        ) = Unit

                        override fun write(
                            bytes: ByteArray,
                            offset: Int,
                            length: Int,
                        ) = Unit

                        override fun close() = Unit
                    },
                    workerDispatcher = dispatcher,
                ).also { it.start(10, 2) }

        private fun settings(): SwingSettings =
            SwingSettings.create {
                it.columns = 10
                it.rows = 2
                it.padding = SwingPadding()
                it.alternateScreenPadding = SwingPadding()
                it.shellIntegrationDecorationGutterWidth = 0
                it.cursorBlinkMillis = 0
                it.smartSuggestionsEnabled = true
                it.treatAmbiguousAsWide = true
            }
    }
}

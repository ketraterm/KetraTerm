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
import io.github.ketraterm.host.HostPolicy
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.transport.TerminalConnector
import io.github.ketraterm.transport.TerminalConnectorListener
import io.github.ketraterm.ui.swing.api.SwingTerminal
import io.github.ketraterm.ui.swing.settings.SwingPadding
import io.github.ketraterm.ui.swing.settings.SwingSettings
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.awt.event.ComponentEvent
import java.util.concurrent.*
import javax.swing.SwingUtilities
import kotlin.test.*

class InitialViewportSynchronizationTest {
    @Test
    fun `superseded startup geometry waits for the latest installed size`() {
        Fixture().use { f ->
            val firstAttempt = CompletableFuture<Unit>()
            val retry = CompletableFuture<Unit>()
            val completed = CompletableFuture<Throwable?>()
            val failures = mutableListOf<Throwable?>()
            var attempts = 0
            var completions = 0
            onEdt {
                synchronizeInitialViewport(
                    isCurrent = { !f.session.isClosed },
                    resize = { complete ->
                        f.view.resizeSessionToVisibleGrid {
                            failures += it
                            complete(it)
                        }
                        if (++attempts == 1) firstAttempt.complete(Unit) else retry.complete(Unit)
                    },
                    onComplete = {
                        completions++
                        completed.complete(it)
                    },
                )
            }
            firstAttempt.get(10, TimeUnit.SECONDS)
            onEdt {
                f.view.size = f.view.preferredGridSize(13, 3)
                f.view.componentListeners.forEach { it.componentResized(ComponentEvent(f.view, ComponentEvent.COMPONENT_RESIZED)) }
                assertIs<CancellationException>(failures.single())
                assertFalse(completed.isDone, "Supersession must not complete or abort startup")
            }
            retry.get(10, TimeUnit.SECONDS)
            f.releaseAdmission()
            assertNull(completed.get(10, TimeUnit.SECONDS))
            f.session.readRenderFrame { frame ->
                assertEquals(13, frame.columns)
                assertEquals(3, frame.rows)
            }
            onEdt {
                assertEquals(2, attempts)
                assertEquals(1, completions)
                assertNull(failures.last())
            }
        }
    }

    @Test
    fun `full mutation queue reports startup failure instead of stranding completion`() {
        Fixture().use { f ->
            val completed = CompletableFuture<Throwable?>()
            var completions = 0
            onEdt {
                // The fixture's policy barrier already owns one of the 64 outstanding slots.
                repeat(63) { f.view.setHostPolicy(HostPolicy()) }
                synchronizeInitialViewport(
                    isCurrent = { !f.session.isClosed },
                    resize = f.view::resizeSessionToVisibleGrid,
                    onComplete = {
                        completions++
                        completed.complete(it)
                    },
                )
            }
            val failure = assertIs<IllegalStateException>(completed.get(10, TimeUnit.SECONDS))
            assertEquals("Terminal view mutation queue is full", failure.message)
            onEdt {
                f.view.dispose()
                assertEquals(1, completions)
            }
        }
    }

    @Test
    fun `closing the session before a queued retry cancels startup without another resize`() {
        Fixture().use { f ->
            val submitted = CompletableFuture<Unit>()
            val completed = CompletableFuture<Throwable?>()
            var attempts = 0
            var completions = 0
            onEdt {
                synchronizeInitialViewport(
                    isCurrent = { !f.session.isClosed },
                    resize = { complete ->
                        attempts++
                        f.view.resizeSessionToVisibleGrid(complete)
                        submitted.complete(Unit)
                    },
                    onComplete = {
                        completions++
                        completed.complete(it)
                    },
                )
            }
            submitted.get(10, TimeUnit.SECONDS)
            onEdt {
                // The second request synchronously supersedes the first, scheduling its retry.
                f.view.resizeSessionToVisibleGrid()
                f.session.requestClose()
                f.view.dispose()
            }
            assertIs<CancellationException>(completed.get(10, TimeUnit.SECONDS))
            onEdt {
                assertEquals(1, attempts)
                assertEquals(1, completions)
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `completion after the pane closes cancels startup without a retry`(resizeSucceeded: Boolean) {
        val completed = CompletableFuture<Throwable?>()
        val submitted = CompletableFuture<(Throwable?) -> Unit>()
        var current = true
        var attempts = 0
        onEdt {
            synchronizeInitialViewport(
                isCurrent = { current },
                resize = {
                    attempts++
                    submitted.complete(it)
                },
                onComplete = completed::complete,
            )
        }
        val complete = submitted.get(10, TimeUnit.SECONDS)
        val cancellation = if (resizeSucceeded) null else CancellationException("Pane closed")
        onEdt {
            current = false
            complete(cancellation)
        }
        val failure = assertIs<CancellationException>(completed.get(10, TimeUnit.SECONDS))
        if (cancellation != null) assertSame(cancellation, failure)
        onEdt { assertEquals(1, attempts) }
    }

    private class Fixture : AutoCloseable {
        private val initialized = CompletableFuture<Throwable?>()
        private val release = CountDownLatch(1)
        private val entered = CountDownLatch(1)
        val session =
            TerminalSession.create(
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
            )
        val view =
            onEdt {
                SwingTerminal(
                    settingsProvider = {
                        SwingSettings.create {
                            it.columns = 10
                            it.rows = 2
                            it.padding = SwingPadding()
                            it.alternateScreenPadding = SwingPadding()
                            it.shellIntegrationDecorationGutterWidth = 0
                            it.cursorBlinkMillis = 0
                        }
                    },
                ).apply {
                    size = preferredGridSize(10, 2)
                    bind(session) { initialized.complete(it) }
                }
            }
        private val gate =
            FutureTask {
                session.withMutationAdmission({ true }) {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS)) { "Admission gate was not released" }
                }
            }

        init {
            assertNull(initialized.get(10, TimeUnit.SECONDS))
            val drained = CompletableFuture<Throwable?>()
            onEdt { view.setHostPolicy(HostPolicy()) { drained.complete(it) } }
            assertNull(drained.get(10, TimeUnit.SECONDS))
            Thread
                .ofPlatform()
                .daemon()
                .name("initial-viewport-admission")
                .start(gate)
            check(entered.await(10, TimeUnit.SECONDS)) { "Admission gate was not entered" }
            // Keep the worker on a non-coalescing policy request. Subsequent geometry is
            // definitely pending even if the worker has already dequeued that policy.
            onEdt { view.setHostPolicy(HostPolicy()) }
        }

        fun releaseAdmission() {
            release.countDown()
            gate.get(10, TimeUnit.SECONDS)
        }

        override fun close() {
            try {
                onEdt { view.dispose() }
            } finally {
                releaseAdmission()
                session.close()
            }
        }
    }

    private companion object {
        fun <T> onEdt(action: () -> T): T {
            val task = FutureTask(action)
            SwingUtilities.invokeLater(task)
            return task.get(10, TimeUnit.SECONDS)
        }
    }
}

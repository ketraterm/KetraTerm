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
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.transport.TerminalConnector
import io.github.ketraterm.transport.TerminalConnectorListener
import io.github.ketraterm.ui.swing.settings.SwingSettings
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities

class SwingTerminalHyperlinkCleanupTest {
    @Test
    @Timeout(20)
    fun `unbind cancels discovery even when provider cleanup throws`() {
        checkUnbind(throwOnCleanup = true)
    }

    @Test
    @Timeout(20)
    fun `unbind cancels discovery when provider cleanup succeeds`() {
        checkUnbind(throwOnCleanup = false)
    }

    private fun checkUnbind(throwOnCleanup: Boolean) {
        val subscribed = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val failCleanup = AtomicBoolean(false)
        val expected = IllegalStateException("Provider cleanup failed")
        val detector =
            object : SwingHyperlinkDetector {
                override val configurationChanges =
                    flow<Unit> {
                        subscribed.countDown()
                        try {
                            awaitCancellation()
                        } finally {
                            cancelled.countDown()
                        }
                    }

                override suspend fun detect(request: SwingHyperlinkDetectionRequest): List<SwingHyperlink> = emptyList()

                override fun discardOrderedState() {
                    if (failCleanup.get()) throw expected
                }
            }
        val connector =
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
            }
        val session = TerminalSession.create(TerminalBuffers.create(40, 2), connector)
        val view =
            onEdt {
                SwingTerminal(
                    settingsProvider = {
                        SwingSettings.create {
                            it.columns = 40
                            it.rows = 2
                            it.cursorBlinkMillis = 0
                            it.smartSuggestionsEnabled = false
                        }
                    },
                    hostServices = SwingHostServices.create { it.hyperlinkDetector = detector },
                ).apply { size = preferredGridSize(40, 2) }
            }
        try {
            val bound = CountDownLatch(1)
            val bindFailure = AtomicReference<Throwable?>()
            onEdt {
                view.bind(session) {
                    bindFailure.set(it)
                    bound.countDown()
                }
            }
            assertTrue(bound.await(5, TimeUnit.SECONDS), "Binding must finish")
            bindFailure.get()?.let { throw AssertionError("Binding failed", it) }
            val output = "https://example.com\r\n".toByteArray()
            session.onBytes(output, 0, output.size)
            session.requestRender(0)
            assertTrue(subscribed.await(5, TimeUnit.SECONDS), "Discovery must collect configuration changes")

            failCleanup.set(throwOnCleanup)
            onEdt {
                if (throwOnCleanup) {
                    assertSame(expected, assertThrows(IllegalStateException::class.java) { view.unbind() })
                } else {
                    view.unbind()
                }
            }
            assertTrue(cancelled.await(3, TimeUnit.SECONDS), "Unbinding must cancel the owned configuration collector")
        } finally {
            failCleanup.set(false)
            session.use { onEdt { view.dispose() } }
        }
    }

    private fun <T> onEdt(action: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return action()
        val task = FutureTask { action() }
        SwingUtilities.invokeLater(task)
        return task.get(5, TimeUnit.SECONDS)
    }
}

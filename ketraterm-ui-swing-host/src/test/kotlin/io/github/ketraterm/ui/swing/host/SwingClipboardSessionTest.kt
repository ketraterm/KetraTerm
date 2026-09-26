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
import io.github.ketraterm.host.*
import io.github.ketraterm.session.TerminalClipboardReader
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.testkit.MockConnector
import io.github.ketraterm.ui.swing.settings.TerminalClipboardHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class SwingClipboardSessionTest {
    @Test
    fun startupWriteAndReadInOneChunkObservePublishedPaneAndEarlierPostedWrite() =
        runTest {
            Fixture(this, TerminalClipboardPermission.ALLOW).use { f ->
                // Standalone pane creation completes before the EDT processes posted writes or reads.
                SwingUtilities.invokeAndWait {
                    f.connector.feedFromHost("\u001b]52;c;bmV3\u0007\u001b]52;c;?\u0007".toByteArray())
                    f.attachPane()
                }
                completeRead()
                assertEquals("\u001b]52;c;bmV3\u001b\\", f.output())
                assertEquals(1, f.reads)
            }
        }

    @Test
    fun askRequiresDecisionBeforeNativeReadAndPreservesExactText() =
        runTest {
            Fixture(this, TerminalClipboardPermission.PROMPT).use { f ->
                SwingUtilities.invokeAndWait { f.attachPane() }
                f.text = "\u00e9\r\n\u001b\u0000"
                f.query()
                runCurrent()
                SwingUtilities.invokeAndWait {
                    assertTrue(f.prompt.component.isVisible)
                    assertEquals(0, f.reads)
                    assertEquals("", f.output())
                    f.prompt.click("Allow once")
                }
                completeRead()
                assertEquals("\u001b]52;c;w6kNChsA\u001b\\", f.output())
                assertEquals(1, f.reads)
            }
        }

    @Test
    fun expiryDismissesConsentAndLateApprovalCannotRead() =
        runTest {
            Fixture(this, TerminalClipboardPermission.PROMPT).use { f ->
                SwingUtilities.invokeAndWait { f.attachPane() }
                f.query()
                runCurrent()
                SwingUtilities.invokeAndWait { assertTrue(f.prompt.component.isVisible) }
                advanceTimeBy(8_000.milliseconds)
                runCurrent()
                SwingUtilities.invokeAndWait {
                    assertFalse(f.prompt.component.isVisible)
                    f.prompt.click("Allow once")
                }
                runCurrent()
                assertEquals("\u001b]52;c;\u001b\\", f.output())
                assertEquals(0, f.reads)
            }
        }

    @Test
    fun revocationAfterApprovalBeforeNativeDispatchCannotBeUndoneByReallow() =
        runTest {
            Fixture(this, TerminalClipboardPermission.PROMPT).use { f ->
                SwingUtilities.invokeAndWait { f.attachPane() }
                f.query()
                runCurrent()
                SwingUtilities.invokeAndWait { f.prompt.click("Allow once") }
                SwingUtilities.invokeAndWait { assertFalse(f.prompt.component.isVisible) }
                f.session.setHostPolicy(
                    f.policy.copy(clipboardPolicy = f.policy.clipboardPolicy.copy(readPermission = TerminalClipboardPermission.DENY)),
                )
                f.session.setHostPolicy(
                    f.policy.copy(clipboardPolicy = f.policy.clipboardPolicy.copy(readPermission = TerminalClipboardPermission.ALLOW)),
                )
                completeRead()
                assertEquals(0, f.reads)
                assertEquals("", f.output())
            }
        }

    @Test
    fun sessionCloseDismissesConsentWithoutAReplyOrNativeAccess() =
        runTest {
            Fixture(this, TerminalClipboardPermission.PROMPT).use { f ->
                SwingUtilities.invokeAndWait { f.attachPane() }
                f.query()
                runCurrent()
                SwingUtilities.invokeAndWait { assertTrue(f.prompt.component.isVisible) }
                f.session.close()
                completeRead()
                SwingUtilities.invokeAndWait { assertFalse(f.prompt.component.isVisible) }
                assertEquals(0, f.reads)
                assertEquals("", f.output())
            }
        }

    private fun TestScope.completeRead() {
        // Drain each actual dispatcher handoff: session -> EDT -> native -> EDT -> session writer.
        runCurrent()
        SwingUtilities.invokeAndWait { }
        runCurrent()
        SwingUtilities.invokeAndWait { }
        runCurrent()
    }

    private class Fixture(
        scope: TestScope,
        permission: TerminalClipboardPermission,
    ) : AutoCloseable {
        val connector = MockConnector()
        lateinit var prompt: SwingClipboardReadPrompt
        var text = "old"
        var reads = 0
        private val clipboard =
            object : TerminalClipboardHandler {
                override fun copyText(text: String) {
                    check(SwingUtilities.isEventDispatchThread())
                    this@Fixture.text = text
                }

                override fun readText(): String {
                    check(!SwingUtilities.isEventDispatchThread())
                    reads++
                    return text
                }
            }
        private val reader = SwingClipboardReader(StandardTestDispatcher(scope.testScheduler))
        val policy =
            HostPolicy(
                clipboardPolicy =
                    TerminalClipboardPolicy(
                        readPermission = permission,
                        writePermission = TerminalClipboardPermission.ALLOW,
                    ),
            )
        val session =
            TerminalSession
                .create(
                    TerminalBuffers.create(10, 3),
                    connector,
                    hostPolicy = policy,
                    hostEvents =
                        object : HostEventSink by HostEventSink.NONE {
                            override fun terminalClipboardWrite(event: TerminalClipboardWriteEvent) {
                                SwingUtilities.invokeLater { clipboard.copyText(event.text) }
                            }
                        },
                    workerDispatcher = StandardTestDispatcher(scope.testScheduler),
                    ioDispatcher = StandardTestDispatcher(scope.testScheduler),
                    clipboardReadTimeSource = scope.testScheduler.timeSource,
                    clipboardReader =
                        TerminalClipboardReader { request ->
                            withContext(Dispatchers.Swing) { reader.read(request, prompt, "Read from this terminal?", clipboard) }
                        },
                ).also { it.start(10, 3) }

        fun attachPane() {
            prompt = SwingClipboardReadPrompt(JPanel())
        }

        fun query() {
            connector.feedFromHost("\u001b]52;c;?\u0007".toByteArray())
        }

        fun output(): String = connector.writtenBytes.toString(Charsets.US_ASCII)

        override fun close() {
            session.close()
            SwingUtilities.invokeAndWait { if (::prompt.isInitialized) prompt.close() }
        }
    }
}

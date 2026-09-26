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
package io.github.ketraterm.workspace

import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.host.*
import io.github.ketraterm.protocol.TerminalClipboardSelection
import io.github.ketraterm.pty.PtyEventListener
import io.github.ketraterm.session.TerminalClipboardReadResult
import io.github.ketraterm.session.TerminalClipboardReader
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.testkit.MockConnector
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.*
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalWorkspaceClipboardReadTest {
    @Test
    fun startupReadWaitsUntilTabOpenedReturns() =
        runTest {
            val reads = mutableListOf<TerminalWorkspaceTab>()
            var published = false
            val listener =
                object : TerminalWorkspaceListener {
                    override fun tabOpened(tab: TerminalWorkspaceTab) {
                        runCurrent()
                        assertEquals(emptyList(), reads)
                        published = true
                    }

                    override suspend fun readClipboard(
                        tab: TerminalWorkspaceTab,
                        request: TerminalClipboardReadRequest,
                    ): TerminalClipboardReadResult {
                        assertTrue(published)
                        assertEquals("pc", request.selection.value)
                        reads += tab
                        return TerminalClipboardReadResult.Text("ok")
                    }
                }
            Fixture(this, listener) { entry ->
                entry.query()
                runCurrent()
                assertEquals(emptyList(), reads)
                assertEquals("", entry.output())
            }.use { f ->
                val tab = f.open("first")
                runCurrent()
                assertEquals(listOf(tab), reads)
                assertEquals("\u001b]52;pc;b2s=\u001b\\", f.entries.single().output())
            }
        }

    @Test
    fun readTargetsItsOwningTabAfterSelectionChangesAndRejectsStaleSessions() =
        runTest {
            val reads = mutableListOf<TerminalWorkspaceTab>()
            val listener =
                object : TerminalWorkspaceListener {
                    override suspend fun readClipboard(
                        tab: TerminalWorkspaceTab,
                        request: TerminalClipboardReadRequest,
                    ): TerminalClipboardReadResult {
                        reads += tab
                        return TerminalClipboardReadResult.Text(tab.profile.displayName)
                    }
                }
            Fixture(this, listener).use { f ->
                val first = f.open("first")
                val second = f.open("second")
                assertSame(second, f.workspace.selectedTab())
                val entry = f.entries.first()
                entry.query()
                runCurrent()
                assertEquals(listOf(first), reads)
                assertEquals("\u001b]52;pc;Zmlyc3Q=\u001b\\", entry.output())
                assertEquals("", f.entries.last().output())

                val request =
                    TerminalClipboardReadRequest(
                        checkNotNull(TerminalClipboardSelection.parse("c")),
                        TerminalClipboardPermission.ALLOW,
                        1024,
                    )
                assertSame(TerminalClipboardReadResult.Unavailable, entry.events.readClipboard(second.session, request))
                f.workspace.closeTab(first.id)
                assertSame(TerminalClipboardReadResult.Unavailable, entry.events.readClipboard(first.session, request))
                assertEquals(listOf(first), reads)
            }
        }

    @Test
    fun startupWaitingKeepsTheOriginalDeadlineAndCannotReviveAfterCancellation() =
        runTest {
            for (change in listOf("timeout", "close", "deny")) {
                var reads = 0
                val listener =
                    object : TerminalWorkspaceListener {
                        override suspend fun readClipboard(
                            tab: TerminalWorkspaceTab,
                            request: TerminalClipboardReadRequest,
                        ): TerminalClipboardReadResult {
                            reads++
                            return TerminalClipboardReadResult.Text("must-not-escape")
                        }
                    }
                Fixture(this, listener) { entry ->
                    entry.query()
                    runCurrent()
                    assertEquals(0, reads)
                    when (change) {
                        "timeout" -> advanceTimeBy(8000.milliseconds)
                        "close" -> entry.session.close()
                        "deny" -> {
                            entry.session.setHostPolicy(HostPolicy())
                            entry.session.setHostPolicy(ALLOW_READS)
                        }
                    }
                    runCurrent()
                }.use { f ->
                    f.open("early")
                    runCurrent()
                    assertEquals(0, reads, change)
                    assertEquals(if (change == "timeout") "\u001b]52;pc;\u001b\\" else "", f.entries.single().output(), change)
                }
            }
        }

    @Test
    fun closingATabCancelsItsSuspendedHostRead() =
        runTest {
            var entered = false
            var cancelled = false
            val listener =
                object : TerminalWorkspaceListener {
                    override suspend fun readClipboard(
                        tab: TerminalWorkspaceTab,
                        request: TerminalClipboardReadRequest,
                    ): TerminalClipboardReadResult {
                        entered = true
                        try {
                            awaitCancellation()
                        } finally {
                            cancelled = true
                        }
                    }
                }
            Fixture(this, listener).use { f ->
                val tab = f.open("pending")
                val entry = f.entries.single()
                entry.query()
                runCurrent()
                assertTrue(entered)
                f.workspace.closeTab(tab.id)
                runCurrent()
                assertTrue(cancelled)
                assertEquals("", entry.output())
                assertEquals(listOf(TerminalClipboardReadOutcome.CANCELLED), entry.outcomes)
            }
        }

    @Test
    fun failedLaunchOrPublicationCancelsStartupWaiting() =
        runTest {
            for (stage in listOf("launch", "publication")) {
                var reads = 0
                val listener =
                    object : TerminalWorkspaceListener {
                        override fun tabOpened(tab: TerminalWorkspaceTab) {
                            if (stage == "publication") error("publication failed")
                        }

                        override suspend fun readClipboard(
                            tab: TerminalWorkspaceTab,
                            request: TerminalClipboardReadRequest,
                        ): TerminalClipboardReadResult {
                            reads++
                            return TerminalClipboardReadResult.Text("must-not-escape")
                        }
                    }
                Fixture(this, listener) { entry ->
                    entry.query()
                    runCurrent()
                    if (stage == "launch") error("launch failed")
                }.use { f ->
                    assertFailsWith<IllegalStateException> { f.open("failed") }
                    runCurrent()
                    assertEquals(0, reads)
                    assertEquals("", f.entries.single().output())
                    assertEquals(listOf(TerminalClipboardReadOutcome.CANCELLED), f.entries.single().outcomes)
                }
            }
        }

    @Test
    fun absentHostProviderReturnsAnEmptyReply() =
        runTest {
            Fixture(this, TerminalWorkspaceListener.NONE).use { f ->
                f.open("unavailable")
                val entry = f.entries.single()
                entry.query()
                runCurrent()
                assertEquals("\u001b]52;pc;\u001b\\", entry.output())
            }
        }

    private class Fixture(
        scope: TestScope,
        listener: TerminalWorkspaceListener,
        beforePublication: (Entry) -> Unit = {},
    ) : AutoCloseable {
        val entries = mutableListOf<Entry>()
        private val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val workspace =
            TerminalWorkspace(
                listener,
                sessionFactory = { _, options, events ->
                    val connector = MockConnector()
                    val outcomes = mutableListOf<TerminalClipboardReadOutcome>()
                    var attached: TerminalSession? = null
                    val session =
                        TerminalSession.create(
                            TerminalBuffers.create(10, 3),
                            connector,
                            hostPolicy = options.hostPolicy,
                            hostEvents =
                                object : HostEventSink by HostEventSink.NONE {
                                    override fun terminalClipboardReadCompleted(event: TerminalClipboardReadAuditEvent) {
                                        outcomes += event.outcome
                                    }
                                },
                            clipboardReader = TerminalClipboardReader { events.readClipboard(checkNotNull(attached), it) },
                            workerDispatcher = dispatcher,
                            ioDispatcher = dispatcher,
                            clipboardReadTimeSource = scope.testScheduler.timeSource,
                        )
                    attached = session
                    val entry = Entry(session, connector, events, outcomes)
                    entries += entry
                    session.start(10, 3)
                    beforePublication(entry)
                    session
                },
                workerDispatcher = dispatcher,
            )

        fun open(name: String): TerminalWorkspaceTab =
            workspace.openTab(
                TerminalProfile(name, name, listOf("mock")),
                TerminalWorkspaceOpenOptions(10, 3, false, 0, hostPolicy = ALLOW_READS),
            )

        override fun close() {
            workspace.close()
            entries.forEach { it.session.close() }
        }
    }

    private class Entry(
        val session: TerminalSession,
        val connector: MockConnector,
        val events: PtyEventListener,
        val outcomes: List<TerminalClipboardReadOutcome>,
    ) {
        fun query() = connector.feedFromHost("\u001b]52;ppcc;?\u0007".toByteArray())

        fun output(): String = connector.writtenBytes.toString(Charsets.US_ASCII)
    }

    private companion object {
        val ALLOW_READS = HostPolicy(clipboardPolicy = TerminalClipboardPolicy(readPermission = TerminalClipboardPermission.ALLOW))
    }
}

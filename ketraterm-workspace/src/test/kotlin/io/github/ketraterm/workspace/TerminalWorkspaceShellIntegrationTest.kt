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
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.session.TerminalShellIntegrationCommandMetadata
import io.github.ketraterm.session.TerminalShellIntegrationFactory
import io.github.ketraterm.session.TerminalShellIntegrationState
import io.github.ketraterm.shell.integration.OscShellIntegration
import io.github.ketraterm.testkit.MockConnector
import io.github.ketraterm.transport.TerminalConnector
import io.github.ketraterm.transport.TerminalConnectorListener
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalWorkspaceShellIntegrationTest {
    @Test
    fun `host completions retain each exact command before revision observation starts`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val model = TerminalShellIntegrationState()
            val session =
                TerminalSession.create(
                    TerminalBuffers.create(80, 24),
                    MockConnector(),
                    workerDispatcher = dispatcher,
                    ioDispatcher = dispatcher,
                    shellIntegration = TerminalShellIntegrationFactory.host(model),
                )
            val completed = mutableListOf<Pair<TerminalWorkspaceTab, TerminalShellIntegrationCommandMetadata>>()
            TerminalWorkspace(
                listener =
                    object : TerminalWorkspaceListener {
                        override fun commandFinished(
                            tab: TerminalWorkspaceTab,
                            metadata: TerminalShellIntegrationCommandMetadata,
                        ) {
                            completed += tab to metadata
                        }
                    },
                sessionFactory = { _, _, _ -> session },
                workerDispatcher = dispatcher,
            ).use { workspace ->
                val tab = workspace.openTab(profile, options)
                model.recordCommandStart(1, true, "first", "file:///first")
                model.recordCommandFinished(2, 0)
                model.recordCommandStart(3, true, "second", "file:///second")
                model.recordCommandFinished(4, 7)
                model.recordCommandFinished(4, 7)

                assertEquals(listOf("first", "second"), completed.map { it.second.commandText })
                assertEquals(listOf(0, 7), completed.map { it.second.exitCode })
                assertEquals(listOf("file:///first", "file:///second"), completed.map { it.second.workingDirectoryUri })
                assertTrue(completed.all { it.first === tab })
                assertNotEquals(completed[0].second.recordId, completed[1].second.recordId)
                runCurrent()
                assertEquals(2, completed.size)

                workspace.closeTab(tab.id)
                model.recordCommandStart(5, true, "after close")
                model.recordCommandFinished(6, 0)
                runCurrent()
                assertEquals(2, completed.size)
            }
        }

    @Test
    fun `remote closure stops host metadata before and after workspace cleanup runs`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val connector = MockConnector()
            val model = TerminalShellIntegrationState()
            val session =
                TerminalSession.create(
                    TerminalBuffers.create(80, 24),
                    connector,
                    workerDispatcher = dispatcher,
                    ioDispatcher = dispatcher,
                    shellIntegration = TerminalShellIntegrationFactory.host(model),
                )
            val completed = mutableListOf<String?>()
            val directories = mutableListOf<String>()
            TerminalWorkspace(
                listener =
                    object : TerminalWorkspaceListener {
                        override fun commandFinished(
                            tab: TerminalWorkspaceTab,
                            metadata: TerminalShellIntegrationCommandMetadata,
                        ) {
                            completed += metadata.commandText
                        }

                        override fun currentWorkingDirectoryChanged(
                            tab: TerminalWorkspaceTab,
                            uri: String,
                        ) {
                            directories += uri
                        }
                    },
                sessionFactory = { _, _, _ -> session },
                workerDispatcher = dispatcher,
            ).use { workspace ->
                val tab = workspace.openTab(profile, options)
                model.recordCurrentWorkingDirectory("file:///before")
                runCurrent()
                assertEquals(listOf("file:///before"), directories)

                connector.simulateClosed(0)
                model.recordCommandStart(1, true, "after exit")
                model.recordCommandFinished(2, 0)
                model.recordCurrentWorkingDirectory("file:///after")
                runCurrent()
                model.recordCommandStart(3, true, "after cleanup")
                model.recordCommandFinished(4, 0)
                runCurrent()

                assertTrue(completed.isEmpty())
                assertEquals(listOf("file:///before"), directories)
                assertEquals("file:///before", tab.currentWorkingDirectoryUri)
                assertEquals("file:///after", model.currentWorkingDirectoryUri(), "The host keeps owning its model after session close")
            }
        }

    @Test
    fun `tab publication failure detaches semantic completion observation`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val model = TerminalShellIntegrationState()
            val session =
                TerminalSession.create(
                    TerminalBuffers.create(80, 24),
                    MockConnector(),
                    workerDispatcher = dispatcher,
                    ioDispatcher = dispatcher,
                    shellIntegration = TerminalShellIntegrationFactory.host(model),
                )
            val failure = IllegalStateException("publication failed")
            var completed = 0
            TerminalWorkspace(
                listener =
                    object : TerminalWorkspaceListener {
                        override fun tabOpened(tab: TerminalWorkspaceTab): Unit = throw failure

                        override fun commandFinished(
                            tab: TerminalWorkspaceTab,
                            metadata: TerminalShellIntegrationCommandMetadata,
                        ) {
                            completed++
                        }
                    },
                sessionFactory = { _, _, _ -> session },
                workerDispatcher = dispatcher,
            ).use { workspace ->
                assertSame(failure, assertFailsWith<IllegalStateException> { workspace.openTab(profile, options) })
                model.recordCommandStart(1, true, "after failure")
                model.recordCommandFinished(2, 0)
                runCurrent()
                assertEquals(0, completed)
                assertTrue(workspace.tabSnapshot().isEmpty())
                assertTrue(session.isClosed)
            }
        }

    @Test
    fun `OSC command completion during connector startup reaches the published tab exactly once`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val backingConnector = MockConnector()
            val connector =
                object : TerminalConnector by backingConnector {
                    override fun start(listener: TerminalConnectorListener) {
                        backingConnector.start(listener)
                        backingConnector.feedFromHost(
                            "\u001B]133;A\u0007$ \u001B]133;B\u0007echo ready\r\n\u001B]133;C\u0007ready\r\n\u001B]133;D;0\u0007"
                                .encodeToByteArray(),
                        )
                    }
                }
            val session =
                TerminalSession.create(
                    TerminalBuffers.create(80, 24),
                    connector,
                    workerDispatcher = dispatcher,
                    ioDispatcher = dispatcher,
                    shellIntegration = OscShellIntegration,
                )
            val events = mutableListOf<String>()
            TerminalWorkspace(
                listener =
                    object : TerminalWorkspaceListener {
                        override fun tabOpened(tab: TerminalWorkspaceTab) {
                            events += "opened:${tab.id}"
                        }

                        override fun commandFinished(
                            tab: TerminalWorkspaceTab,
                            metadata: TerminalShellIntegrationCommandMetadata,
                        ) {
                            events += "finished:${tab.id}:${metadata.commandText}:${metadata.exitCode}"
                        }
                    },
                sessionFactory = { _, _, _ -> session },
                workerDispatcher = dispatcher,
            ).use { workspace ->
                val tab = workspace.openTab(profile, options)
                assertEquals(listOf("opened:${tab.id}", "finished:${tab.id}:echo ready:0"), events)
                runCurrent()
                assertEquals(2, events.size)
            }
        }

    @Test
    fun `directory output followed by immediate process exit is retained before workspace workers run`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val backingConnector = MockConnector()
            val connector =
                object : TerminalConnector by backingConnector {
                    override fun start(listener: TerminalConnectorListener) {
                        backingConnector.start(listener)
                        backingConnector.feedFromHost("\u001B]7;file:///first\u0007\u001B]7;file:///final\u0007".encodeToByteArray())
                        backingConnector.simulateClosed(0)
                    }
                }
            val session =
                TerminalSession.create(
                    TerminalBuffers.create(80, 24),
                    connector,
                    workerDispatcher = dispatcher,
                    ioDispatcher = dispatcher,
                    shellIntegration = OscShellIntegration,
                )
            val directories = mutableListOf<String>()
            TerminalWorkspace(
                listener =
                    object : TerminalWorkspaceListener {
                        override fun currentWorkingDirectoryChanged(
                            tab: TerminalWorkspaceTab,
                            uri: String,
                        ) {
                            assertEquals(uri, tab.currentWorkingDirectoryUri)
                            directories += uri
                        }
                    },
                sessionFactory = { _, _, _ -> session },
                workerDispatcher = dispatcher,
            ).use { workspace ->
                val tab = workspace.openTab(profile, options)
                assertTrue(session.isClosed)
                assertEquals(listOf("file:///first", "file:///final"), directories)
                assertEquals("file:///final", tab.currentWorkingDirectoryUri)
                assertEquals("final", tab.title)
                runCurrent()
                assertEquals(listOf("file:///first", "file:///final"), directories)
            }
        }

    @Test
    fun `directory observer failure does not stop command completion or remote close observation`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val connector = MockConnector()
            val model = TerminalShellIntegrationState()
            val session =
                TerminalSession.create(
                    TerminalBuffers.create(80, 24),
                    connector,
                    workerDispatcher = dispatcher,
                    ioDispatcher = dispatcher,
                    shellIntegration = TerminalShellIntegrationFactory.host(model),
                )
            val events = mutableListOf<String>()
            TerminalWorkspace(
                listener =
                    object : TerminalWorkspaceListener {
                        override fun currentWorkingDirectoryChanged(
                            tab: TerminalWorkspaceTab,
                            uri: String,
                        ) {
                            events += "directory:$uri"
                            error("Directory observer failed")
                        }

                        override fun commandFinished(
                            tab: TerminalWorkspaceTab,
                            metadata: TerminalShellIntegrationCommandMetadata,
                        ) {
                            events += "command:${metadata.commandText}"
                        }

                        override fun sessionClosed(
                            tab: TerminalWorkspaceTab,
                            exitCode: Int?,
                            failure: Throwable?,
                        ) {
                            assertNull(failure)
                            events += "closed:$exitCode"
                        }
                    },
                sessionFactory = { _, _, _ -> session },
                workerDispatcher = dispatcher,
            ).use { workspace ->
                val tab = workspace.openTab(profile, options)
                model.recordCurrentWorkingDirectory("file:///workspace")
                assertEquals("file:///workspace", tab.currentWorkingDirectoryUri)
                model.recordCommandStart(1, true, "build")
                model.recordCommandFinished(2, 0)
                connector.simulateClosed(5)
                runCurrent()

                assertEquals(listOf("directory:file:///workspace", "command:build", "closed:5"), events)
            }
        }

    @Test
    fun `preexisting host directory is published after the tab and before transport startup`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val model = TerminalShellIntegrationState().apply { recordCurrentWorkingDirectory("file:///initial") }
            val events = mutableListOf<String>()
            val connector =
                object : TerminalConnector by MockConnector() {
                    override fun start(listener: TerminalConnectorListener) {
                        events += "transport"
                    }
                }
            val session =
                TerminalSession.create(
                    TerminalBuffers.create(80, 24),
                    connector,
                    workerDispatcher = dispatcher,
                    ioDispatcher = dispatcher,
                    shellIntegration = TerminalShellIntegrationFactory.host(model),
                )
            TerminalWorkspace(
                listener =
                    object : TerminalWorkspaceListener {
                        override fun tabOpened(tab: TerminalWorkspaceTab) {
                            events += "tab"
                        }

                        override fun currentWorkingDirectoryChanged(
                            tab: TerminalWorkspaceTab,
                            uri: String,
                        ) {
                            events += "directory:$uri"
                        }
                    },
                sessionFactory = { _, _, _ -> session },
                workerDispatcher = dispatcher,
            ).use { workspace ->
                val tab = workspace.openTab(profile, options)
                assertEquals(listOf("tab", "directory:file:///initial", "transport"), events)
                assertEquals("initial", tab.title)
            }
        }

    private val profile = TerminalProfile("host", "Host shell", listOf("unused-shell"))
    private val options =
        TerminalWorkspaceOpenOptions.create { draft ->
            draft.columns = 80
            draft.rows = 24
            draft.treatAmbiguousAsWide = false
            draft.maxHistory = 100
        }
}

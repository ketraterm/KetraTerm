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
import io.github.ketraterm.input.api.TerminalInputEncoder
import io.github.ketraterm.input.event.TerminalFocusEvent
import io.github.ketraterm.input.event.TerminalKeyEvent
import io.github.ketraterm.input.event.TerminalMouseEvent
import io.github.ketraterm.input.event.TerminalPasteEvent
import io.github.ketraterm.input.policy.PasteControlPolicy
import io.github.ketraterm.parser.api.TerminalOutputParser
import io.github.ketraterm.protocol.NotificationLevel
import io.github.ketraterm.protocol.ShellIntegrationEvent
import io.github.ketraterm.protocol.ShellIntegrationMarker
import io.github.ketraterm.pty.PtyEventListener
import io.github.ketraterm.render.api.TerminalColorPalette
import io.github.ketraterm.render.api.TerminalRenderFrameReader
import io.github.ketraterm.render.cache.TerminalRenderPublisher
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.session.TerminalStartupCommand
import io.github.ketraterm.transport.TerminalConnector
import io.github.ketraterm.transport.TerminalConnectorListener
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalWorkspaceTest {
    @Test
    fun `metadata callbacks target the attached tab and stop after removal`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            testSession(dispatcher = dispatcher).use { session ->
                var ptyListener: PtyEventListener? = null
                val delivered = mutableListOf<Pair<TerminalWorkspaceTab, String>>()
                val palette = TerminalColorPalette(isDark = false)
                val listener =
                    object : TerminalWorkspaceListener {
                        override fun paletteChanged(
                            tab: TerminalWorkspaceTab,
                            palette: TerminalColorPalette,
                        ) {
                            assertEquals(false, palette.isDark)
                            delivered += tab to "palette"
                        }

                        override fun hyperlinkRegistered(
                            tab: TerminalWorkspaceTab,
                            hyperlinkId: Int,
                            uri: String,
                            id: String?,
                        ) {
                            delivered += tab to "registered:$hyperlinkId:$uri:$id"
                        }

                        override fun hyperlinkRemoved(
                            tab: TerminalWorkspaceTab,
                            hyperlinkId: Int,
                        ) {
                            delivered += tab to "removed:$hyperlinkId"
                        }

                        override fun hyperlinksCleared(tab: TerminalWorkspaceTab) {
                            delivered += tab to "cleared"
                        }

                        override fun showNotification(
                            tab: TerminalWorkspaceTab,
                            title: String,
                            body: String,
                            level: NotificationLevel,
                        ) {
                            delivered += tab to "notification:$title:$body:$level"
                        }
                    }
                TerminalWorkspace(
                    listener,
                    { _, _, events ->
                        ptyListener = events
                        session
                    },
                    workerDispatcher = dispatcher,
                ).use { workspace ->
                    val tab =
                        workspace.openTab(
                            TerminalProfile("p", "Profile", listOf("mock")),
                            TerminalWorkspaceOpenOptions(80, 24, false, 100),
                        )
                    val events = requireNotNull(ptyListener)

                    fun emit() {
                        events.paletteChanged(session, palette)
                        events.hyperlinkRegistered(session, 1, "https://a", "key")
                        events.hyperlinkRemoved(session, 1)
                        events.hyperlinksCleared(session)
                        repeat(2) { events.showNotification(session, "title", "body", NotificationLevel.INFO) }
                    }
                    emit()
                    assertEquals(
                        listOf(
                            "palette",
                            "registered:1:https://a:key",
                            "removed:1",
                            "cleared",
                            "notification:title:body:INFO",
                            "notification:title:body:INFO",
                        ).map { tab to it },
                        delivered,
                    )
                    workspace.closeTab(tab.id)
                    emit()
                    assertEquals(6, delivered.size)
                }
            }
        }

    @Test
    fun `workspace tracks connector metadata applies live toggles and clears on remote exit`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val titles = mutableListOf<String>()
            val connector = RecordingConnector(foregroundName = "vim")
            val session = testSession(connector, dispatcher)
            session.start(80, 24)
            TerminalWorkspace(
                listener =
                    object : TerminalWorkspaceListener {
                        override fun titleChanged(
                            tab: TerminalWorkspaceTab,
                            title: String,
                        ) {
                            titles += title
                        }
                    },
                sessionFactory = { _, _, _ -> session },
                workerDispatcher = dispatcher,
            ).use { workspace ->
                val tab =
                    workspace.openTab(
                        TerminalProfile("p1", "Profile", listOf("mock-shell")),
                        TerminalWorkspaceOpenOptions(80, 24, false, 100, showForegroundProcessName = false),
                    )
                assertEquals("Profile", tab.title)
                runCurrent()
                assertEquals(0, connector.foregroundReads)
                tab.showForegroundProcessName = true
                runCurrent()
                assertEquals("vim", tab.title)
                assertEquals(listOf("vim"), titles)
                tab.showForegroundProcessName = false
                assertEquals("Profile", tab.title)
                runCurrent()
                val readsWhileDisabled = connector.foregroundReads
                advanceTimeBy(5_000.milliseconds)
                runCurrent()
                assertEquals(readsWhileDisabled, connector.foregroundReads)
                assertEquals(listOf("vim", "Profile"), titles)
                tab.showForegroundProcessName = true
                runCurrent()
                assertEquals("vim", tab.title)
                assertEquals(listOf("vim", "Profile", "vim"), titles)
                connector.simulateClosed(0)
                runCurrent()
                assertEquals(listOf("vim", "Profile", "vim", "Profile"), titles)
                assertTrue(session.isClosed)
                assertEquals("Profile", tab.title)
                val readsAtExit = connector.foregroundReads
                advanceTimeBy(5_000.milliseconds)
                runCurrent()
                assertEquals(readsAtExit, connector.foregroundReads)
            }
            runCurrent()
        }

    @Test
    fun `rapid process title toggle restores retained metadata without waiting for another process change`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val connector = RecordingConnector(foregroundName = "vim")
            val session = testSession(connector, dispatcher)
            session.start(80, 24)
            TerminalWorkspace(
                listener = TerminalWorkspaceListener.NONE,
                sessionFactory = { _, _, _ -> session },
                workerDispatcher = dispatcher,
            ).use { workspace ->
                val tab =
                    workspace.openTab(
                        TerminalProfile("p1", "Profile", listOf("mock-shell")),
                        TerminalWorkspaceOpenOptions(80, 24, false, 100),
                    )
                runCurrent()
                assertEquals("vim", tab.title)
                assertEquals(1, connector.foregroundReads)
                tab.showForegroundProcessName = false
                assertEquals("Profile", tab.title)
                tab.showForegroundProcessName = true
                runCurrent()
                assertEquals("vim", tab.title)
                assertEquals(1, connector.foregroundReads)
                advanceTimeBy(1_000.milliseconds)
                runCurrent()
                assertEquals("vim", tab.title)
            }
            runCurrent()
        }

    @Test
    fun `process titles preserve custom application and directory precedence`() {
        testSession().use { session ->
            val titles = mutableListOf<String>()
            val tab =
                TerminalWorkspaceTab(
                    "t1",
                    TerminalProfile("p1", "Profile", listOf("mock-shell")),
                    "Profile",
                    session,
                    { _, _ -> },
                    { _, title -> titles += title },
                    { _, _ -> },
                )
            tab.updateForegroundProcessName("mock-shell")
            assertEquals("Profile", tab.title)
            tab.updateCurrentWorkingDirectoryUri("file:///work/project")
            tab.updateForegroundProcessName("vim")
            assertEquals("vim", tab.title)
            tab.updateForegroundProcessName("vim")
            assertEquals(listOf("project", "vim"), titles)
            tab.updateDynamicTitle("Editor")
            tab.updateForegroundProcessName("git")
            assertEquals("Editor", tab.title)
            tab.customTitle = "Mine"
            tab.updateDynamicTitle("Build")
            tab.updateForegroundProcessName(null)
            assertEquals("Mine", tab.title)
            tab.customTitle = null
            assertEquals("Build", tab.title)
            tab.updateDynamicTitle("mock-shell")
            assertEquals("project", tab.title)
            tab.updateForegroundProcessName("git")
            assertEquals("git", tab.title)
            tab.updateForegroundProcessName(null)
            assertEquals("project", tab.title)
            assertEquals(listOf("project", "vim", "Editor", "Mine", "Build", "project", "git", "project"), titles)
        }
    }

    @Test
    fun `process titles are sanitized bounded and ignored when disabled or closed`() {
        testSession().use { session ->
            val tab =
                TerminalWorkspaceTab(
                    "t1",
                    TerminalProfile("p1", "Profile", listOf("mock-shell")),
                    "Profile",
                    session,
                    { _, _ -> },
                    { _, _ -> },
                    { _, _ -> },
                )
            tab.updateForegroundProcessName("  vi\u001b\u202Em  ")
            assertEquals("vim", tab.title)
            tab.updateForegroundProcessName("x".repeat(300))
            assertEquals("x".repeat(256), tab.title)
            tab.showForegroundProcessName = false
            assertEquals("Profile", tab.title)
            tab.updateForegroundProcessName("git")
            assertEquals("Profile", tab.title)
            tab.showForegroundProcessName = true
            assertEquals("Profile", tab.title)
            tab.updateForegroundProcessName("git")
            assertEquals("git", tab.title)
            session.close()
            tab.updateForegroundProcessName("stale")
            assertEquals("Profile", tab.title)
        }
    }

    @Test
    fun `column mode requests require acceptance from the owning tab host`() {
        val session = testSession()
        lateinit var events: PtyEventListener
        var accept = false
        var requestedTab: TerminalWorkspaceTab? = null
        TerminalWorkspace(
            listener =
                object : TerminalWorkspaceListener {
                    override fun requestColumnMode(
                        tab: TerminalWorkspaceTab,
                        rows: Int,
                        columns: Int,
                    ): Boolean {
                        requestedTab = tab
                        assertEquals(24, rows)
                        assertEquals(132, columns)
                        return accept
                    }
                },
            sessionFactory =
                { _, _, listener ->
                    events = listener
                    session
                },
        ).use { workspace ->
            val tab =
                workspace.openTab(
                    TerminalProfile("test", "Test", listOf("unused-shell")),
                    TerminalWorkspaceOpenOptions(80, 24, false, 100),
                )
            assertFalse(events.requestColumnMode(session, 24, 132))
            assertSame(tab, requestedTab)
            accept = true
            assertTrue(events.requestColumnMode(session, 24, 132))
            workspace.closeTab(tab.id)
            assertFalse(events.requestColumnMode(session, 24, 132))
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `startup cancellation is delivered once with its owning tab even before observer starts`() =
        runTest {
            val session =
                TerminalSession.create(
                    terminal = TerminalBuffers.create(80, 24),
                    connector = RecordingConnector(),
                    startupCommand = TerminalStartupCommand("echo ready"),
                    workerDispatcher = StandardTestDispatcher(testScheduler),
                )
            session.start(80, 24)
            val cancellations = mutableListOf<String>()
            TerminalWorkspace(
                listener =
                    object : TerminalWorkspaceListener {
                        override fun startupCommandCancelled(tab: TerminalWorkspaceTab) {
                            cancellations += tab.id
                        }
                    },
                sessionFactory = { _, _, _ -> session },
                workerDispatcher = StandardTestDispatcher(testScheduler),
            ).use { workspace ->
                val tab =
                    workspace.openTab(
                        TerminalProfile("test", "Test", listOf("unused-shell")),
                        TerminalWorkspaceOpenOptions(80, 24, false, 100),
                    )
                session.encodePaste(TerminalPasteEvent("user command"))
                runCurrent()
                assertEquals(listOf(tab.id), cancellations)
                session.encodePaste(TerminalPasteEvent("more input"))
                runCurrent()
                assertEquals(listOf(tab.id), cancellations)
            }
            runCurrent()
        }

    @Test
    fun testTabTitleRenamingAndPrecedence() {
        val session = testSession()

        var titleChangedCount = 0
        var lastTitle: String? = null

        val tab =
            TerminalWorkspaceTab(
                id = "t1",
                profile = TerminalProfile("p1", "Profile 1", listOf("mock-shell")),
                title = "Initial Dynamic",
                session = session,
                onColorChanged = { _, _ -> },
                onTitleChanged = { _, t ->
                    titleChangedCount++
                    lastTitle = t
                },
                onCurrentWorkingDirectoryChanged = { _, _ -> },
            )

        // 1. Initial state
        assertEquals("Initial Dynamic", tab.title)
        assertNull(tab.customTitle)

        // 2. Setting custom title takes precedence and fires callback
        tab.customTitle = "My Custom Name"
        assertEquals("My Custom Name", tab.title)
        assertEquals("My Custom Name", lastTitle)
        assertEquals(1, titleChangedCount)

        // 3. Dynamic title changes while custom title is set: should NOT fire callback or change visible title
        tab.updateDynamicTitle("PTY Title 1")
        assertEquals("My Custom Name", tab.title)
        assertEquals("My Custom Name", lastTitle)
        assertEquals(1, titleChangedCount)

        // 4. Clearing custom title reverts back to the dynamic title and fires callback
        tab.customTitle = null
        assertEquals("PTY Title 1", tab.title)
        assertEquals("PTY Title 1", lastTitle)
        assertEquals(2, titleChangedCount)

        // 5. Dynamic title changes while custom title is null: should update title and fire callback
        tab.updateDynamicTitle("PTY Title 2")
        assertEquals("PTY Title 2", tab.title)
        assertEquals("PTY Title 2", lastTitle)
        assertEquals(3, titleChangedCount)
    }

    @Test
    fun `current working directory is stored forwarded coalesced and used as title fallback`() {
        var capturedEventListener: PtyEventListener? = null
        val session = testSession()
        val directoryEvents = mutableListOf<Pair<String, String>>()
        val titleEvents = mutableListOf<String>()
        val workspace =
            TerminalWorkspace(
                listener =
                    object : TerminalWorkspaceListener {
                        override fun currentWorkingDirectoryChanged(
                            tab: TerminalWorkspaceTab,
                            uri: String,
                        ) {
                            assertEquals(uri, tab.currentWorkingDirectoryUri)
                            directoryEvents += tab.id to uri
                        }

                        override fun titleChanged(
                            tab: TerminalWorkspaceTab,
                            title: String,
                        ) {
                            titleEvents += title
                        }
                    },
                sessionFactory =
                    { _, _, eventListener ->
                        capturedEventListener = eventListener
                        session
                    },
            )
        val tab =
            workspace.openTab(
                profile = TerminalProfile("p1", "Profile 1", listOf("mock-shell")),
                options = TerminalWorkspaceOpenOptions(80, 24, false, 100),
            )
        val eventListener = requireNotNull(capturedEventListener)

        eventListener.currentWorkingDirectoryChanged(session, "file:///home/user/My%20Project")
        eventListener.currentWorkingDirectoryChanged(session, "file:///home/user/My%20Project")

        assertEquals("file:///home/user/My%20Project", tab.currentWorkingDirectoryUri)
        assertEquals("My Project", tab.title)
        assertEquals(listOf(tab.id to "file:///home/user/My%20Project"), directoryEvents)
        assertEquals(listOf("My Project"), titleEvents)

        eventListener.windowTitleChanged(session, "Build")
        eventListener.currentWorkingDirectoryChanged(session, "file:///home/user/Other")
        assertEquals("Build", tab.title)

        eventListener.windowTitleChanged(session, "")
        assertEquals("Other", tab.title)
    }

    @Test
    fun `workspace open options carry paste sanitization profile default`() {
        val session = testSession()
        var capturedOptions: TerminalWorkspaceOpenOptions? = null
        val workspace =
            TerminalWorkspace(
                listener = TerminalWorkspaceListener.NONE,
                sessionFactory =
                    { _, options, _ ->
                        capturedOptions = options
                        session
                    },
            )

        workspace.openTab(
            profile = TerminalProfile("p1", "Profile 1", listOf("mock-shell")),
            options =
                TerminalWorkspaceOpenOptions(
                    columns = 80,
                    rows = 24,
                    treatAmbiguousAsWide = false,
                    maxHistory = 100,
                    pasteControlPolicy = PasteControlPolicy.STRIP_C0_EXCEPT_TAB_CR_LF,
                ),
        )

        assertEquals(
            PasteControlPolicy.STRIP_C0_EXCEPT_TAB_CR_LF,
            capturedOptions?.pasteControlPolicy,
        )
    }

    @Test
    fun `launch executable window title is ignored so directory fallback remains visible`() {
        val tab =
            TerminalWorkspaceTab(
                id = "t1",
                profile =
                    TerminalProfile(
                        id = "windows-powershell",
                        displayName = "Windows PowerShell",
                        command = listOf("C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe", "-NoLogo"),
                    ),
                title = "Windows PowerShell",
                session = testSession(),
                onColorChanged = { _, _ -> },
                onTitleChanged = { _, _ -> },
                onCurrentWorkingDirectoryChanged = { _, _ -> },
            )

        tab.updateCurrentWorkingDirectoryUri("file:///C:/Users/gagik")
        tab.updateDynamicTitle("C:\\WINDOWS\\System32\\WindowsPowerShell\\v1.0\\powershell.exe")

        assertEquals("gagik", tab.title)
    }

    @Test
    fun `application window title still overrides directory fallback`() {
        val tab =
            TerminalWorkspaceTab(
                id = "t1",
                profile =
                    TerminalProfile(
                        id = "windows-powershell",
                        displayName = "Windows PowerShell",
                        command = listOf("powershell.exe", "-NoLogo"),
                    ),
                title = "Windows PowerShell",
                session = testSession(),
                onColorChanged = { _, _ -> },
                onTitleChanged = { _, _ -> },
                onCurrentWorkingDirectoryChanged = { _, _ -> },
            )

        tab.updateCurrentWorkingDirectoryUri("file:///C:/Users/gagik")
        tab.updateDynamicTitle("nvim")

        assertEquals("nvim", tab.title)
    }

    @Test
    fun `directory title fallback strips encoded control and format characters`() {
        val tab =
            TerminalWorkspaceTab(
                id = "t1",
                profile = TerminalProfile("p1", "Profile 1", listOf("mock-shell")),
                title = "Profile 1",
                session = testSession(),
                onColorChanged = { _, _ -> },
                onTitleChanged = { _, _ -> },
                onCurrentWorkingDirectoryChanged = { _, _ -> },
            )

        tab.updateCurrentWorkingDirectoryUri("file:///tmp/safe%1B%0A%E2%80%AEname")

        assertEquals("safename", tab.title)
        assertTrue(tab.title.none(Char::isISOControl))
    }

    @Test
    fun testShellIntegrationMarkerIsForwardedWithOwningTab() {
        var capturedEventListener: PtyEventListener? = null
        val session = testSession()
        val markerEvents = mutableListOf<Pair<String, ShellIntegrationEvent>>()
        val workspace =
            TerminalWorkspace(
                listener =
                    object : TerminalWorkspaceListener {
                        override fun shellIntegrationMarker(
                            tab: TerminalWorkspaceTab,
                            event: ShellIntegrationEvent,
                        ) {
                            markerEvents += tab.id to event
                        }
                    },
                sessionFactory =
                    { _, _, eventListener ->
                        capturedEventListener = eventListener
                        session
                    },
            )
        val tab =
            workspace.openTab(
                profile = TerminalProfile("p1", "Profile 1", listOf("mock-shell")),
                options =
                    TerminalWorkspaceOpenOptions(
                        columns = 80,
                        rows = 24,
                        treatAmbiguousAsWide = false,
                        maxHistory = 100,
                    ),
            )
        val event = ShellIntegrationEvent(ShellIntegrationMarker.COMMAND_FINISHED, exitCode = 2)

        capturedEventListener!!.shellIntegrationMarker(session, event)

        assertEquals(listOf(tab.id to event), markerEvents)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `remote session close is forwarded with owning tab and exit code`() =
        runTest {
            val connector = RecordingConnector()
            val session = testSession(connector, StandardTestDispatcher(testScheduler))
            session.start(columns = 80, rows = 24)
            val closeEvents = mutableListOf<Triple<String, Int?, Throwable?>>()
            val workspace =
                TerminalWorkspace(
                    listener =
                        object : TerminalWorkspaceListener {
                            override fun sessionClosed(
                                tab: TerminalWorkspaceTab,
                                exitCode: Int?,
                                failure: Throwable?,
                            ) {
                                closeEvents += Triple(tab.id, exitCode, failure)
                            }
                        },
                    sessionFactory = { _, _, _ -> session },
                    workerDispatcher = StandardTestDispatcher(testScheduler),
                )
            val tab =
                workspace.openTab(
                    profile = TerminalProfile("p1", "Profile 1", listOf("mock-shell")),
                    options = TerminalWorkspaceOpenOptions(80, 24, false, 100),
                )

            connector.simulateClosed(1)
            runCurrent()

            assertEquals(listOf(Triple<String, Int?, Throwable?>(tab.id, 1, null)), closeEvents)
            workspace.close()
        }

    @Test
    fun `local workspace close is not forwarded as remote session close`() {
        val session = testSession(RecordingConnector())
        session.start(columns = 80, rows = 24)
        val closeEvents = mutableListOf<String>()
        val workspace =
            TerminalWorkspace(
                listener =
                    object : TerminalWorkspaceListener {
                        override fun sessionClosed(
                            tab: TerminalWorkspaceTab,
                            exitCode: Int?,
                            failure: Throwable?,
                        ) {
                            closeEvents += tab.id
                        }
                    },
                sessionFactory = { _, _, _ -> session },
            )
        val tab =
            workspace.openTab(
                profile = TerminalProfile("p1", "Profile 1", listOf("mock-shell")),
                options = TerminalWorkspaceOpenOptions(80, 24, false, 100),
            )
        assertTrue(workspace.isCoroutineScopeActive)
        assertEquals(1, workspace.sessionCollectionCount)

        workspace.closeTab(tab.id)

        assertEquals(emptyList<String>(), closeEvents)
        assertEquals(0, workspace.sessionCollectionCount)
        workspace.close()
        assertFalse(workspace.isCoroutineScopeActive)
    }

    @Test
    fun `allowed clipboard write is forwarded with owning tab`() {
        var capturedEventListener: PtyEventListener? = null
        val session = testSession()
        val clipboardEvents = mutableListOf<Pair<String, TerminalClipboardWriteEvent>>()
        val workspace =
            TerminalWorkspace(
                listener =
                    object : TerminalWorkspaceListener {
                        override fun terminalClipboardWrite(
                            tab: TerminalWorkspaceTab,
                            event: TerminalClipboardWriteEvent,
                        ) {
                            clipboardEvents += tab.id to event
                        }
                    },
                sessionFactory =
                        { _, _, eventListener ->
                        capturedEventListener = eventListener
                        session
                    },
            )
        val tab =
            workspace.openTab(
                profile = TerminalProfile("p1", "Profile 1", listOf("mock-shell")),
                options =
                    TerminalWorkspaceOpenOptions(
                        columns = 80,
                        rows = 24,
                        treatAmbiguousAsWide = false,
                        maxHistory = 100,
                    ),
            )
        val event = testClipboardWriteEvent("copied")

        capturedEventListener!!.terminalClipboardWrite(session, event)

        assertEquals(listOf(tab.id to event), clipboardEvents)
    }

    @Test
    fun `clipboard prompt is forwarded with owning tab`() {
        var capturedEventListener: PtyEventListener? = null
        val session = testSession()
        val clipboardEvents = mutableListOf<Pair<String, TerminalClipboardPromptEvent>>()
        val workspace =
            TerminalWorkspace(
                listener =
                    object : TerminalWorkspaceListener {
                        override fun terminalClipboardPrompt(
                            tab: TerminalWorkspaceTab,
                            event: TerminalClipboardPromptEvent,
                        ) {
                            clipboardEvents += tab.id to event
                        }
                    },
                sessionFactory =
                    { _, _, eventListener ->
                        capturedEventListener = eventListener
                        session
                    },
            )
        val tab =
            workspace.openTab(
                profile = TerminalProfile("p1", "Profile 1", listOf("mock-shell")),
                options =
                    TerminalWorkspaceOpenOptions(
                        columns = 80,
                        rows = 24,
                        treatAmbiguousAsWide = false,
                        maxHistory = 100,
                    ),
            )
        val event = testClipboardPromptEvent("prompted")

        capturedEventListener!!.terminalClipboardPrompt(session, event)

        assertEquals(listOf(tab.id to event), clipboardEvents)
    }

    private fun testClipboardWriteEvent(text: String): TerminalClipboardWriteEvent =
        TerminalClipboardWriteEvent(
            selection = "c",
            text = text,
            audit =
                TerminalClipboardAuditEvent(
                    operation = TerminalClipboardOperation.WRITE,
                    selection = "c",
                    origin = TerminalClipboardOrigin.LOCAL,
                    encodedLength = 8,
                    decodedBytes = text.encodeToByteArray().size,
                    maxDecodedBytes = 1024,
                    decision = TerminalClipboardDecision.ALLOWED_BY_POLICY,
                ),
        )

    private fun testClipboardPromptEvent(text: String): TerminalClipboardPromptEvent =
        TerminalClipboardPromptEvent(
            selection = "c",
            text = text,
            audit =
                TerminalClipboardAuditEvent(
                    operation = TerminalClipboardOperation.WRITE,
                    selection = "c",
                    origin = TerminalClipboardOrigin.LOCAL,
                    encodedLength = 8,
                    decodedBytes = text.encodeToByteArray().size,
                    maxDecodedBytes = 1024,
                    decision = TerminalClipboardDecision.PROMPT_REQUIRED,
                ),
        )

    private fun testSession(
        connector: TerminalConnector = NoOpConnector,
        dispatcher: CoroutineDispatcher = StandardTestDispatcher(),
    ): TerminalSession {
        val terminal = TerminalBuffers.create(width = 80, height = 24, maxHistory = 100)
        return TerminalSession(
            terminal = terminal,
            renderPublisher = TerminalRenderPublisher(80, 24),
            renderReader = terminal as TerminalRenderFrameReader,
            responseReader = terminal,
            connector = connector,
            parser = NoOpParser,
            inputEncoder = NoOpInputEncoder,
            workerDispatcher = dispatcher,
            ioDispatcher = dispatcher,
        )
    }

    private object NoOpConnector : TerminalConnector {
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

    private class RecordingConnector(
        private val foregroundName: String? = null,
    ) : TerminalConnector {
        private var listener: TerminalConnectorListener? = null
        var foregroundReads = 0
            private set

        override fun foregroundProcessName(): String? {
            foregroundReads++
            return foregroundName
        }

        override fun start(listener: TerminalConnectorListener) {
            this.listener = listener
        }

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

        fun simulateClosed(exitCode: Int?) {
            checkNotNull(listener).onClosed(exitCode)
        }
    }

    private object NoOpParser : TerminalOutputParser {
        override fun accept(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) = Unit

        override fun acceptByte(byteValue: Int) = Unit

        override fun endOfInput() = Unit

        override fun reset() = Unit
    }

    private object NoOpInputEncoder : TerminalInputEncoder {
        override fun encodeKey(event: TerminalKeyEvent) = Unit

        override fun encodePaste(event: TerminalPasteEvent) = Unit

        override fun encodeFocus(event: TerminalFocusEvent) = Unit

        override fun encodeMouse(event: TerminalMouseEvent) = Unit
    }
}

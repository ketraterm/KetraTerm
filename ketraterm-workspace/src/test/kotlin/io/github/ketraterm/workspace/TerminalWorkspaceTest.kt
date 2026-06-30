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
import io.github.ketraterm.host.TerminalClipboardAuditEvent
import io.github.ketraterm.host.TerminalClipboardDecision
import io.github.ketraterm.host.TerminalClipboardOperation
import io.github.ketraterm.host.TerminalClipboardOrigin
import io.github.ketraterm.host.TerminalClipboardPromptEvent
import io.github.ketraterm.host.TerminalClipboardWriteEvent
import io.github.ketraterm.input.api.TerminalInputEncoder
import io.github.ketraterm.input.event.TerminalFocusEvent
import io.github.ketraterm.input.event.TerminalKeyEvent
import io.github.ketraterm.input.event.TerminalMouseEvent
import io.github.ketraterm.input.event.TerminalPasteEvent
import io.github.ketraterm.input.policy.PasteSanitizationPolicy
import io.github.ketraterm.parser.api.TerminalOutputParser
import io.github.ketraterm.protocol.ShellIntegrationEvent
import io.github.ketraterm.protocol.ShellIntegrationMarker
import io.github.ketraterm.pty.PtyEventListener
import io.github.ketraterm.render.api.TerminalRenderFrameReader
import io.github.ketraterm.render.cache.TerminalRenderPublisher
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.transport.TerminalConnector
import io.github.ketraterm.transport.TerminalConnectorListener
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TerminalWorkspaceTest {
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
                    TerminalWorkspaceSessionFactory { _, _, eventListener ->
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
                    TerminalWorkspaceSessionFactory { _, options, _ ->
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
                    pasteSanitizationPolicy = PasteSanitizationPolicy.STRIP_C0_EXCEPT_TAB_CR_LF,
                ),
        )

        assertEquals(
            PasteSanitizationPolicy.STRIP_C0_EXCEPT_TAB_CR_LF,
            capturedOptions?.pasteSanitizationPolicy,
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
                    TerminalWorkspaceSessionFactory { _, _, eventListener ->
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
    fun `remote session close is forwarded with owning tab and exit code`() {
        val connector = RecordingConnector()
        val session = testSession(connector)
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
                sessionFactory = TerminalWorkspaceSessionFactory { _, _, _ -> session },
            )
        val tab =
            workspace.openTab(
                profile = TerminalProfile("p1", "Profile 1", listOf("mock-shell")),
                options = TerminalWorkspaceOpenOptions(80, 24, false, 100),
            )

        connector.simulateClosed(1)

        assertEquals(listOf(Triple<String, Int?, Throwable?>(tab.id, 1, null)), closeEvents)
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
                sessionFactory = TerminalWorkspaceSessionFactory { _, _, _ -> session },
            )
        val tab =
            workspace.openTab(
                profile = TerminalProfile("p1", "Profile 1", listOf("mock-shell")),
                options = TerminalWorkspaceOpenOptions(80, 24, false, 100),
            )

        workspace.closeTab(tab.id)

        assertEquals(emptyList<String>(), closeEvents)
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
                    TerminalWorkspaceSessionFactory { _, _, eventListener ->
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
                    TerminalWorkspaceSessionFactory { _, _, eventListener ->
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

    private fun testSession(connector: TerminalConnector = NoOpConnector): TerminalSession {
        val terminal = TerminalBuffers.create(width = 80, height = 24, maxHistory = 100)
        return TerminalSession(
            terminal = terminal,
            publisher = TerminalRenderPublisher(80, 24),
            renderReader = terminal as TerminalRenderFrameReader,
            responseReader = terminal,
            connector = connector,
            parser = NoOpParser,
            inputEncoder = NoOpInputEncoder,
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

    private class RecordingConnector : TerminalConnector {
        private var listener: TerminalConnectorListener? = null

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

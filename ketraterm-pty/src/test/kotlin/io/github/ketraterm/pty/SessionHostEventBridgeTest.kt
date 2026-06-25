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
package io.github.ketraterm.pty

import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.host.TerminalClipboardAuditEvent
import io.github.ketraterm.host.TerminalClipboardDecision
import io.github.ketraterm.host.TerminalClipboardOperation
import io.github.ketraterm.host.TerminalClipboardOrigin
import io.github.ketraterm.host.TerminalClipboardPromptEvent
import io.github.ketraterm.host.TerminalClipboardWriteEvent
import io.github.ketraterm.protocol.NotificationLevel
import io.github.ketraterm.protocol.ShellIntegrationEvent
import io.github.ketraterm.protocol.ShellIntegrationMarker
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.transport.TerminalConnector
import io.github.ketraterm.transport.TerminalConnectorListener
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class SessionHostEventBridgeTest {
    @Test
    fun `attach twice fails`() {
        val bridge = SessionHostEventBridge(RecordingListener())
        val session = testSession()

        bridge.attach(session)

        assertThrows(IllegalStateException::class.java) {
            bridge.attach(session)
        }
    }

    @Test
    fun `events before attach fail predictably`() {
        val bridge = SessionHostEventBridge(RecordingListener())

        assertThrows(IllegalStateException::class.java) { bridge.bell() }
        assertThrows(IllegalStateException::class.java) { bridge.iconTitleChanged("icon") }
        assertThrows(IllegalStateException::class.java) { bridge.windowTitleChanged("window") }
        assertThrows(IllegalStateException::class.java) { bridge.currentWorkingDirectoryChanged("file:///workspace") }
        assertThrows(IllegalStateException::class.java) {
            bridge.shellIntegrationMarker(ShellIntegrationEvent(ShellIntegrationMarker.PROMPT_START))
        }
        assertThrows(IllegalStateException::class.java) { bridge.showNotification("title", "body", NotificationLevel.INFO) }
    }

    @Test
    fun `events dispatch in order after attach`() {
        val listener = RecordingListener()
        val bridge = SessionHostEventBridge(listener)
        bridge.attach(testSession())

        bridge.bell()
        bridge.iconTitleChanged("icon")
        bridge.windowTitleChanged("window")
        bridge.currentWorkingDirectoryChanged("file:///workspace")
        bridge.resizeWindow(24, 80)
        bridge.moveWindow(100, 200)
        bridge.minimizeWindow()
        bridge.deminimizeWindow()
        bridge.raiseWindow()
        bridge.lowerWindow()
        bridge.setMaximized(true)
        bridge.setMaximized(false)
        bridge.shellIntegrationMarker(ShellIntegrationEvent(ShellIntegrationMarker.COMMAND_FINISHED, exitCode = 3))
        bridge.showNotification("title", "body", NotificationLevel.INFO)
        bridge.terminalClipboardWrite(testClipboardWriteEvent("copied"))
        bridge.terminalClipboardPrompt(testClipboardPromptEvent("prompted"))

        assertEquals(
            listOf(
                "bell",
                "icon:icon",
                "window:window",
                "currentWorkingDirectory:file:///workspace",
                "resizeWindow:24:80",
                "moveWindow:100:200",
                "minimizeWindow",
                "deminimizeWindow",
                "raiseWindow",
                "lowerWindow",
                "setMaximized:true",
                "setMaximized:false",
                "shellIntegrationMarker:COMMAND_FINISHED:3",
                "showNotification:title:body:INFO",
                "terminalClipboardWrite:c:copied",
                "terminalClipboardPrompt:c:prompted",
            ),
            listener.events,
        )
    }

    @Test
    fun `listener failures are reported and isolated`() {
        val listener =
            object : PtyEventListener by PtyEventListener.NONE {
                val failures = mutableListOf<String?>()

                override fun bell(session: TerminalSession): Unit = throw IllegalStateException("bell failed")

                override fun listenerFailed(
                    session: TerminalSession,
                    exception: Exception,
                ) {
                    failures += exception.message
                }
            }
        val bridge = SessionHostEventBridge(listener)
        bridge.attach(testSession())

        bridge.bell()

        assertEquals(listOf("bell failed"), listener.failures)
    }

    @Test
    fun `listenerFailed failures are ignored`() {
        val listener =
            object : PtyEventListener by PtyEventListener.NONE {
                override fun bell(session: TerminalSession): Unit = throw IllegalStateException("bell failed")

                override fun listenerFailed(
                    session: TerminalSession,
                    exception: Exception,
                ): Unit = throw IllegalStateException("listenerFailed failed")
            }
        val bridge = SessionHostEventBridge(listener)
        bridge.attach(testSession())

        bridge.bell()
    }

    private fun testSession(): TerminalSession {
        val terminal = TerminalBuffers.create(width = 5, height = 2)
        return TerminalSession.create(terminal = terminal, connector = NoopConnector)
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

    private class RecordingListener : PtyEventListener {
        val events = mutableListOf<String>()

        override fun bell(session: TerminalSession) {
            events += "bell"
        }

        override fun iconTitleChanged(
            session: TerminalSession,
            title: String,
        ) {
            events += "icon:$title"
        }

        override fun windowTitleChanged(
            session: TerminalSession,
            title: String,
        ) {
            events += "window:$title"
        }

        override fun currentWorkingDirectoryChanged(
            session: TerminalSession,
            uri: String,
        ) {
            events += "currentWorkingDirectory:$uri"
        }

        override fun resizeWindow(
            session: TerminalSession,
            rows: Int,
            columns: Int,
        ) {
            events += "resizeWindow:$rows:$columns"
        }

        override fun moveWindow(
            session: TerminalSession,
            x: Int,
            y: Int,
        ) {
            events += "moveWindow:$x:$y"
        }

        override fun minimizeWindow(session: TerminalSession) {
            events += "minimizeWindow"
        }

        override fun deminimizeWindow(session: TerminalSession) {
            events += "deminimizeWindow"
        }

        override fun raiseWindow(session: TerminalSession) {
            events += "raiseWindow"
        }

        override fun lowerWindow(session: TerminalSession) {
            events += "lowerWindow"
        }

        override fun setMaximized(
            session: TerminalSession,
            maximize: Boolean,
        ) {
            events += "setMaximized:$maximize"
        }

        override fun shellIntegrationMarker(
            session: TerminalSession,
            event: ShellIntegrationEvent,
        ) {
            events += "shellIntegrationMarker:${event.marker.name}:${event.exitCode ?: "null"}"
        }

        override fun showNotification(
            session: TerminalSession,
            title: String,
            body: String,
            level: NotificationLevel,
        ) {
            events += "showNotification:$title:$body:${level.name}"
        }

        override fun terminalClipboardWrite(
            session: TerminalSession,
            event: TerminalClipboardWriteEvent,
        ) {
            events += "terminalClipboardWrite:${event.selection}:${event.text}"
        }

        override fun terminalClipboardPrompt(
            session: TerminalSession,
            event: TerminalClipboardPromptEvent,
        ) {
            events += "terminalClipboardPrompt:${event.selection}:${event.text}"
        }

        override fun listenerFailed(
            session: TerminalSession,
            exception: Exception,
        ) = Unit
    }

    private object NoopConnector : TerminalConnector {
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
}

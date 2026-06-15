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
package io.github.jvterm.pty

import io.github.jvterm.core.TerminalBuffers
import io.github.jvterm.protocol.NotificationLevel
import io.github.jvterm.session.TerminalSession
import io.github.jvterm.transport.TerminalConnector
import io.github.jvterm.transport.TerminalConnectorListener
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
        bridge.resizeWindow(24, 80)
        bridge.moveWindow(100, 200)
        bridge.minimizeWindow()
        bridge.deminimizeWindow()
        bridge.raiseWindow()
        bridge.lowerWindow()
        bridge.setMaximized(true)
        bridge.setMaximized(false)
        bridge.showNotification("title", "body", NotificationLevel.INFO)

        assertEquals(
            listOf(
                "bell",
                "icon:icon",
                "window:window",
                "resizeWindow:24:80",
                "moveWindow:100:200",
                "minimizeWindow",
                "deminimizeWindow",
                "raiseWindow",
                "lowerWindow",
                "setMaximized:true",
                "setMaximized:false",
                "showNotification:title:body:INFO",
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

        override fun showNotification(
            session: TerminalSession,
            title: String,
            body: String,
            level: NotificationLevel,
        ) {
            events += "showNotification:$title:$body:${level.name}"
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

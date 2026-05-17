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
package com.gagik.terminal.pty

import com.gagik.core.TerminalBuffers
import com.gagik.terminal.session.TerminalSession
import com.gagik.terminal.transport.TerminalConnector
import com.gagik.terminal.transport.TerminalConnectorListener
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
    }

    @Test
    fun `events dispatch in order after attach`() {
        val listener = RecordingListener()
        val bridge = SessionHostEventBridge(listener)
        bridge.attach(testSession())

        bridge.bell()
        bridge.iconTitleChanged("icon")
        bridge.windowTitleChanged("window")

        assertEquals(listOf("bell", "icon:icon", "window:window"), listener.events)
    }

    @Test
    fun `listener failures are reported and isolated`() {
        val listener =
            object : TerminalPtyEventListener by TerminalPtyEventListener.NONE {
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
            object : TerminalPtyEventListener by TerminalPtyEventListener.NONE {
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

    private class RecordingListener : TerminalPtyEventListener {
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

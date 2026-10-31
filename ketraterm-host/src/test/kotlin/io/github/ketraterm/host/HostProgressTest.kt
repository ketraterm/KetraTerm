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
package io.github.ketraterm.host

import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.parser.api.TerminalParsers
import io.github.ketraterm.protocol.NotificationLevel
import io.github.ketraterm.protocol.TerminalProgressState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class HostProgressTest {
    @Test
    fun `progress is presentation metadata independent of notification window and response permissions`() {
        val f =
            Fixture(
                HostPolicy(
                    notificationPolicy = HostControlPolicy.DENY,
                    windowManipulationPolicy = HostControlPolicy.DENY,
                    terminalResponsePolicy = HostControlPolicy.DENY,
                ),
            )
        f.accept("\u001B]9;4;1;0\u0007\u001B]9;4;2;0\u0007\u001B]9;4;4;100\u0007")
        assertEquals(listOf("progress:NORMAL:0", "progress:ERROR:0", "progress:WARNING:100"), f.events)
        assertEquals(0, f.terminal.getCodepointAt(0, 0))
    }

    @Test
    fun `progress denial suppresses reports until its independent permission is restored`() {
        val f = Fixture(HostPolicy(progressPolicy = HostControlPolicy.DENY))
        f.accept("\u001B]9;4;1;50\u0007\u001B]9;4;0;0\u0007")
        assertEquals(emptyList<String>(), f.events)
        f.sink.setHostPolicy(HostPolicy())
        assertEquals(emptyList<String>(), f.events)
        f.accept("\u001B]9;4;1;25\u0007")
        f.sink.setHostPolicy(HostPolicy(progressPolicy = HostControlPolicy.DENY))
        f.accept("\u001B]9;4;2;80\u0007")
        assertEquals(listOf("progress:NORMAL:25"), f.events)
    }

    @Test
    fun `title denial preserves progress and progress denial preserves titles`() {
        val titleDenied = Fixture(HostPolicy(titlePolicy = TerminalTitlePolicy(permission = TerminalTitlePermission.DENY)))
        titleDenied.accept("\u001B]2;blocked\u0007\u001B]9;4;1;50\u0007")
        assertEquals(listOf("progress:NORMAL:50"), titleDenied.events)
        val progressDenied = Fixture(HostPolicy(progressPolicy = HostControlPolicy.DENY))
        progressDenied.accept("\u001B]2;allowed\u0007\u001B]9;4;1;50\u0007")
        assertEquals(listOf("title:allowed"), progressDenied.events)
        val copied = HostPolicy(progressPolicy = HostControlPolicy.DENY).copy(notificationPolicy = HostControlPolicy.DENY)
        assertEquals(HostControlPolicy.DENY, copied.progressPolicy)
    }

    @Test
    fun `direct progress rejects invalid percentages and normalizes ignored values`() {
        val f = Fixture()
        for (percentage in listOf(-1, 101, Int.MIN_VALUE, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { f.sink.setProgress(TerminalProgressState.NORMAL, percentage) }
        }
        f.sink.setProgress(TerminalProgressState.INDETERMINATE, 100)
        f.sink.setProgress(TerminalProgressState.HIDDEN, 50)
        assertEquals(listOf("progress:INDETERMINATE:0", "progress:HIDDEN:0"), f.events)
    }

    @Test
    fun `hard reset clears presentation even after permission denial while soft reset preserves it`() {
        val f = Fixture()
        f.accept("\u001B]9;4;1;75\u0007\u001B[!p")
        assertEquals(listOf("progress:NORMAL:75"), f.events)
        f.sink.setHostPolicy(HostPolicy(progressPolicy = HostControlPolicy.DENY))
        f.accept("\u001Bc")
        assertEquals(listOf("progress:NORMAL:75", "progress:HIDDEN:0"), f.events)
    }

    @Test
    fun `byte stream progress preserves event ordering at every split`() {
        val bytes =
            (
                "\u001B]2;build\u0007\u001B]9;4;1;50\u001B\\\u001B]9;hello\u0007" +
                    "\u001B]9;4;3;100\u0007\u001B]9;4;0\u001B\\X"
            ).encodeToByteArray()
        val expected =
            listOf(
                "title:build",
                "progress:NORMAL:50",
                "notification:hello",
                "progress:INDETERMINATE:0",
                "progress:HIDDEN:0",
            )
        for (split in 0..bytes.size) {
            val f = Fixture()
            f.parser.accept(bytes, 0, split)
            f.parser.accept(bytes, split, bytes.size - split)
            f.parser.endOfInput()
            assertEquals(expected, f.events, "split $split")
            assertEquals('X'.code, f.terminal.getCodepointAt(0, 0), "split $split")
        }
    }

    @Test
    fun `malformed progress neither notifies nor paints its payload and recovers`() {
        val f = Fixture()
        for (body in listOf("4;1;101", "4;1;999999999999999999999", "4;5;50", "4;1;50;extra", "4;1;-1", "4;1;\u00e9")) {
            f.accept("\u001B]9;$body\u0007")
        }
        f.accept("\u001B]9;4;1;100\u0007X")
        assertEquals(listOf("progress:NORMAL:100"), f.events)
        assertEquals('X'.code, f.terminal.getCodepointAt(0, 0))
    }

    private class Fixture(
        policy: HostPolicy = HostPolicy(),
    ) {
        val terminal = TerminalBuffers.create(10, 3)
        val events = mutableListOf<String>()
        val sink =
            HostCommandAdapter(
                terminal,
                object : HostEventSink by HostEventSink.NONE {
                    override fun progressChanged(
                        state: TerminalProgressState,
                        percentage: Int,
                    ) {
                        events += "progress:$state:$percentage"
                    }

                    override fun windowTitleChanged(title: String) {
                        events += "title:$title"
                    }

                    override fun showNotification(
                        title: String,
                        body: String,
                        level: NotificationLevel,
                    ) {
                        events += "notification:$body"
                    }
                },
                policy,
            )
        val parser = TerminalParsers.create(sink)

        fun accept(text: String) = parser.accept(text.encodeToByteArray())
    }
}

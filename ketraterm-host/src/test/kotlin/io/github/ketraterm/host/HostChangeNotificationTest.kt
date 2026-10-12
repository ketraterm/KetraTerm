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
import io.github.ketraterm.render.api.TerminalColorPalette
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class HostChangeNotificationTest {
    @ParameterizedTest
    @ValueSource(ints = [1, 2, 7, 256])
    fun modeDetectionAndSubscriptionsSurviveByteChunks(chunkSize: Int) {
        val f = Fixture()
        f.accept("\u001b[?2031\$p\u001b[?2048\$p\u001b[?2031h\u001b[?2048h\u001b[?2048h\u001b[?2031\$p\u001b[?2048\$p", chunkSize)
        assertEquals(
            "\u001b[?2031;2\$y\u001b[?2048;2\$y\u001b[48;3;10;0;0t\u001b[48;3;10;0;0t\u001b[?2031;1\$y\u001b[?2048;1\$y",
            f.responses(),
        )
        f.accept("\u001b[?2031l\u001b[?2048l", chunkSize)
        f.sink.setThemePalette(TerminalColorPalette(isDark = false))
        f.terminal.resize(12, 4)
        assertEquals("", f.responses())
    }

    @Test
    fun applicationColorUpdatesAndResetsDoNotSendSchemeNotifications() {
        val f = Fixture()
        f.accept("\u001b[?2031h\u001b]11;#ffffff\u0007\u001b]111\u0007\u001b]4;1;#123456\u0007\u001b]104\u0007")
        assertEquals("", f.responses())
        f.sink.setThemePalette(TerminalColorPalette(isDark = false))
        assertEquals("\u001b[?997;2n", f.responses())
        f.accept("\u001bc")
        f.sink.setThemePalette(TerminalColorPalette())
        f.terminal.resize(12, 4)
        assertEquals("", f.responses())
    }

    @Test
    fun notificationPermissionPublicationPreservesEarlierPermittedReplies() {
        val f = Fixture()
        f.accept("\u001b[?2048h")
        f.sink.setHostPolicy(HostPolicy(terminalResponsePolicy = HostControlPolicy.DENY))
        f.terminal.resize(12, 4)
        f.accept("\u001b[?2048h\u001b[?2048\$p\u001b[?9999\$p")
        assertEquals("\u001b[48;3;10;0;0t", f.responses())
        f.sink.setHostPolicy(HostPolicy(palettePolicy = HostControlPolicy.DENY))
        f.terminal.resize(12, 5, 0, 120, 100)
        assertEquals("\u001b[48;5;12;100;120t", f.responses())
    }

    private class Fixture {
        val terminal = TerminalBuffers.create(10, 3)
        val sink = HostCommandAdapter(terminal)
        private val parser = TerminalParsers.create(sink)

        fun accept(
            text: String,
            chunkSize: Int = 256,
        ) {
            val bytes = text.encodeToByteArray()
            var offset = 0
            while (offset < bytes.size) {
                val length = minOf(chunkSize, bytes.size - offset)
                parser.accept(bytes, offset, length)
                offset += length
            }
        }

        fun responses(): String {
            val bytes = ByteArray(terminal.pendingResponseBytes)
            assertEquals(bytes.size, terminal.readResponseBytes(bytes, 0, bytes.size))
            return bytes.decodeToString()
        }
    }
}

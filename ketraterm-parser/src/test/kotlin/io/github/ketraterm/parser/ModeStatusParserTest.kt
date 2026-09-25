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
package io.github.ketraterm.parser

import io.github.ketraterm.parser.ansi.RecordingTerminalCommandSink
import io.github.ketraterm.parser.api.TerminalParsers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ModeStatusParserTest {
    @Test
    fun requestsPreserveNamespacesAndExactDecimalBoundsAcrossEverySplit() {
        val cases =
            mapOf(
                "\u001B[4\$p" to "requestModeStatus:4:false",
                "\u001B[?2004\$p" to "requestModeStatus:2004:true",
                "\u001B[\$p" to "requestModeStatus:0:false",
                "\u001B[?\$p" to "requestModeStatus:0:true",
                "\u001B[?0\$p" to "requestModeStatus:0:true",
                "\u001B[?0002004\$p" to "requestModeStatus:2004:true",
                "\u001B[?2147483640\$p" to "requestModeStatus:2147483640:true",
                "\u001B[?2147483647\$p" to "requestModeStatus:2147483647:true",
            )
        for ((command, expected) in cases) {
            val bytes = command.encodeToByteArray()
            for (split in 0..bytes.size) {
                val sink = RecordingTerminalCommandSink()
                val parser = TerminalParsers.create(sink)
                parser.accept(bytes, 0, split)
                parser.accept(bytes, split, bytes.size - split)
                parser.endOfInput()
                assertEquals(listOf(expected), sink.events, "$command split $split")
            }
        }
    }

    @Test
    fun malformedCancelledAndOverflowedRequestsRecoverWithoutDispatch() {
        val invalid =
            listOf(
                "\u001B[?2004;1004\$p",
                "\u001B[?2004;\$p",
                "\u001B[?2004:1\$p",
                "\u001B[?2004:\$p",
                "\u001B[>2004\$p",
                "\u001B[?2004\$\$p",
                "\u001B[?2147483648\$p",
                "\u001B[?" + "9".repeat(80) + "\$p",
                "\u001B[?" + "1;".repeat(32) + "2004\$p",
                "\u001B[?2004\u0018",
                "\u001B[?2004\u001A",
                "\u001B[?2004;1\$y",
            )
        for (command in invalid) {
            val bytes = (command + "\u001B[?7\$p").encodeToByteArray()
            for (split in 0..bytes.size) {
                val sink = RecordingTerminalCommandSink()
                val parser = TerminalParsers.create(sink)
                parser.accept(bytes, 0, split)
                parser.accept(bytes, split, bytes.size - split)
                parser.endOfInput()
                assertEquals(listOf("requestModeStatus:7:true"), sink.events, "$command split $split")
            }
        }
    }

    @Test
    fun unfinishedRequestAtEndOfInputHasNoEffect() {
        val sink = RecordingTerminalCommandSink()
        val parser = TerminalParsers.create(sink)
        parser.accept("\u001B[?2004$".encodeToByteArray())
        parser.endOfInput()
        assertEquals(emptyList<String>(), sink.events)
    }
}

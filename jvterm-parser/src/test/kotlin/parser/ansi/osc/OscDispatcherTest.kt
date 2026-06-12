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
package com.gagik.parser.ansi.osc

import com.gagik.parser.ansi.RecordingTerminalCommandSink
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("OscDispatcher")
class OscDispatcherTest {
    private fun dispatch(
        payload: String,
        overflowed: Boolean = false,
    ): RecordingTerminalCommandSink {
        val sink = RecordingTerminalCommandSink()
        val bytes = payload.encodeToByteArray()
        OscDispatcher.dispatch(
            sink = sink,
            payload = bytes,
            length = bytes.size,
            overflowed = overflowed,
        )
        return sink
    }

    @Nested
    @DisplayName("titles")
    inner class Titles {
        @Test
        fun `OSC 0 sets icon and window title`() {
            assertEquals(
                listOf("setIconAndWindowTitle:hello"),
                dispatch("0;hello").events,
            )
        }

        @Test
        fun `OSC 1 sets icon title`() {
            assertEquals(
                listOf("setIconTitle:icon"),
                dispatch("1;icon").events,
            )
        }

        @Test
        fun `OSC 2 sets window title`() {
            assertEquals(
                listOf("setWindowTitle:window"),
                dispatch("2;window").events,
            )
        }
    }

    @Nested
    @DisplayName("hyperlinks")
    inner class Hyperlinks {
        @Test
        fun `OSC 8 with empty params starts hyperlink without id`() {
            assertEquals(
                listOf("startHyperlink:https://example.com:null"),
                dispatch("8;;https://example.com").events,
            )
        }

        @Test
        fun `OSC 8 with id param starts hyperlink with id`() {
            assertEquals(
                listOf("startHyperlink:https://example.com:abc"),
                dispatch("8;id=abc;https://example.com").events,
            )
        }

        @Test
        fun `OSC 8 with empty URI ends hyperlink`() {
            assertEquals(listOf("endHyperlink"), dispatch("8;;").events)
        }
    }

    @Nested
    @DisplayName("colors")
    inner class Colors {
        @Test
        fun `OSC 4 updates palette color with standard hex`() {
            assertEquals(
                listOf("setPaletteColor:1:-16776961"),
                dispatch("4;1;#0000ff").events,
            )
        }

        @Test
        fun `OSC 4 updates palette color with rgb colon format`() {
            assertEquals(
                listOf("setPaletteColor:2:-16711936"),
                dispatch("4;2;rgb:00/ff/00").events,
            )
        }

        @Test
        fun `OSC 4 queries palette color`() {
            assertEquals(
                listOf("queryPaletteColor:5"),
                dispatch("4;5;?").events,
            )
        }

        @Test
        fun `OSC 4 handles multiple updates and queries`() {
            assertEquals(
                listOf(
                    "setPaletteColor:1:-16776961",
                    "queryPaletteColor:2",
                    "setPaletteColor:3:-65536",
                ),
                dispatch("4;1;#0000ff;2;?;3;#ff0000").events,
            )
        }

        @Test
        fun `OSC 10 sets dynamic foreground color`() {
            assertEquals(
                listOf("setDynamicColor:10:-1"),
                dispatch("10;#ffffff").events,
            )
        }

        @Test
        fun `OSC 10 queries dynamic foreground color`() {
            assertEquals(
                listOf("queryDynamicColor:10"),
                dispatch("10;?").events,
            )
        }

        @Test
        fun `OSC 10 cascades to target 11 and 12`() {
            assertEquals(
                listOf(
                    "setDynamicColor:10:-1",
                    "setDynamicColor:11:-16777216",
                    "queryDynamicColor:12",
                ),
                dispatch("10;#ffffff;#000000;?").events,
            )
        }
    }

    @Nested
    @DisplayName("ignored payloads")
    inner class IgnoredPayloads {
        @Test
        fun `malformed command is ignored`() {
            assertTrue(dispatch("x;hello").events.isEmpty())
            assertTrue(dispatch(";hello").events.isEmpty())
            assertTrue(dispatch("hello").events.isEmpty())
            assertTrue(dispatch("999999999999999999999999;hello").events.isEmpty())
        }

        @Test
        fun `unsupported command is ignored`() {
            assertTrue(dispatch("9;ignored").events.isEmpty())
        }

        @Test
        fun `overflowed payload is ignored`() {
            assertTrue(dispatch("0;truncated", overflowed = true).events.isEmpty())
        }

        @Test
        fun `OSC 52 clipboard is ignored`() {
            assertTrue(dispatch("52;c;SGVsbG8=").events.isEmpty())
        }
    }
}

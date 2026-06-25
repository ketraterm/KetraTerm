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
package io.github.ketraterm.parser.ansi.dcs

import io.github.ketraterm.parser.ansi.RecordingTerminalCommandSink
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("DcsDispatcher")
class DcsDispatcherTest {
    private fun dispatch(
        payload: String,
        overflowed: Boolean = false,
    ): RecordingTerminalCommandSink {
        val sink = RecordingTerminalCommandSink()
        val bytes = payload.encodeToByteArray()
        DcsDispatcher.dispatch(
            sink = sink,
            payload = bytes,
            length = bytes.size,
            overflowed = overflowed,
        )
        return sink
    }

    @Test
    fun `decrqss queries route to sink`() {
        assertEquals(
            listOf("queryStatusString:m"),
            dispatch("\$qm").events,
        )
        assertEquals(
            listOf("queryStatusString:?1049"),
            dispatch("\$q?1049").events,
        )
    }

    @Test
    fun `xtgettcap queries route to sink`() {
        assertEquals(
            listOf("queryTerminfo:436f;544e"),
            dispatch("+q436f;544e").events,
        )
    }

    @Test
    fun `unrecognized dcs sequence is ignored`() {
        assertEquals(
            emptyList<String>(),
            dispatch("invalid").events,
        )
    }

    @Test
    fun `overflowed payload is discarded`() {
        assertEquals(
            emptyList<String>(),
            dispatch("\$qm", overflowed = true).events,
        )
    }

    @Test
    fun `zero-length payload is discarded`() {
        val sink = RecordingTerminalCommandSink()
        DcsDispatcher.dispatch(
            sink = sink,
            payload = ByteArray(16),
            length = 0,
            overflowed = false,
        )
        assertEquals(emptyList<String>(), sink.events)
    }

    @Test
    fun `decrqss with empty query body dispatches empty string`() {
        // DCS $ q ST — the query selector is empty
        assertEquals(
            listOf("queryStatusString:"),
            dispatch("\$q").events,
        )
    }

    @Test
    fun `xtgettcap with empty payload dispatches empty string`() {
        // DCS + q ST — no capability names
        assertEquals(
            listOf("queryTerminfo:"),
            dispatch("+q").events,
        )
    }

    @Test
    fun `single-char prefix without matching sub-protocol is ignored`() {
        // Just "$" or "+" without the second routing character
        assertEquals(emptyList<String>(), dispatch("$").events)
        assertEquals(emptyList<String>(), dispatch("+").events)
    }
}

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
import io.github.ketraterm.protocol.TerminalPointerShape
import io.github.ketraterm.render.api.TerminalRenderFrameReader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class HostPointerShapeTest {
    @Test
    fun `pointer metadata is accepted through bytes without grid mutations`() {
        val bytes = "\u001B]22;wait\u0007\u001B]22;wait\u001B\\\u001B]22;bogus\u0007".encodeToByteArray()
        for (split in 0..bytes.size) {
            val terminal = TerminalBuffers.create(4, 2)
            val adapter = HostCommandAdapter(terminal, hostPolicy = HostPolicy(windowManipulationPolicy = HostControlPolicy.DENY))
            val parser = TerminalParsers.create(adapter)
            assertNull(adapter.pointerShape)
            parser.accept("ABCD".encodeToByteArray())
            var generation = -1L
            (terminal as TerminalRenderFrameReader).readRenderFrame { generation = it.frameGeneration }
            parser.accept(bytes, 0, split)
            parser.accept(bytes, split, bytes.size - split)
            assertEquals(TerminalPointerShape.WAIT, adapter.pointerShape)
            terminal.readRenderFrame { assertEquals(generation, it.frameGeneration) }
            parser.accept("X".encodeToByteArray())
            assertEquals("ABCD", terminal.getLineAsString(0))
            assertEquals("X", terminal.getLineAsString(1))
            parser.accept("\u001B]22;\u0007".encodeToByteArray())
            assertNull(adapter.pointerShape)
        }
    }

    @Test
    fun `pointer requests and resets are independent of denied window actions and policy changes`() {
        var resizeRequests = 0
        val adapter =
            HostCommandAdapter(
                TerminalBuffers.create(4, 2),
                object : HostEventSink by HostEventSink.NONE {
                    override fun resizeWindow(
                        rows: Int,
                        columns: Int,
                    ) {
                        resizeRequests++
                    }
                },
                HostPolicy(windowManipulationPolicy = HostControlPolicy.DENY, terminalResponsePolicy = HostControlPolicy.DENY),
            )
        val parser = TerminalParsers.create(adapter)

        fun output(text: String) = parser.accept(text.encodeToByteArray())
        output("\u001B]22;wait\u0007\u001B[8;10;20t")
        assertEquals(TerminalPointerShape.WAIT, adapter.pointerShape)
        assertEquals(0, resizeRequests)
        adapter.setHostPolicy(HostPolicy())
        assertEquals(TerminalPointerShape.WAIT, adapter.pointerShape)
        output("\u001B[8;10;20t")
        assertEquals(1, resizeRequests)
        adapter.setHostPolicy(HostPolicy(windowManipulationPolicy = HostControlPolicy.DENY))
        assertEquals(TerminalPointerShape.WAIT, adapter.pointerShape)
        output("\u001B]22;text\u0007\u001B[8;10;20t")
        assertEquals(TerminalPointerShape.TEXT, adapter.pointerShape)
        assertEquals(1, resizeRequests)
        output("\u001B]22;\u0007")
        assertNull(adapter.pointerShape)
        output("\u001B]22;pointer\u0007")
        assertEquals(TerminalPointerShape.POINTER, adapter.pointerShape)
        output("\u001Bc")
        assertNull(adapter.pointerShape)
    }

    @Test
    fun `basic pointer override is global survives DECSTR and clears on RIS`() {
        val adapter = HostCommandAdapter(TerminalBuffers.create(4, 2))
        val parser = TerminalParsers.create(adapter)
        parser.accept("\u001B]22;crosshair\u0007".encodeToByteArray())
        for (sequence in listOf("\u001B[?1049h", "\u001B[!p", "\u001B[?1049l", "\u001B7\u001B8", "\u001B[?1003h")) {
            parser.accept(sequence.encodeToByteArray())
            assertEquals(TerminalPointerShape.CROSSHAIR, adapter.pointerShape, sequence)
        }
        parser.accept("\u001Bc".encodeToByteArray())
        assertNull(adapter.pointerShape)
    }
}

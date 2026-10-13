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
import io.github.ketraterm.core.api.TerminalInputState
import io.github.ketraterm.parser.api.TerminalParsers
import io.github.ketraterm.render.api.TerminalRenderFrameReader
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class HostApplicationMouseBehaviorTest {
    @Test
    fun `applications change both mouse controls without disturbing pending wrap or render generations`() {
        val bytes = "\u001B[?1007l\u001B[>1s".encodeToByteArray()
        assertAllByteSplits(bytes) { split ->
            val terminal = TerminalBuffers.create(4, 2)
            val parser = TerminalParsers.create(HostCommandAdapter(terminal))
            parser.accept("ABCD".encodeToByteArray())
            var generation = -1L
            (terminal as TerminalRenderFrameReader).readRenderFrame { generation = it.frameGeneration }
            parser.accept(bytes, 0, split)
            parser.accept(bytes, split, bytes.size - split)
            assertFalse(TerminalInputState.isAlternateScrollEnabled(terminal.getInputModeBits()))
            assertTrue(TerminalInputState.isShiftMouseCaptureEnabled(terminal.getInputModeBits()))
            terminal.readRenderFrame { assertEquals(generation, it.frameGeneration) }
            parser.accept("X".encodeToByteArray())
            assertEquals("ABCD", terminal.getLineAsString(0))
            assertEquals("X", terminal.getLineAsString(1))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["2", "3", "1:0", "1;0", "999999999999"])
    fun `invalid Shift capture request cannot disable a prior valid request`(parameter: String) {
        val bytes = ("\u001B[>1s\u001B[>" + parameter + "s").encodeToByteArray()
        assertAllByteSplits(bytes) { split ->
            val terminal = TerminalBuffers.create(4, 2)
            val parser = TerminalParsers.create(HostCommandAdapter(terminal))
            parser.accept(bytes, 0, split)
            parser.accept(bytes, split, bytes.size - split)
            assertTrue(TerminalInputState.isShiftMouseCaptureEnabled(terminal.getInputModeBits()))
            assertEquals(0, terminal.cursorCol)
            assertEquals(0, terminal.cursorRow)
        }
    }

    @Test
    fun `resize screen switches cursor save and DECSTR preserve requests while RIS clears them`() {
        val terminal = TerminalBuffers.create(4, 2)
        val parser = TerminalParsers.create(HostCommandAdapter(terminal))
        parser.accept("\u001B7\u001B[?1007l\u001B[>1s\u001B8\u001B[?1049h\u001B[!p".encodeToByteArray())
        terminal.resize(6, 3)
        parser.accept("\u001B[?1049l".encodeToByteArray())
        assertFalse(TerminalInputState.isAlternateScrollEnabled(terminal.getInputModeBits()))
        assertTrue(TerminalInputState.isShiftMouseCaptureEnabled(terminal.getInputModeBits()))
        parser.accept("\u001Bc".encodeToByteArray())
        assertTrue(TerminalInputState.isAlternateScrollEnabled(terminal.getInputModeBits()))
        assertFalse(TerminalInputState.isShiftMouseCaptureEnabled(terminal.getInputModeBits()))
        parser.accept("\u001B[>1s\u001B[>s".encodeToByteArray())
        assertFalse(TerminalInputState.isShiftMouseCaptureEnabled(terminal.getInputModeBits()))
    }

    @Test
    fun `mode status is policy gated and Shift capture does not invent a DEC mode`() {
        for (policy in HostControlPolicy.entries) {
            val terminal = TerminalBuffers.create(4, 2)
            val parser = TerminalParsers.create(HostCommandAdapter(terminal, hostPolicy = HostPolicy(terminalResponsePolicy = policy)))
            parser.accept("\u001B[?1007\$p\u001B[?1007l\u001B[>1s\u001B[?1007\$p\u001B[1007\$p".encodeToByteArray())
            val replies = ByteArray(terminal.pendingResponseBytes)
            terminal.readResponseBytes(replies)
            assertEquals(
                if (policy == HostControlPolicy.ALLOW) "\u001B[?1007;1\$y\u001B[?1007;2\$y\u001B[1007;0\$y" else "",
                replies.decodeToString(),
            )
        }
    }
}

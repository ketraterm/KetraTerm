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
package io.github.ketraterm.core.buffer

import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.core.api.TerminalBuffer
import io.github.ketraterm.protocol.TerminalHostModeCapability
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TerminalModeStatusTest {
    @Test
    fun unsupportedNamespacesAndHostCapabilitiesReturnZero() {
        val buffer = TerminalBuffers.create(10, 3)
        for (mode in listOf(0, 2, 4, 1048, 2031, Int.MAX_VALUE)) {
            buffer.requestModeStatus(mode, true)
            assertEquals("\u001B[?$mode;0\$y", drain(buffer))
        }
        for (mode in listOf(0, 1, 7, 2004, Int.MAX_VALUE)) {
            buffer.requestModeStatus(mode, false)
            assertEquals("\u001B[$mode;0\$y", drain(buffer))
        }
        buffer.setBellIsUrgent(true)
        buffer.setPopOnBell(true)
        for (mode in listOf(3, 1042, 1043)) {
            buffer.requestModeStatus(mode, true)
            assertEquals("\u001B[?$mode;0\$y", drain(buffer))
        }
        buffer.requestModeStatus(-1, true)
        assertEquals("", drain(buffer))
        buffer.requestModeStatus(1042, true, TerminalHostModeCapability.URGENT_BELL)
        buffer.requestModeStatus(1043, true, TerminalHostModeCapability.URGENT_BELL)
        assertEquals("\u001B[?1042;1\$y\u001B[?1043;0\$y", drain(buffer))
    }

    @Test
    fun columnSelectionFollowsAcceptedOperationsAndNotGeometry() {
        val buffer = TerminalBuffers.create(132, 3)
        assertColumnStatus(buffer, 2)
        buffer.executeDeccolm(132)
        assertColumnStatus(buffer, 1)
        buffer.resize(90, 3)
        assertColumnStatus(buffer, 1)
        buffer.enterAltBuffer()
        assertColumnStatus(buffer, 1)
        buffer.softReset()
        assertColumnStatus(buffer, 1)
        buffer.executeDeccolm(80)
        assertColumnStatus(buffer, 2)
        buffer.executeDeccolm(132)
        buffer.executeDeccolm(100)
        assertColumnStatus(buffer, 1)
        buffer.reset()
        assertColumnStatus(buffer, 2)
    }

    @Test
    fun backarrowUsesCurrentDefaultUntilExplicitlyOverriddenAndAfterBothResets() {
        val buffer = TerminalBuffers.create(10, 3)
        buffer.requestModeStatus(67, true, defaultBackarrowSendsBackspace = true)
        buffer.requestModeStatus(67, true, defaultBackarrowSendsBackspace = false)
        assertEquals("\u001B[?67;1\$y\u001B[?67;2\$y", drain(buffer))
        buffer.setBackarrowKeyMode(false)
        buffer.requestModeStatus(67, true, defaultBackarrowSendsBackspace = true)
        assertEquals("\u001B[?67;2\$y", drain(buffer))
        buffer.softReset()
        buffer.requestModeStatus(67, true, defaultBackarrowSendsBackspace = true)
        assertEquals("\u001B[?67;1\$y", drain(buffer))
        buffer.setBackarrowKeyMode(true)
        buffer.reset()
        buffer.requestModeStatus(67, true, defaultBackarrowSendsBackspace = false)
        assertEquals("\u001B[?67;2\$y", drain(buffer))
    }

    @Test
    fun repliesSupportPartialDrainsAndQueueGrowthWithoutTruncation() {
        val buffer = TerminalBuffers.create(10, 3)
        repeat(40) { buffer.requestModeStatus(2004, true) }
        val result = StringBuilder()
        val scratch = ByteArray(3)
        while (buffer.pendingResponseBytes > 0) {
            val count = buffer.readResponseBytes(scratch)
            result.append(scratch.decodeToString(0, count))
        }
        assertEquals("\u001B[?2004;2\$y".repeat(40), result.toString())
    }

    private fun assertColumnStatus(
        buffer: TerminalBuffer,
        expected: Int,
    ) {
        buffer.requestModeStatus(3, true, TerminalHostModeCapability.COLUMN_MODE)
        assertEquals("\u001B[?3;$expected\$y", drain(buffer))
    }

    private fun drain(buffer: TerminalBuffer): String {
        val bytes = ByteArray(buffer.pendingResponseBytes)
        buffer.readResponseBytes(bytes)
        return bytes.decodeToString()
    }
}

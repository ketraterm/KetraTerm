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
package io.github.ketraterm.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class TerminalColorRequestTest {
    @Test
    fun `batch preserves operation order duplicates and original unsigned key bytes`() {
        val batch = TerminalColorRequest(3)
        val payload = "foreground;é;foreground".encodeToByteArray()
        batch.clear(payload)
        batch.add(TerminalColorRequest.FOREGROUND, TerminalColorRequest.SET, 0xff123456.toInt(), 0, 10)
        batch.add(TerminalColorRequest.UNKNOWN, TerminalColorRequest.RESET, 0, 11, 13)
        batch.add(TerminalColorRequest.FOREGROUND, TerminalColorRequest.QUERY, 0, 14, 24)

        assertEquals(3, batch.count)
        assertEquals(TerminalColorRequest.FOREGROUND, batch.target(0))
        assertEquals(TerminalColorRequest.SET, batch.operation(0))
        assertEquals(0xff123456.toInt(), batch.color(0))
        assertEquals(10, batch.keyLength(0))
        assertEquals('f'.code, batch.keyByte(0, 0))
        assertEquals('d'.code, batch.keyByte(0, 9))
        assertEquals(TerminalColorRequest.UNKNOWN, batch.target(1))
        assertEquals(TerminalColorRequest.RESET, batch.operation(1))
        assertEquals(2, batch.keyLength(1))
        assertEquals(0xc3, batch.keyByte(1, 0))
        assertEquals(0xa9, batch.keyByte(1, 1))
        assertEquals(TerminalColorRequest.FOREGROUND, batch.target(2))
        assertEquals(TerminalColorRequest.QUERY, batch.operation(2))
        assertEquals(10, batch.keyLength(2))
    }

    @Test
    fun `clear replaces the borrowed payload and invalidates all previous operations`() {
        val batch = TerminalColorRequest(2)
        batch.clear("old".encodeToByteArray())
        batch.add(0, TerminalColorRequest.RESET, 0, 0, 3)
        batch.add(1, TerminalColorRequest.QUERY, 0, 0, 3)

        batch.clear("x".encodeToByteArray())
        assertEquals(0, batch.count)
        assertThrows(IllegalArgumentException::class.java) { batch.target(0) }
        batch.add(TerminalColorRequest.UNKNOWN, TerminalColorRequest.DYNAMIC, 0, 0, 1)
        assertEquals(1, batch.count)
        assertEquals(TerminalColorRequest.UNKNOWN, batch.target(0))
        assertEquals(TerminalColorRequest.DYNAMIC, batch.operation(0))
        assertEquals(1, batch.keyLength(0))
        assertEquals('x'.code, batch.keyByte(0, 0))
        assertThrows(IllegalArgumentException::class.java) { batch.target(1) }
    }

    @Test
    fun `invalid targets operations and borrowed ranges leave the batch unchanged`() {
        val batch = TerminalColorRequest(1)
        batch.clear("key".encodeToByteArray())
        for (target in intArrayOf(TerminalColorRequest.UNKNOWN - 1, TerminalColorRequest.CURSOR_TEXT + 1)) {
            assertThrows(IllegalArgumentException::class.java) { batch.add(target, TerminalColorRequest.RESET, 0, 0, 3) }
        }
        for (operation in intArrayOf(TerminalColorRequest.SET - 1, TerminalColorRequest.QUERY + 1)) {
            assertThrows(IllegalArgumentException::class.java) { batch.add(0, operation, 0, 0, 3) }
        }
        for ((start, end) in listOf(-1 to 1, 0 to 0, 2 to 1, 0 to 4)) {
            assertThrows(IllegalArgumentException::class.java) { batch.add(0, TerminalColorRequest.RESET, 0, start, end) }
        }
        assertEquals(0, batch.count)
        batch.add(255, TerminalColorRequest.RESET, 0, 0, 3)
        assertEquals(1, batch.count)
        assertEquals(255, batch.target(0))
    }

    @Test
    fun `capacity and access bounds are enforced independently of storage contents`() {
        assertThrows(IllegalArgumentException::class.java) { TerminalColorRequest(-1) }
        val empty = TerminalColorRequest(0)
        empty.clear("x".encodeToByteArray())
        assertEquals(0, empty.count)
        assertThrows(IllegalArgumentException::class.java) { empty.add(0, TerminalColorRequest.RESET, 0, 0, 1) }

        val batch = TerminalColorRequest(1)
        batch.clear("key".encodeToByteArray())
        batch.add(0, TerminalColorRequest.RESET, 0, 0, 3)
        assertThrows(IllegalArgumentException::class.java) { batch.add(1, TerminalColorRequest.RESET, 0, 0, 3) }
        assertEquals(1, batch.count)
        assertEquals(0, batch.target(0))
        for (index in intArrayOf(-1, 1)) {
            assertThrows(IllegalArgumentException::class.java) { batch.target(index) }
            assertThrows(IllegalArgumentException::class.java) { batch.operation(index) }
            assertThrows(IllegalArgumentException::class.java) { batch.color(index) }
            assertThrows(IllegalArgumentException::class.java) { batch.keyLength(index) }
            assertThrows(IllegalArgumentException::class.java) { batch.keyByte(index, 0) }
        }
        assertThrows(IllegalArgumentException::class.java) { batch.keyByte(0, -1) }
        assertThrows(IllegalArgumentException::class.java) { batch.keyByte(0, 3) }
    }
}

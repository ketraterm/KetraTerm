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
package io.github.ketraterm.testkit

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TerminalReplayChunkingsTest {
    @Test
    fun `bounded stream includes single every split bytewise and hostile fixed partitions`() {
        val bytes = ByteArray(16) { it.toByte() }

        val variants = TerminalReplayChunkings.exhaustive(bytes)

        assertEquals("single", variants.first().name)
        assertTrue(variants.any { it.name == "split@1" })
        assertTrue(variants.any { it.name == "split@15" })
        assertTrue(variants.any { it.name == "bytewise" })
        assertTrue(variants.any { it.name == "fixed-2" })
        assertTrue(variants.any { it.name == "fixed-3" })
        assertTrue(variants.any { it.name == "fixed-7" })
        assertTrue(variants.all { it.transcript.events.last() == TerminalReplayEvent.EndOfInput })
    }

    @Test
    fun `every two-way split reconstructs the exact logical bytes`() {
        val bytes = "\u001B[31mABC".encodeToByteArray()

        val variants = TerminalReplayChunkings.exhaustive(bytes)

        for (variant in variants) {
            val reconstructed =
                variant.transcript.events
                    .filterIsInstance<TerminalReplayEvent.Input>()
                    .flatMap { it.copyBytes().asIterable() }
                    .toByteArray()
            assertEquals(bytes.toList(), reconstructed.toList(), variant.name)
        }
    }

    @Test
    fun `end-of-input can be omitted for suffix event composition`() {
        val variants = TerminalReplayChunkings.exhaustive("ABC".encodeToByteArray(), endOfInput = false)

        assertFalse(variants.any { it.transcript.events.contains(TerminalReplayEvent.EndOfInput) })
    }
}

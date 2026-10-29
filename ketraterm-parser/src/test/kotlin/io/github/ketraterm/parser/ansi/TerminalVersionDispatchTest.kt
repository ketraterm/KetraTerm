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
package io.github.ketraterm.parser.ansi

import io.github.ketraterm.parser.fixture.TerminalParserFixture
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals

class TerminalVersionDispatchTest {
    @ParameterizedTest
    @ValueSource(strings = ["", "0", "000"])
    fun `XTVERSION accepts only omitted or zero parameter at every split`(parameter: String) {
        val wire = ("\u001b[>" + parameter + "q").encodeToByteArray()
        for (split in -1..wire.size) {
            val f = TerminalParserFixture()
            if (split < 0) {
                wire.forEach { f.acceptByte(it.toInt() and 255) }
            } else {
                f.parser.accept(wire, 0, split)
                f.parser.accept(wire, split, wire.size - split)
            }
            assertEquals(listOf("requestTerminalVersion"), f.sink.events, "split=$split")
        }
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            ">1q", ">2147483647q", ">999999999999999999999q", ">0;0q", ">;q", ">0;q",
            ">0:0q", ">0::q", ">0 q", "?0q", "=0q", "0q", ">0\u0018", ">0\u001a",
        ],
    )
    fun `unsupported malformed and cancelled version requests recover at every split`(body: String) {
        val wire = ("\u001b[" + body + "\u001b[>qX").encodeToByteArray()
        for (split in 0..wire.size) {
            val f = TerminalParserFixture()
            f.parser.accept(wire, 0, split)
            f.parser.accept(wire, split, wire.size - split)
            f.endOfInput()
            assertEquals(listOf("requestTerminalVersion", "writeCodepoint:88"), f.sink.events, "body=$body split=$split")
        }
    }

    @Test
    fun `cursor style keeps its separate signature`() {
        val f = TerminalParserFixture()
        f.acceptAscii("\u001b[2 q\u001b[>q")
        assertEquals(listOf("setCursorStyle:2", "requestTerminalVersion"), f.sink.events)
    }

    @Test
    fun `unfinished version request is discarded at EOF and reset`() {
        val f = TerminalParserFixture()
        f.acceptAscii("\u001b[>")
        f.endOfInput()
        assertEquals(emptyList(), f.sink.events)
        f.reset()
        f.acceptAscii("\u001b[>0q")
        assertEquals(listOf("requestTerminalVersion"), f.sink.events)
    }
}

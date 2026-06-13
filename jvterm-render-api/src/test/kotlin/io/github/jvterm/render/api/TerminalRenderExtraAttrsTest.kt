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
package io.github.jvterm.render.api

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TerminalRenderExtraAttrsTest {
    @Test
    fun `default word decodes to default underline color and no overline`() {
        val word = TerminalRenderExtraAttrs.DEFAULT

        assertAll(
            { assertEquals(TerminalRenderColorKind.DEFAULT, TerminalRenderExtraAttrs.underlineColorKind(word)) },
            { assertEquals(0, TerminalRenderExtraAttrs.underlineColorValue(word)) },
            { assertFalse(TerminalRenderExtraAttrs.isOverline(word)) },
        )
    }

    @Test
    fun `packed underline color and overline decode through public ABI`() {
        val word =
            TerminalRenderExtraAttrs.pack(
                underlineColorKind = TerminalRenderColorKind.RGB,
                underlineColorValue = 0xAA_55_11,
                overline = true,
            )

        assertAll(
            { assertEquals(TerminalRenderColorKind.RGB, TerminalRenderExtraAttrs.underlineColorKind(word)) },
            { assertEquals(0xAA_55_11, TerminalRenderExtraAttrs.underlineColorValue(word)) },
            { assertTrue(TerminalRenderExtraAttrs.isOverline(word)) },
            {
                assertEquals(
                    (TerminalRenderColorKind.RGB.toLong() shl 0) or
                        (0xAA_55_11L shl 2) or
                        (1L shl 26),
                    word,
                )
            },
        )
    }

    @Test
    fun `packer rejects invalid underline color values`() {
        assertAll(
            {
                assertThrows(IllegalArgumentException::class.java) {
                    TerminalRenderExtraAttrs.pack(
                        underlineColorKind = TerminalRenderColorKind.DEFAULT,
                        underlineColorValue = 1,
                    )
                }
            },
            {
                assertThrows(IllegalArgumentException::class.java) {
                    TerminalRenderExtraAttrs.pack(
                        underlineColorKind = TerminalRenderColorKind.INDEXED,
                        underlineColorValue = 256,
                    )
                }
            },
            {
                assertThrows(IllegalArgumentException::class.java) {
                    TerminalRenderExtraAttrs.pack(underlineColorKind = 3)
                }
            },
        )
    }
}

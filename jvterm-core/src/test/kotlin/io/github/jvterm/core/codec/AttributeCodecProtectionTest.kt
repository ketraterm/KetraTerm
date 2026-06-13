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
package io.github.jvterm.core.codec

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AttributeCodecProtectionTest {
    @Test
    fun `pack and unpack preserve selective erase protection`() {
        val packed =
            AttributeCodec.pack(
                fg = 9,
                bg = 4,
                bold = true,
                faint = true,
                italic = false,
                protected = true,
            )
        val unpacked = AttributeCodec.unpack(packed)

        assertAll(
            { assertTrue(AttributeCodec.isProtected(packed)) },
            { assertEquals(1L shl 57, packed and (1L shl 57)) },
            { assertTrue(unpacked.selectiveEraseProtected) },
        )
    }

    @Test
    fun `withProtected toggles only the protection bit`() {
        val base = AttributeCodec.pack(7, 3, bold = true, faint = true, italic = true)
        val protectedAttr = AttributeCodec.withProtected(base, enabled = true)
        val unprotectedAttr = AttributeCodec.withProtected(protectedAttr, enabled = false)

        assertAll(
            { assertTrue(AttributeCodec.isProtected(protectedAttr)) },
            { assertEquals(AttributeCodec.foreground(base), AttributeCodec.foreground(protectedAttr)) },
            { assertEquals(AttributeCodec.background(base), AttributeCodec.background(protectedAttr)) },
            { assertEquals(AttributeCodec.isBold(base), AttributeCodec.isBold(protectedAttr)) },
            { assertEquals(AttributeCodec.isFaint(base), AttributeCodec.isFaint(protectedAttr)) },
            { assertEquals(AttributeCodec.isItalic(base), AttributeCodec.isItalic(protectedAttr)) },
            { assertFalse(AttributeCodec.isProtected(unprotectedAttr)) },
            { assertEquals(base, unprotectedAttr) },
        )
    }
}

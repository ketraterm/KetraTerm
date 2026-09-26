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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TerminalClipboardSelectionTest {
    @Test
    fun `selection normalization preserves order and distinguishes every protocol target`() {
        assertEquals("c", TerminalClipboardSelection.parse("")?.value)
        assertEquals("pcs", TerminalClipboardSelection.parse("ppccsp")?.value)
        assertEquals("cpqs01234567", TerminalClipboardSelection.parse("cpqs01234567cpqs01234567")?.value)
        assertEquals("c", TerminalClipboardSelection.parse("c".repeat(4096))?.value)
        for (invalid in listOf("C", "8", "cx", "c;", "c\u001b", "c\n", "c🙂")) {
            assertNull(TerminalClipboardSelection.parse(invalid), invalid)
        }
    }
}

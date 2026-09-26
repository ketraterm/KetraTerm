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
package io.github.ketraterm.intellij.services

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IntellijOsc52ClipboardSelectionsTest {
    @Test
    fun `empty and c selections target the IDE clipboard`() {
        assertTrue(IntellijOsc52ClipboardSelections.targetsIdeClipboard(""))
        assertTrue(IntellijOsc52ClipboardSelections.targetsIdeClipboard("c"))
        assertTrue(IntellijOsc52ClipboardSelections.targetsIdeClipboard("cp"))
    }

    @Test
    fun `primary and secondary only selections do not target the IDE clipboard`() {
        assertFalse(IntellijOsc52ClipboardSelections.targetsIdeClipboard("p"))
        assertFalse(IntellijOsc52ClipboardSelections.targetsIdeClipboard("s"))
        assertFalse(IntellijOsc52ClipboardSelections.targetsIdeClipboard("ps"))
    }
}

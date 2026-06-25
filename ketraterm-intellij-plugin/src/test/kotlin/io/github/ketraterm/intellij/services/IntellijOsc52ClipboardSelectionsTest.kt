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

import io.github.ketraterm.host.TerminalClipboardAuditEvent
import io.github.ketraterm.host.TerminalClipboardDecision
import io.github.ketraterm.host.TerminalClipboardOperation
import io.github.ketraterm.host.TerminalClipboardOrigin
import io.github.ketraterm.host.TerminalClipboardPromptEvent
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

    @Test
    fun `prompt text names profile and hides protocol details`() {
        val message = IntellijOsc52ClipboardPromptText.message("PowerShell", promptEvent("OSC 52 works"))

        assertTrue(message.contains("Allow PowerShell to write 12 characters to the IDE clipboard?"))
        assertTrue(message.contains("Local terminal session"))
        assertFalse(message.contains("OSC 52"))
        assertFalse(message.contains("Selection:"))
        assertFalse(message.contains("bytes"))
    }

    @Test
    fun `empty prompt text is shown as clipboard clear`() {
        val message = IntellijOsc52ClipboardPromptText.message("PowerShell", promptEvent(""))

        assertTrue(message.contains("Allow PowerShell to clear the IDE clipboard?"))
    }

    private fun promptEvent(text: String): TerminalClipboardPromptEvent =
        TerminalClipboardPromptEvent(
            selection = "c",
            text = text,
            audit =
                TerminalClipboardAuditEvent(
                    operation = TerminalClipboardOperation.WRITE,
                    selection = "c",
                    origin = TerminalClipboardOrigin.LOCAL,
                    encodedLength = 0,
                    decodedBytes = text.toByteArray().size,
                    maxDecodedBytes = 1024,
                    decision = TerminalClipboardDecision.PROMPT_REQUIRED,
                ),
        )
}

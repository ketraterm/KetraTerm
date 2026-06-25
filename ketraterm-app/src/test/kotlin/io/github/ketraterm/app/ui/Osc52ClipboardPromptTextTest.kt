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
package io.github.ketraterm.app.ui

import io.github.ketraterm.host.TerminalClipboardAuditEvent
import io.github.ketraterm.host.TerminalClipboardDecision
import io.github.ketraterm.host.TerminalClipboardOperation
import io.github.ketraterm.host.TerminalClipboardOrigin
import io.github.ketraterm.host.TerminalClipboardPromptEvent
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class Osc52ClipboardPromptTextTest {
    @Test
    fun `plain prompt names profile and text length without protocol details`() {
        val message = Osc52ClipboardPromptText.plainMessage("PowerShell", promptEvent("OSC 52 works"))

        assertContains(message, "Allow PowerShell to write 12 characters to the clipboard?")
        assertContains(message, "Local terminal session")
        assertFalse(message.contains("OSC 52"))
        assertFalse(message.contains("Selection:"))
        assertFalse(message.contains("bytes"))
    }

    @Test
    fun `empty prompt is shown as clipboard clear`() {
        val message = Osc52ClipboardPromptText.plainMessage("PowerShell", promptEvent(""))

        assertEquals("Clipboard Access", Osc52ClipboardPromptText.title())
        assertContains(message, "Allow PowerShell to clear the clipboard?")
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
                    decodedBytes = text.encodeToByteArray().size,
                    maxDecodedBytes = 1024,
                    decision = TerminalClipboardDecision.PROMPT_REQUIRED,
                ),
        )
}

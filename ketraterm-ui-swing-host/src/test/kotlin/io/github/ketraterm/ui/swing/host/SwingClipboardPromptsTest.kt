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
package io.github.ketraterm.ui.swing.host

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SwingClipboardPromptsTest {
    @Test
    fun productsShareNamesUnicodeCountsAndEmptyWriteMeaning() {
        for (clipboard in listOf("clipboard", "IDE clipboard")) {
            assertEquals(
                "Allow an application in PowerShell to write 1 character to the $clipboard?",
                SwingClipboardPrompts.writeQuestion(" PowerShell ", "\ud83d\ude42", clipboard),
            )
            assertEquals(
                "Allow an application in this terminal to clear the $clipboard?",
                SwingClipboardPrompts.writeQuestion(" ", "", clipboard),
            )
            assertEquals(
                "Allow an application in this terminal to read your $clipboard? " +
                    "Only allow applications you trust. This request expires automatically.",
                SwingClipboardPrompts.readQuestion(" ", clipboard),
            )
            val question = SwingClipboardPrompts.writeQuestion("PowerShell", "OSC 52 works", clipboard)
            assertContains(question, "12 characters")
            assertFalse(question.contains("OSC 52"))
        }
    }

    @Test
    fun untrustedTerminalNamesAreEscapedBeforeSwingHtmlPresentation() {
        val message = SwingClipboardPrompts.readQuestion("<img src='https://invalid.example'> & \"name\"")
        val html = SwingDialogRequest("Consent", message, SwingDialogRequest.Severity.WARNING).htmlMessage()
        assertFalse(html.contains("<img"))
        assertContains(html, "&lt;img src=&#39;https://invalid.example&#39;&gt; &amp; &quot;name&quot;")
    }
}

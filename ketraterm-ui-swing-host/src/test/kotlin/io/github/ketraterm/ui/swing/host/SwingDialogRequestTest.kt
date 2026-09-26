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
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SwingDialogRequestTest {
    @Test
    fun `invalid choices fail before either product can show UI`() {
        for (options in listOf(emptyList(), listOf(""), listOf("OK", "OK"))) {
            assertFailsWith<IllegalArgumentException> {
                SwingDialogRequest("Title", "Text", SwingDialogRequest.Severity.WARNING, options)
            }
        }
        for (index in listOf(-1, 2)) {
            assertFailsWith<IllegalArgumentException> {
                SwingDialogRequest("Title", "Text", SwingDialogRequest.Severity.WARNING, listOf("Proceed", "Cancel"), index)
            }
        }
        assertFailsWith<IllegalArgumentException> {
            SwingDialogRequest(" ", "Text", SwingDialogRequest.Severity.ERROR)
        }
    }

    @Test
    fun `choices retain their meaning after the caller mutates its list`() {
        val options = mutableListOf("Terminate", "Cancel")
        val request = SwingDialogRequest("Close terminal?", "Message", SwingDialogRequest.Severity.WARNING, options)
        options.reverse()
        assertEquals(listOf("Terminate", "Cancel"), request.options)
        assertEquals(1, request.defaultOption)
    }

    @Test
    fun `both products receive literal text with preserved line breaks`() {
        val request = SwingDialogRequest("Error", "<img src='remote'> & \"text\"\nNext line", SwingDialogRequest.Severity.ERROR)
        assertEquals(
            "<html><body style='width: 340px'>&lt;img src=&#39;remote&#39;&gt; &amp; &quot;text&quot;<br>Next line</body></html>",
            request.htmlMessage(),
        )
    }
}

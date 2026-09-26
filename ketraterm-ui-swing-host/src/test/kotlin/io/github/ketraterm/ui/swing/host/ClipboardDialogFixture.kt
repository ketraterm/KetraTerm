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

/** Records the product dialog boundary without creating a native window. */
internal class ClipboardDialogFixture {
    var isVisible = false
        private set
    var message = ""
        private set
    var decide: (Int?) -> Unit = {}
        private set
    val prompt =
        SwingClipboardReadPrompt { text, decision ->
            message = text.message
            decide = decision
            isVisible = true
            AutoCloseable { isVisible = false }
        }

    fun click(label: String) =
        decide(
            SwingClipboardReadPrompt.Decision.entries
                .single { it.label == label }
                .ordinal,
        )
}

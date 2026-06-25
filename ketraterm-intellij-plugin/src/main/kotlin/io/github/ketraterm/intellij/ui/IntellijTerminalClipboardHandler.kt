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
package io.github.ketraterm.intellij.ui

import com.intellij.openapi.ide.CopyPasteManager
import io.github.ketraterm.ui.swing.settings.TerminalClipboardHandler
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection

/**
 * IntelliJ-backed clipboard service for the reusable Swing terminal.
 *
 * This adapter keeps IDE clipboard ownership inside the plugin host. Reusable
 * terminal modules continue to depend only on [TerminalClipboardHandler].
 */
internal object IntellijTerminalClipboardHandler : TerminalClipboardHandler {
    override fun copyText(text: String) {
        CopyPasteManager.getInstance().setContents(StringSelection(text))
    }

    override fun readText(): String? =
        try {
            CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor) as? String
        } catch (_: Exception) {
            null
        }
}

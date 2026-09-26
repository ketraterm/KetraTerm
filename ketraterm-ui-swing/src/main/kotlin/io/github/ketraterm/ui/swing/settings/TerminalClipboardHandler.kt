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
package io.github.ketraterm.ui.swing.settings

import java.awt.Toolkit
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection

/**
 * Host-facing clipboard abstraction used by the reusable Swing terminal.
 *
 * Hosts can replace the default system implementation when embedding the
 * component in an environment with its own clipboard, permission prompts, or
 * test doubles.
 */
interface TerminalClipboardHandler {
    /**
     * Writes [text] to the host clipboard.
     *
     * @param text selected terminal text.
     */
    fun copyText(text: String)

    /**
     * Reads plain text from the host clipboard, or returns `null` when no text
     * is available.
     *
     * @return clipboard text, or `null`.
     */
    fun readText(): String?

    /**
     * Reads the native primary selection, or returns `null` when unsupported or
     * without text. An empty string is available text. Never substitutes the
     * ordinary clipboard. Like [readText], this operation may block in native code.
     */
    fun readPrimarySelectionText(): String? = null

    companion object {
        /**
         * Clipboard handler backed by AWT's system clipboard.
         */
        @JvmField
        val SYSTEM: TerminalClipboardHandler = SystemTerminalClipboardHandler
    }
}

private object SystemTerminalClipboardHandler : TerminalClipboardHandler {
    override fun copyText(text: String) {
        Toolkit
            .getDefaultToolkit()
            .systemClipboard
            .setContents(StringSelection(text), null)
    }

    override fun readText(): String? = Toolkit.getDefaultToolkit().systemClipboard.readPlainText()

    override fun readPrimarySelectionText(): String? = Toolkit.getDefaultToolkit().systemSelection?.readPlainText()

    private fun Clipboard.readPlainText(): String? {
        val contents = getContents(null) ?: return null
        if (!contents.isDataFlavorSupported(DataFlavor.stringFlavor)) return null
        return contents.getTransferData(DataFlavor.stringFlavor) as? String
    }
}

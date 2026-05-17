package com.gagik.terminal.ui.swing.settings

import java.awt.Toolkit
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
        Toolkit.getDefaultToolkit()
            .systemClipboard
            .setContents(StringSelection(text), null)
    }

    override fun readText(): String? {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        if (!clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) return null
        return clipboard.getData(DataFlavor.stringFlavor) as? String
    }
}

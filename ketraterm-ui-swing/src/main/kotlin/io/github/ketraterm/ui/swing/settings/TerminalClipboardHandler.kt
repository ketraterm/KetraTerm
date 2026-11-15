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

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
 * Direct synchronous methods may block. SwingTerminal uses the suspend methods, whose defaults
 * preserve existing host callbacks on the caller thread. The system implementation serializes
 * suspend reads and writes off the EDT. Cancellation prevents queued operations and discards late
 * reads; a native write already entered cannot be undone.
 */
public interface TerminalClipboardHandler {
    /**
     * Writes [text] to the host clipboard.
     *
     * @param text selected terminal text.
     */
    public fun copyText(text: String)

    /**
     * Reads plain text from the host clipboard, or returns `null` when no text
     * is available.
     *
     * @return clipboard text, or `null`.
     */
    public fun readText(): String?

    /**
     * Reads the native primary selection, or returns `null` when unsupported or
     * without text. An empty string is available text. Never substitutes the
     * ordinary clipboard. Like [readText], this operation may block in native code.
     */
    public fun readPrimarySelectionText(): String? = null

    /**
     * Writes without blocking the UI when using [SYSTEM]. Called from the terminal's EDT coroutine.
     * The default preserves existing host callbacks on the caller thread. Hosts doing blocking I/O
     * must override this method and dispatch that work; Swing consent stays on the caller thread.
     */
    public suspend fun copyTextAsync(text: String): Unit = copyText(text)

    /** Reads clipboard text asynchronously; follows the threading contract of [copyTextAsync]. */
    public suspend fun readTextAsync(): String? = readText()

    /** Reads only the primary selection; follows the threading contract of [copyTextAsync]. */
    public suspend fun readPrimarySelectionTextAsync(): String? = readPrimarySelectionText()

    public companion object {
        /**
         * Clipboard handler backed by AWT's system clipboard.
         */
        @JvmField
        public val SYSTEM: TerminalClipboardHandler = SystemTerminalClipboardHandler()
    }
}

internal class SystemTerminalClipboardHandler(
    private val clipboard: () -> Clipboard = { Toolkit.getDefaultToolkit().systemClipboard },
    private val primarySelection: () -> Clipboard? = { Toolkit.getDefaultToolkit().systemSelection },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : TerminalClipboardHandler {
    // Acquire before dispatch so EDT request order survives worker scheduling. Cancellation cannot
    // release this slot while an uninterruptible native call is still running.
    private val access = Mutex()

    override suspend fun copyTextAsync(text: String) = access.withLock { withContext(ioDispatcher) { copyText(text) } }

    override suspend fun readTextAsync(): String? = access.withLock { withContext(ioDispatcher) { readText() } }

    override suspend fun readPrimarySelectionTextAsync(): String? =
        access.withLock { withContext(ioDispatcher) { readPrimarySelectionText() } }

    override fun copyText(text: String) {
        clipboard().setContents(StringSelection(text), null)
    }

    override fun readText(): String? = clipboard().readPlainText()

    override fun readPrimarySelectionText(): String? = primarySelection()?.readPlainText()

    private fun Clipboard.readPlainText(): String? {
        val contents = getContents(null) ?: return null
        if (!contents.isDataFlavorSupported(DataFlavor.stringFlavor)) return null
        return contents.getTransferData(DataFlavor.stringFlavor) as? String
    }
}

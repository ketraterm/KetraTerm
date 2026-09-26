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

import com.intellij.ide.ClientCopyPasteManager
import com.intellij.openapi.client.ClientAppSession
import io.github.ketraterm.ui.swing.settings.TerminalClipboardHandler
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.util.concurrent.CancellationException

/**
 * IntelliJ-backed clipboard service for the reusable Swing terminal.
 *
 * This adapter keeps IDE clipboard ownership inside the plugin host. Reusable
 * terminal modules continue to depend only on [TerminalClipboardHandler].
 */
internal class IntellijTerminalClipboardHandler(
    private val client: ClientAppSession,
) : TerminalClipboardHandler {
    // Resolve once: CopyPasteManager's facade looks up the ambient client on each call.
    private val clipboard = ClientCopyPasteManager.getInstance(client)

    override fun copyText(text: String) {
        checkClient()
        clipboard.contents = StringSelection(text)
    }

    override fun readText(): String? {
        checkClient()
        val text = clipboard.getContents<String>(DataFlavor.stringFlavor)
        checkClient()
        return text
    }

    override fun readPrimarySelectionText(): String? {
        checkClient()
        val contents = clipboard.systemSelectionContents ?: return null
        if (!contents.isDataFlavorSupported(DataFlavor.stringFlavor)) return null
        val text = contents.getTransferData(DataFlavor.stringFlavor) as? String
        checkClient()
        return text
    }

    private fun checkClient() {
        if (client.isDisposed) throw CancellationException("Terminal clipboard client closed")
    }
}

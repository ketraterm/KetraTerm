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
import java.awt.Desktop
import java.net.URI
import java.net.URISyntaxException
import java.util.*

/**
 * Host-facing policy for explicit terminal hyperlink activation.
 *
 * Swing UI invokes this handler only after explicit activation.
 * Hosts can replace it to show prompts, route links through an
 * IDE, audit activations, or deny all links in locked-down environments.
 * SwingTerminal uses [openHyperlinkAsync]; direct [openHyperlink] calls may block.
 */
public fun interface TerminalHyperlinkHandler {
    /**
     * Activates [uri] after explicit user intent.
     *
     * @param uri target URI supplied by terminal output metadata.
     * @return `true` when activation was handled.
     */
    public fun openHyperlink(uri: String): Boolean

    /**
     * Activates from the terminal's EDT coroutine. [SYSTEM] performs OS activation on a worker.
     * The default preserves existing host callbacks on the caller thread; blocking hosts should
     * override this method. Cancellation propagates, but cannot undo an OS activation already begun.
     */
    public suspend fun openHyperlinkAsync(uri: String): Boolean = openHyperlink(uri)

    public companion object {
        /**
         * Conservative system-browser handler for common user-facing URI schemes.
         * Malformed URIs and schemes other than HTTP, HTTPS, and mailto return false.
         */
        @JvmField
        public val SYSTEM: TerminalHyperlinkHandler = SystemTerminalHyperlinkHandler()

        /**
         * Handler that denies every activation request.
         */
        @JvmField
        public val NONE: TerminalHyperlinkHandler = TerminalHyperlinkHandler { false }
    }
}

internal class SystemTerminalHyperlinkHandler(
    private val browse: (URI) -> Boolean = ::browseWithDesktop,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : TerminalHyperlinkHandler {
    private val activation = Mutex()

    override suspend fun openHyperlinkAsync(uri: String): Boolean = activation.withLock { withContext(ioDispatcher) { openHyperlink(uri) } }

    override fun openHyperlink(uri: String): Boolean {
        val parsed =
            try {
                URI(uri)
            } catch (_: URISyntaxException) {
                return false
            }
        if (!isAllowedScheme(parsed.scheme)) return false
        return browse(parsed)
    }

    private fun isAllowedScheme(scheme: String?): Boolean =
        when (scheme?.lowercase(Locale.ROOT)) {
            "http", "https", "mailto" -> true
            else -> false
        }
}

private fun browseWithDesktop(parsed: URI): Boolean {
    if (!Desktop.isDesktopSupported()) return false
    val desktop =
        try {
            Desktop.getDesktop()
        } catch (_: SecurityException) {
            return false
        } catch (_: UnsupportedOperationException) {
            return false
        }
    val browseSupported =
        try {
            desktop.isSupported(Desktop.Action.BROWSE)
        } catch (_: SecurityException) {
            return false
        }
    if (!browseSupported) return false

    return try {
        desktop.browse(parsed)
        true
    } catch (_: SecurityException) {
        false
    } catch (_: UnsupportedOperationException) {
        false
    } catch (_: java.io.IOException) {
        false
    }
}

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
package io.github.jvterm.ui.swing.api

import io.github.jvterm.ui.swing.api.TerminalUiDispatcher.Companion.SWING
import io.github.jvterm.ui.swing.settings.TerminalClipboardHandler
import io.github.jvterm.ui.swing.settings.TerminalHyperlinkHandler
import javax.swing.SwingUtilities

/**
 * Host scheduler used by the reusable Swing terminal for UI-thread work.
 *
 * Implementations must eventually run submitted [Runnable] instances on the
 * Swing Event Dispatch Thread. Standalone hosts normally use [SWING]; IntelliJ
 * hosts can route through the platform application dispatcher while preserving
 * EDT ownership of Swing component state.
 */
fun interface TerminalUiDispatcher {
    /**
     * Schedules [runnable] for execution on the UI thread.
     *
     * @param runnable work that mutates Swing component state.
     */
    fun dispatch(runnable: Runnable)

    companion object {
        /**
         * Dispatcher backed by Swing's standard event queue.
         */
        @JvmField
        val SWING: TerminalUiDispatcher =
            TerminalUiDispatcher { runnable ->
                SwingUtilities.invokeLater(runnable)
            }
    }
}

/**
 * Host-provided non-render services for [TerminalSwingTerminal].
 *
 * These services are intentionally kept out of row painters. Rendering consumes
 * immutable settings and render-cache snapshots, while host integrations supply
 * scheduling, clipboard, and explicit hyperlink activation policy here.
 *
 * @property uiDispatcher scheduler for UI-thread component work.
 * @property clipboardHandler host clipboard adapter for copy and paste actions.
 * @property hyperlinkHandler host policy for explicit Ctrl-click hyperlink
 * activation.
 * @property viewportListener host scrollbar adapter notified when the terminal
 * scrollback viewport changes.
 */
data class TerminalSwingHostServices
    @JvmOverloads
    constructor(
        val uiDispatcher: TerminalUiDispatcher = SWING,
        val clipboardHandler: TerminalClipboardHandler = TerminalClipboardHandler.SYSTEM,
        val hyperlinkHandler: TerminalHyperlinkHandler = TerminalHyperlinkHandler.SYSTEM,
        val viewportListener: TerminalViewportListener = TerminalViewportListener.NONE,
    )

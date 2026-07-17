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
package io.github.ketraterm.ui.swing.api

import io.github.ketraterm.ui.swing.api.TerminalUiDispatcher.Companion.SWING
import io.github.ketraterm.ui.swing.settings.TerminalClipboardHandler
import io.github.ketraterm.ui.swing.settings.TerminalHyperlinkHandler
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionFeedbackHandler
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionHandler
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionKeymap
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionProvider
import java.awt.event.KeyEvent
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
 * Host-owned keyboard action hook for [SwingTerminal].
 *
 * The reusable terminal asks this hook before encoding key presses for the
 * shell. Returning `true` means the host handled the key as application policy
 * and the terminal must not send it to the session. Returning `false` passes
 * the key through to terminal input handling.
 */
fun interface SwingTerminalHostKeyHandler {
    /**
     * Handles a key press before terminal input encoding.
     *
     * @param event Swing key event owned by the EDT.
     * @return `true` when the host handled and consumed the key.
     */
    fun handleKeyPressed(event: KeyEvent): Boolean

    companion object {
        /**
         * Handler that never claims keys.
         */
        @JvmField
        val NONE: SwingTerminalHostKeyHandler = SwingTerminalHostKeyHandler { false }
    }
}

/**
 * Host-provided non-render services for [SwingTerminal].
 *
 * These services are intentionally kept out of row painters. Rendering consumes
 * immutable settings and render-cache snapshots, while host integrations supply
 * scheduling, clipboard, and explicit hyperlink activation policy here.
 *
 * @property uiDispatcher scheduler for UI-thread component work.
 * @property clipboardHandler host clipboard adapter for copy and paste actions.
 * @property hyperlinkHandler host policy for explicit Ctrl-click hyperlink
 * activation.
 * @property hyperlinkDetector host detector for links discovered from the
 * currently visible terminal viewport. Detection runs outside paint and mouse
 * movement, and reported actions are invoked only after explicit activation.
 * @property viewportListener host scrollbar adapter notified when the terminal
 * scrollback viewport changes.
 * @property scrollbarOverlayEnabled whether the reusable component should draw
 * and handle its own overlay scrollbar. Hosts that install a native external
 * scrollbar should set this to `false`.
 * @property shellSuggestionProvider host provider queried for bounded
 * command-line suggestion snapshots.
 * @property shellSuggestionHandler host callback invoked after the user accepts
 * a shell suggestion from the reusable popup.
 * @property shellSuggestionFeedbackHandler host callback invoked when the user
 * accepts or explicitly dismisses a shell suggestion.
 * @property shellSuggestionKeymap host-owned mapping from Swing key events to
 * semantic suggestion actions. Standalone hosts may retain the standard map;
 * platform integrations should resolve their active application keymap.
 * @property hostKeyHandler host-owned keyboard action policy evaluated before
 * terminal input encoding.
 * @property contextMenuHandler host-owned right-click popup policy. The
 * reusable terminal invokes it only when terminal UI owns the right-click
 * gesture; application mouse reporting takes precedence unless Shift is held.
 * @property fontResolver custom host font resolver policy.
 */
data class SwingHostServices
    @JvmOverloads
    constructor(
        val uiDispatcher: TerminalUiDispatcher = SWING,
        val clipboardHandler: TerminalClipboardHandler = TerminalClipboardHandler.SYSTEM,
        val hyperlinkHandler: TerminalHyperlinkHandler = TerminalHyperlinkHandler.SYSTEM,
        val hyperlinkDetector: SwingHyperlinkDetector = SwingHyperlinkDetector.NONE,
        val viewportListener: TerminalViewportListener = TerminalViewportListener.NONE,
        val scrollbarOverlayEnabled: Boolean = true,
        val shellSuggestionProvider: SwingShellSuggestionProvider = SwingShellSuggestionProvider.NONE,
        val shellSuggestionHandler: SwingShellSuggestionHandler = SwingShellSuggestionHandler.NONE,
        val shellSuggestionFeedbackHandler: SwingShellSuggestionFeedbackHandler = SwingShellSuggestionFeedbackHandler.NONE,
        val shellSuggestionKeymap: SwingShellSuggestionKeymap = SwingShellSuggestionKeymap.STANDARD,
        val hostKeyHandler: SwingTerminalHostKeyHandler = SwingTerminalHostKeyHandler.NONE,
        val contextMenuHandler: SwingTerminalContextMenuHandler = SwingTerminalContextMenuHandler.NONE,
        val fontResolver: TerminalFontResolver? = null,
    )

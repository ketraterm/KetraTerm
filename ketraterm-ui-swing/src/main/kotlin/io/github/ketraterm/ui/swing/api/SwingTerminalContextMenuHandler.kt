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

/**
 * Host-owned terminal context-menu hook.
 *
 * The reusable Swing terminal invokes this hook only when terminal-level UI is
 * allowed to own the right-click gesture. Plain right-click is passed through
 * to the PTY while DEC mouse reporting is active; Shift-right-click forces
 * this hook so users can temporarily bypass application mouse handling.
 */
fun interface SwingTerminalContextMenuHandler {
    /**
     * Shows or handles a terminal context menu request.
     *
     * Implementations run on the Swing Event Dispatch Thread.
     *
     * @param request context-menu request and terminal actions.
     * @return `true` when the host showed or handled a menu.
     */
    fun handleContextMenu(request: SwingTerminalContextMenuRequest): Boolean

    companion object {
        /**
         * Handler that never shows a context menu.
         */
        @JvmField
        val NONE: SwingTerminalContextMenuHandler = SwingTerminalContextMenuHandler { false }
    }
}

/**
 * Context-menu request delivered to a Swing terminal host.
 *
 * The request exposes host-safe terminal actions without making the reusable
 * component choose popup contents, styling, or host-specific menu items.
 *
 * @property terminal source terminal component.
 * @property x component-local x coordinate for showing a popup.
 * @property y component-local y coordinate for showing a popup.
 * @property forcedByShift whether Shift forced terminal-level handling while
 * application mouse reporting was active.
 * @property hyperlink hyperlink under the pointer, or `null`.
 */
class SwingTerminalContextMenuRequest internal constructor(
    val terminal: SwingTerminal,
    val x: Int,
    val y: Int,
    val forcedByShift: Boolean,
    val hyperlink: SwingTerminalContextHyperlink?,
) {
    /**
     * Returns whether the terminal currently has selected text.
     */
    fun hasSelection(): Boolean = terminal.currentSelection() != null

    /**
     * Copies the current terminal selection to the host clipboard.
     *
     * @return `true` when text was copied.
     */
    fun copySelection(): Boolean = terminal.copySelectionToClipboard()

    /**
     * Pastes host clipboard text into the terminal session.
     *
     * @return `true` when text was pasted.
     */
    fun pasteClipboard(): Boolean = terminal.pasteClipboardText()

    /**
     * Selects all retained terminal text.
     *
     * @return `true` when a selection was created.
     */
    fun selectAll(): Boolean = terminal.selectAll()

    /**
     * Requests a foreground-program screen clear/redraw.
     *
     * This sends Ctrl+L/form-feed to the terminal session instead of mutating
     * the emulator display behind the PTY.
     *
     * @return `true` when a bound session accepted the input request.
     */
    fun clearScreen(): Boolean = terminal.clearScreen()
}

/**
 * Hyperlink context available to a terminal context menu.
 *
 * @property uri resolved OSC 8 URI when available. Host-discovered links may
 * expose only [open] and leave this value `null`.
 */
class SwingTerminalContextHyperlink internal constructor(
    val uri: String?,
    private val openAction: () -> Boolean,
    private val copyUriAction: () -> Boolean,
) {
    /**
     * Opens the hyperlink target.
     *
     * @return `true` when activation was handled.
     */
    fun open(): Boolean = openAction()

    /**
     * Copies [uri] to the host clipboard when a resolved URI is available.
     *
     * @return `true` when a URI was copied.
     */
    fun copyUri(): Boolean = copyUriAction()
}

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

import io.github.ketraterm.workspace.TerminalWorkspaceTab

/**
 * IDE-owned actions that affect a terminal pane's surrounding tool-window UI.
 *
 * The reusable terminal component exposes only terminal operations. IntelliJ
 * pane/tab lifecycle commands stay in the plugin and are invoked through this
 * narrow callback set.
 */
internal class KetraTermTerminalPaneHostActions(
    private val openNewTabAction: () -> Boolean = { false },
    private val canOpenTerminalHereAction: (TerminalWorkspaceTab) -> Boolean = { false },
    private val openTerminalHereAction: (TerminalWorkspaceTab) -> Boolean = { false },
    private val closePaneAction: (TerminalWorkspaceTab) -> Unit = {},
) {
    /**
     * Opens a new default KetraTerm tab in the containing tool window.
     */
    fun openNewTab(): Boolean = openNewTabAction()

    /**
     * Returns whether a local OSC 7 working directory can be used for [tab].
     */
    fun canOpenTerminalHere(tab: TerminalWorkspaceTab): Boolean = canOpenTerminalHereAction(tab)

    /**
     * Opens a new KetraTerm tab rooted at [tab]'s current local directory.
     */
    fun openTerminalHere(tab: TerminalWorkspaceTab): Boolean = openTerminalHereAction(tab)

    /**
     * Closes [tab]'s containing pane.
     */
    fun closePane(tab: TerminalWorkspaceTab) = closePaneAction(tab)

    companion object {
        /**
         * Empty action set used by tests or panes not attached to a tool window.
         */
        val NONE = KetraTermTerminalPaneHostActions()
    }
}

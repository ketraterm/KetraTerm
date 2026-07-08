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
 * IDE-owned pane actions exposed to the terminal context menu.
 *
 * The pane owns Swing assembly; the project service owns tab lifecycle and
 * launch policy. This adapter keeps those responsibilities separated.
 */
internal class KetraTermTerminalPaneHostActions(
    private val canOpenTerminalHereAction: (TerminalWorkspaceTab) -> Boolean,
    private val openTerminalHereAction: (TerminalWorkspaceTab) -> Boolean,
    private val openNewTabAction: () -> Boolean,
    private val closePaneAction: (TerminalWorkspaceTab) -> Unit,
) {
    /**
     * Returns whether a local working directory is available for [tab].
     */
    fun canOpenTerminalHere(tab: TerminalWorkspaceTab): Boolean = canOpenTerminalHereAction(tab)

    /**
     * Opens a new IDE terminal tab using [tab]'s current local working directory.
     */
    fun openTerminalHere(tab: TerminalWorkspaceTab): Boolean = openTerminalHereAction(tab)

    /**
     * Opens a new IDE terminal tab with the default launch profile.
     */
    fun openNewTab(): Boolean = openNewTabAction()

    /**
     * Closes [tab] using the IDE content lifecycle.
     */
    fun closePane(tab: TerminalWorkspaceTab) {
        closePaneAction(tab)
    }

    companion object {
        /**
         * Adapter with no project-level actions.
         */
        val NONE =
            KetraTermTerminalPaneHostActions(
                canOpenTerminalHereAction = { false },
                openTerminalHereAction = { false },
                openNewTabAction = { false },
                closePaneAction = {},
            )
    }
}

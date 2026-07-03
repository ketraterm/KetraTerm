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
package io.github.ketraterm.app.ui

import java.awt.Component
import javax.swing.JOptionPane

/**
 * User-facing confirmation for closing live terminal sessions.
 */
internal fun interface TerminalCloseConfirmation {
    /**
     * Returns true when the user accepts terminating the live process.
     *
     * @param request close request that needs confirmation.
     * @return true to continue closing, false to leave the terminal open.
     */
    fun confirmClose(request: TerminalCloseRequest): Boolean
}

/**
 * Describes a close request containing one or more live terminal processes.
 *
 * @property displayName user-visible terminal or profile name.
 * @property liveProcessCount number of still-running sessions that will be
 * terminated.
 */
internal data class TerminalCloseRequest(
    val displayName: String,
    val liveProcessCount: Int,
) {
    init {
        require(displayName.isNotBlank()) { "displayName must not be blank" }
        require(liveProcessCount > 0) { "liveProcessCount must be > 0" }
    }
}

internal class SwingTerminalCloseConfirmation(
    private val owner: Component,
) : TerminalCloseConfirmation {
    override fun confirmClose(request: TerminalCloseRequest): Boolean {
        val answer =
            JOptionPane.showOptionDialog(
                owner,
                TerminalClosePromptText.message(request),
                TerminalClosePromptText.title(request),
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE,
                null,
                TerminalClosePromptText.options(),
                TerminalClosePromptText.cancelOption(),
            )
        return answer == 0
    }
}

internal object TerminalClosePromptText {
    fun title(request: TerminalCloseRequest): String =
        if (request.liveProcessCount == 1) {
            "Kill Terminal Process?"
        } else {
            "Kill Terminal Processes?"
        }

    fun message(request: TerminalCloseRequest): String =
        if (request.liveProcessCount == 1) {
            "Closing \"${request.displayName}\" will kill its running process."
        } else {
            "Closing \"${request.displayName}\" will kill ${request.liveProcessCount} running processes."
        }

    fun options(): Array<String> = arrayOf("Kill Process", cancelOption())

    fun cancelOption(): String = "Cancel"
}

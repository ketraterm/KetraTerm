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
package io.github.ketraterm.ui.swing.suggestion

import io.github.ketraterm.input.event.TerminalTextReplacementEvent
import io.github.ketraterm.session.TerminalCommandEditContext
import io.github.ketraterm.session.TerminalInputAdmission
import io.github.ketraterm.session.TerminalSession

/** Carries the session capability through Swing's final acceptance callback. */
internal class SessionShellSuggestionHandler(
    val session: TerminalSession,
) : SwingShellSuggestionHandler {
    override fun onSuggestionAccepted(acceptance: SwingShellSuggestionAcceptance) {
        error(
            "Session-backed suggestion acceptance requires a captured Swing request; use conditional session admission for host-managed requests",
        )
    }

    fun accept(
        acceptance: SwingShellSuggestionAcceptance,
        expected: TerminalCommandEditContext?,
    ): Boolean {
        if (expected == null) return false
        val request = acceptance.request
        if (expected.commandLine.commandText != request.commandText ||
            expected.commandLine.cursorOffset != request.cursorOffset
        ) {
            return false
        }
        val replacement = acceptance.suggestion.replacementFor(request) ?: return false
        return session.submitInput(
            expected,
            listOf(
                TerminalTextReplacementEvent(
                    replacement.deleteAfterCursorCount,
                    replacement.deleteBeforeCursorCount,
                    replacement.replacementText,
                ),
            ),
        ) == TerminalInputAdmission.ACCEPTED
    }
}

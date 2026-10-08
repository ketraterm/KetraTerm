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
import io.github.ketraterm.session.TerminalInputAdmission
import io.github.ketraterm.session.TerminalSession

/** Captures an editing capability on the EDT before asynchronous suggestion work starts. */
public fun interface SwingShellSuggestionEditTarget {
    /** Returns a request-owned handler, or null when editing is currently unavailable. */
    public fun capture(request: SwingShellSuggestionRequest): SwingShellSuggestionHandler?

    public companion object {
        /** Display-only target; acceptance truthfully reports unsupported editing. */
        @JvmField
        public val NONE: SwingShellSuggestionEditTarget = SwingShellSuggestionEditTarget { SwingShellSuggestionHandler.NONE }

        /**
         * Captures the session's original versioned command line and atomically admits a complete replacement.
         * A missing model, pending input, or mismatched command line returns null before provider work.
         * Capture never infers acknowledgement by the remote shell.
         */
        @JvmStatic
        public fun createDefault(session: TerminalSession): SwingShellSuggestionEditTarget =
            SwingShellSuggestionEditTarget { request ->
                val expected = session.captureCommandEdit() ?: return@SwingShellSuggestionEditTarget null
                if (expected.commandLine.commandText != request.commandText ||
                    expected.commandLine.cursorOffset != request.cursorOffset
                ) {
                    expected.cancel()
                    return@SwingShellSuggestionEditTarget null
                }
                object : SwingShellSuggestionHandler {
                    override fun tryAccept(acceptance: SwingShellSuggestionAcceptance): SwingShellSuggestionAcceptanceResult {
                        if (acceptance.request != request) return SwingShellSuggestionAcceptanceResult.STALE_CONTEXT
                        val replacement =
                            acceptance.suggestion.replacementFor(request)
                                ?: return SwingShellSuggestionAcceptanceResult.INVALID_EDIT
                        return when (
                            session.submitInput(
                                expected,
                                listOf(
                                    TerminalTextReplacementEvent(
                                        replacement.deleteAfterCursorCount,
                                        replacement.deleteBeforeCursorCount,
                                        replacement.replacementText,
                                    ),
                                ),
                            )
                        ) {
                            TerminalInputAdmission.ACCEPTED -> SwingShellSuggestionAcceptanceResult.ACCEPTED
                            TerminalInputAdmission.STALE_CONTEXT,
                            TerminalInputAdmission.CANCELLED,
                            -> SwingShellSuggestionAcceptanceResult.STALE_CONTEXT
                            TerminalInputAdmission.UNSUPPORTED_CONTEXT -> SwingShellSuggestionAcceptanceResult.UNSUPPORTED
                            TerminalInputAdmission.NOT_RUNNING, TerminalInputAdmission.CLOSED,
                            TerminalInputAdmission.CAPACITY_EXCEEDED,
                            -> SwingShellSuggestionAcceptanceResult.UNAVAILABLE
                        }
                    }

                    override fun close() = expected.cancel()
                }
            }
    }
}

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

/** View-owned provider diagnostics, called once on the EDT after failed request cleanup. */
public fun interface SwingShellSuggestionFailureHandler {
    /** Reports an operational provider failure; coroutine cancellation is excluded. */
    public fun onSuggestionFailure(
        request: SwingShellSuggestionRequest,
        failure: Exception,
    )

    public companion object {
        /** Default diagnostics, preserving the exception without adding request command text. */
        @JvmField
        public val LOGGING: SwingShellSuggestionFailureHandler =
            SwingShellSuggestionFailureHandler { _, failure ->
                System.getLogger(SwingShellSuggestionFailureHandler::class.java.name).log(
                    System.Logger.Level.WARNING,
                    "Shell suggestion provider failed",
                    failure,
                )
            }
    }
}

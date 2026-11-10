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

/** Context captured on the EDT before inline source collection starts. */
@ExperimentalShellSuggestionsApi
public data class SwingInlineShellSuggestionRequest(
    /** Original command text and UTF-16 cursor, sharing the interaction's editing authority. */
    val commandLine: SwingShellSuggestionRequest,
    /** Resulting command of the selected visible popup item, or null when none is selected. */
    val selectedPopupCommandText: String?,
)

/**
 * Host-owned inline source. [open] captures resources and feedback on the EDT; keep it cheap.
 * The returned cold source is collected off the EDT. Popup selection changes cancel collection
 * and call [open] again with updated context. Closing an interaction cancels its source.
 */
@ExperimentalShellSuggestionsApi
public fun interface SwingInlineShellSuggestionProvider {
    /** Returns request-owned work; the provider itself remains host-owned. */
    public fun open(request: SwingInlineShellSuggestionRequest): SwingShellSuggestionSource
}

/** Determines which independently produced inline results may accompany a popup selection. */
@ExperimentalShellSuggestionsApi
public enum class SwingInlineShellSuggestionCoexistence {
    /** Show inline only when its resulting command extends the selected popup command. */
    EXTEND_POPUP_SELECTION,

    /** Permit unrelated popup and inline results to remain visible together. */
    INDEPENDENT,
}

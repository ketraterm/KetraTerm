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

import io.github.ketraterm.ui.swing.suggestion.*
import kotlinx.coroutines.flow.map

/** Uses the popup provider's top-ranked completion without adding another ranking or fallback policy. */
@OptIn(ExperimentalShellSuggestionsApi::class)
internal class InlineCompletionSuggestions(
    private val provider: SwingShellSuggestionProvider,
) : SwingInlineShellSuggestionProvider {
    override fun open(request: SwingShellSuggestionRequest): SwingShellSuggestionSource {
        val source = provider.open(request)
        // Keep the original candidate and feedback owner; the view checks append-only eligibility.
        return SwingShellSuggestionSource(source.suggestions.map { it.take(1) }, source.feedbackHandler)
    }
}

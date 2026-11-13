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

/** One EDT publication of a ranking into inline text and the remaining popup rows. */
internal class SharedShellSuggestionPresentation(
    val interaction: SwingShellSuggestionInteraction,
    private val presentInline: (SwingShellSuggestion) -> Boolean,
    private val hideInline: () -> Unit,
    private val popupEnabled: () -> Boolean,
) : AutoCloseable {
    private var ranked: List<SwingShellSuggestion> = emptyList()
    private var omittedFirst = false
    private var browsing = false
    private var updating = false
    private val listener = SwingShellSuggestionInteractionListener { refresh() }
    val hasSuggestions: Boolean get() = ranked.isNotEmpty()

    init {
        interaction.addChangeListener(listener)
    }

    fun publish(suggestions: List<SwingShellSuggestion>) {
        ranked = suggestions.toList()
        refresh()
    }

    fun refresh() {
        if (updating || !interaction.isActive) return
        updating = true
        try {
            if (interaction.hasExplicitSelection) browsing = true
            if (browsing) {
                hideInline()
            } else {
                omittedFirst = ranked.firstOrNull()?.let(presentInline) == true
                if (!omittedFirst) hideInline()
            }
            val rows =
                if (!popupEnabled()) {
                    emptyList()
                } else if (omittedFirst) {
                    ranked.drop(1)
                } else {
                    ranked
                }
            if (rows != interaction.snapshot.suggestions) interaction.publish(rows)
        } finally {
            updating = false
        }
    }

    override fun close() {
        interaction.removeChangeListener(listener)
    }
}

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

import javax.swing.JComponent

/**
 * Host-pluggable visual surface for shell suggestions.
 *
 * The reusable suggestion controller owns navigation, acceptance, dismissal,
 * feedback, and popup state. A view only renders the supplied immutable items
 * and reports pointer interaction through the listener used to create it.
 * Implementations must update Swing component state only on the Event Dispatch
 * Thread and must not invoke completion providers or mutate command lines.
 */
interface SwingShellSuggestionView {
    /**
     * Component embedded and positioned by the owning Swing terminal.
     */
    val component: JComponent

    /**
     * Replaces the visual suggestion snapshot and selection.
     *
     * @param suggestions ordered, bounded suggestions to display.
     * @param selectedIndex selected item index, or `-1` for no selection.
     */
    fun update(
        suggestions: List<SwingShellSuggestion>,
        selectedIndex: Int,
    )

    /**
     * Releases host-specific presentation resources.
     *
     * The default implementation is resource-free. Hosts that own platform
     * editors, popups, listeners, or disposable scopes must release them here.
     */
    fun close() = Unit
}

/**
 * Pointer-interaction callback used by a [SwingShellSuggestionView].
 *
 * Selection and acceptance remain controller semantics; the view reports only
 * the item index under the relevant pointer gesture.
 */
interface SwingShellSuggestionViewListener {
    /**
     * Reports that the pointer moved over an item.
     *
     * @param index zero-based index in the current visual snapshot.
     */
    fun onSuggestionHovered(index: Int)

    /**
     * Reports an explicit primary-button click on an item.
     *
     * @param index zero-based index in the current visual snapshot.
     */
    fun onSuggestionClicked(index: Int)
}

/**
 * Creates one suggestion view for a reusable Swing terminal instance.
 *
 * Standalone hosts normally use [DEFAULT]. Platform hosts can supply a native
 * Swing implementation without replacing completion behavior.
 */
fun interface SwingShellSuggestionViewFactory {
    /**
     * Creates a view that reports pointer interaction to [listener].
     *
     * @param listener controller-owned interaction callback.
     * @return a new view owned by one terminal component.
     */
    fun create(listener: SwingShellSuggestionViewListener): SwingShellSuggestionView

    companion object {
        /**
         * Factory for KetraTerm's adaptive standalone suggestion surface.
         */
        @JvmField
        val DEFAULT: SwingShellSuggestionViewFactory =
            SwingShellSuggestionViewFactory { listener -> SwingShellSuggestionPopup(listener) }
    }
}

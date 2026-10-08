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

/**
 * Immutable complete ranking for one publication. Selection updates share its candidate storage.
 * Retain this snapshot with UI actions; indices from an older publication are never reinterpreted.
 *
 * @property suggestions immutable ordered candidates, shared by all presentations.
 * @property selectedIndex selected global index, or -1 for passive presentation.
 */
public class SwingShellSuggestionSnapshot internal constructor(
    internal val owner: SwingShellSuggestionInteraction,
    public val suggestions: List<SwingShellSuggestion>,
    public val selectedIndex: Int,
) {
    /** The selected candidate, or null for a passive presentation. */
    public val selectedSuggestion: SwingShellSuggestion? get() = suggestions.getOrNull(selectedIndex)
}

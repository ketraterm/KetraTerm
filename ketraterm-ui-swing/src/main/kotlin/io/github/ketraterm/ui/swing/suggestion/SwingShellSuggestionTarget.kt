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
 * Host-owned request and presentation lifecycle for optional automatic suggestions.
 *
 * Calls run on the EDT. The target owns source collection and presentation;
 * the supplied interaction owns selection, admission, and feedback. The terminal
 * owns focus, eligibility, debounce, invalidation, and session termination.
 * Capture occurs before target work begins. Native popups obtain component-local
 * anchors through [io.github.ketraterm.ui.swing.api.SwingTerminal.copyCellBounds].
 * Target exceptions propagate to the calling operation or component observation scope.
 * Changing the target or disposing the view never disposes host-owned target resources.
 */
public interface SwingShellSuggestionTarget {
    /** Replaces automatic work for [interaction]; cancel and close any previous request first. */
    public fun requestSuggestions(interaction: SwingShellSuggestionInteraction)

    /**
     * Cancels source work and hides the surface; must tolerate repeated calls.
     * The terminal closes active interactions. Hiding during acceptance must not
     * close its captured editing capability or report preference feedback.
     */
    public fun hideSuggestions()
}

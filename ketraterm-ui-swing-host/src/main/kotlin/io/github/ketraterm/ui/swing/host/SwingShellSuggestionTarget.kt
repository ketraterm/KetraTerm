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
package io.github.ketraterm.ui.swing.host

import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionInteraction

/**
 * Host-owned request and presentation lifecycle for optional automatic completion.
 *
 * Calls run on the EDT. The target owns provider collection and presentation;
 * the supplied interaction owns selection, admission, and feedback. The live
 * binding owns focus, eligibility, debounce, invalidation, and session termination.
 * Capture occurs before target work begins. Native popups obtain
 * component-local anchors through SwingTerminal.copyCellBounds. Target exceptions
 * propagate to the calling operation or observation scope; close still detaches
 * the binding if hiding fails. Closing the binding never disposes target resources.
 */
public interface SwingShellSuggestionTarget {
    /** Replaces automatic work for [interaction]; cancel and close any previous request first. */
    public fun requestSuggestions(interaction: SwingShellSuggestionInteraction)

    /**
     * Cancels provider work and hides the surface; must tolerate repeated calls.
     * The binding closes active interactions. Hiding during acceptance must not
     * close its captured editing capability or report preference feedback.
     */
    public fun hideSuggestions()
}

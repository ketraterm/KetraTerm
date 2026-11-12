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
 * Host-owned inline source. [open] captures resources and feedback on the EDT; keep it cheap.
 * The returned cold source is collected off the EDT. Popup and inline providers are independent.
 * Closing an interaction cancels its source.
 */
@ExperimentalShellSuggestionsApi
public fun interface SwingInlineShellSuggestionProvider {
    /** Returns request-owned work; the provider itself remains host-owned. */
    public fun open(request: SwingShellSuggestionRequest): SwingShellSuggestionSource
}

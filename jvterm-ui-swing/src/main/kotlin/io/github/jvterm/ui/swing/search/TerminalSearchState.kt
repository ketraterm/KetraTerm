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
package io.github.jvterm.ui.swing.search

/**
 * Public snapshot of the Swing terminal search UI.
 *
 * @property visible whether the overlay search bar is open.
 * @property query current literal search query.
 * @property resultCount number of matches in the current buffer snapshot.
 * @property activeResultIndex zero-based active match, or `-1` when no match is
 * active.
 */
data class TerminalSearchState(
    val visible: Boolean,
    val query: String,
    val resultCount: Int,
    val activeResultIndex: Int,
)

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
package io.github.ketraterm.ui.swing.search

/**
 * Public snapshot of terminal search results.
 *
 * @property query current literal search query.
 * @property resultCount matches found so far for a new query, or matches from
 * the last completed pass during a content refresh. While searching, the count
 * is provisional and may lag output; a new query starts with zero matches.
 * A capped pass reports 1,001: 1,000 collected matches plus one overflow witness.
 * Hosts should display `1,000+` when [isCountLimited] is true.
 * @property activeResultIndex zero-based active match, or `-1` when no match is
 * active or when a capped search has no known global match index.
 * @property isCountLimited true when more than 1,000 matches exist. The count is a
 * lower bound and the active global index is unavailable (`-1`); navigation remains unrestricted.
 * @property isSearching whether a background pass is pending or running.
 * @property failure failure of the last pass, or null. A failed pass is not
 * reported as a successful empty search; changing the query retries it.
 */
public data class TerminalSearchState
    @JvmOverloads
    constructor(
        val query: String,
        val resultCount: Int,
        val activeResultIndex: Int,
        val isSearching: Boolean = false,
        val failure: Throwable? = null,
    ) {
        /** Whether [resultCount] is a lower bound rather than an exact completed total. */
        public val isCountLimited: Boolean get() = resultCount > SEARCH_RESULT_LIMIT
    }

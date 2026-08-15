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
package io.github.ketraterm.completion.host

import io.github.ketraterm.completion.api.TerminalFileEntry

/**
 * Immutable, deterministically ordered direct-directory entry snapshot.
 *
 * @param entries bounded direct entries captured by a scanner.
 */
class TerminalDirectoryEntrySnapshot(
    entries: Collection<TerminalFileEntry>,
) {
    private val entries = entries.sortedWith(ENTRY_ORDER)

    /** Number of raw direct entries retained by this snapshot. */
    val size: Int get() = entries.size

    /**
     * Returns the first [limit] case-insensitive prefix matches.
     *
     * @param prefix direct-child name prefix.
     * @param limit positive maximum result count.
     * @return deterministic matching entries.
     */
    fun matching(
        prefix: String,
        limit: Int,
    ): List<TerminalFileEntry> {
        require(limit > 0) { "limit must be > 0, was $limit" }
        if (entries.isEmpty()) return emptyList()
        val matches = ArrayList<TerminalFileEntry>(minOf(entries.size, limit))
        for (entry in entries) {
            if (!entry.name.startsWith(prefix, ignoreCase = true)) continue
            matches += entry
            if (matches.size == limit) break
        }
        return matches
    }

    private companion object {
        private val ENTRY_ORDER =
            compareBy<TerminalFileEntry, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
                .thenBy { it.name }
    }
}

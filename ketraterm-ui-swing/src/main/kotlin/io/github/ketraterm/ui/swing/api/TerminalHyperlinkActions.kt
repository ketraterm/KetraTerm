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
package io.github.ketraterm.ui.swing.api

/** Stable occurrence ownership shared by retained segments. EDT-confined. */
internal class TerminalHyperlinkActions {
    private val entries = HashMap<Int, Entry>()
    private var nextId = 1
    private var cachedId = 0
    private var cachedHyperlink: SwingHyperlink? = null
    var followedId = 0
        private set

    fun add(hyperlink: SwingHyperlink): Int {
        check(nextId > 0) { "Hyperlink occurrence identity exhausted" }
        val id = -nextId++
        entries[id] = Entry(hyperlink)
        return id
    }

    /** Repeated stationary-hover frame reconciliation reuses one successful lookup without boxing. */
    fun get(id: Int): SwingHyperlink? {
        if (id == cachedId) return cachedHyperlink
        val hyperlink = entries[id]?.hyperlink ?: return null
        cachedId = id
        cachedHyperlink = hyperlink
        return hyperlink
    }

    /** Returns the previously followed occurrence so both styles can be repainted. */
    fun follow(id: Int): Int {
        val previous = followedId
        if (id in entries) followedId = id
        return previous
    }

    fun retain(id: Int) {
        checkNotNull(entries[id]) { "Unknown hyperlink occurrence $id" }.references++
    }

    fun release(id: Int) {
        val entry = checkNotNull(entries[id]) { "Unknown hyperlink occurrence $id" }
        check(entry.references > 0)
        if (--entry.references == 0) {
            entries.remove(id)
            if (cachedId == id) {
                cachedId = 0
                cachedHyperlink = null
            }
            if (followedId == id) followedId = 0
        }
    }

    private class Entry(
        val hyperlink: SwingHyperlink,
    ) {
        var references = 0
    }
}

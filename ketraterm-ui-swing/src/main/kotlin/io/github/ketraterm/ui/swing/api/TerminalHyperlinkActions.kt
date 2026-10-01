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

/** Stable occurrence ownership with unboxed lookup on hover/hit-test paths. EDT-confined. */
internal class TerminalHyperlinkActions {
    private var keys = IntArray(16)
    private var values = arrayOfNulls<SwingHyperlink>(16)
    private var references = IntArray(16)
    private var occupied = 0
    private var size = 0
    private var nextId = 1

    fun add(hyperlink: SwingHyperlink): Int {
        check(nextId > 0) { "Hyperlink occurrence identity exhausted" }
        if ((occupied + 1) * 2 >= keys.size) rebuild(if ((size + 1) * 4 >= keys.size) keys.size * 2 else keys.size)
        val key = nextId++
        var slot = hash(key) and (keys.size - 1)
        while (keys[slot] > 0) slot = (slot + 1) and (keys.size - 1)
        if (keys[slot] == 0) occupied++
        keys[slot] = key
        values[slot] = hyperlink
        references[slot] = 0
        size++
        return -key
    }

    fun get(id: Int): SwingHyperlink? = values[find(-id)]

    fun retain(id: Int) {
        references[find(-id)]++
    }

    fun release(id: Int) {
        val slot = find(-id)
        check(keys[slot] == -id && references[slot] > 0)
        if (--references[slot] == 0) {
            keys[slot] = -1
            values[slot] = null
            size--
        }
    }

    private fun find(key: Int): Int {
        var slot = hash(key) and (keys.size - 1)
        while (keys[slot] != 0 && keys[slot] != key) slot = (slot + 1) and (keys.size - 1)
        return slot
    }

    private fun hash(key: Int): Int {
        val mixed = key * -1640531527
        return mixed xor (mixed ushr 16)
    }

    private fun rebuild(capacity: Int) {
        val oldKeys = keys
        val oldValues = values
        val oldReferences = references
        keys = IntArray(capacity)
        values = arrayOfNulls(capacity)
        references = IntArray(capacity)
        occupied = size
        for (index in oldKeys.indices) {
            val key = oldKeys[index]
            if (key <= 0) continue
            val slot = find(key)
            keys[slot] = key
            values[slot] = oldValues[index]
            references[slot] = oldReferences[index]
        }
    }
}

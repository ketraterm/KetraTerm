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
    private var styles = IntArray(16 * STYLE_RECORD_SIZE)
    private var followedId = 0
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
        val presentation = hyperlink.presentation
        writeStyle(slot, 0, presentation.normal)
        writeStyle(slot, 1, presentation.hovered ?: presentation.normal)
        writeStyle(slot, 2, presentation.active ?: presentation.hovered ?: presentation.normal)
        writeStyle(slot, 3, presentation.followed ?: presentation.normal)
        size++
        return -key
    }

    fun get(id: Int): SwingHyperlink? = values[find(-id)]

    /** Primitive style record for painting; no actions or framework objects enter the paint path. */
    fun styleOffset(
        id: Int,
        hovered: Boolean,
        active: Boolean,
    ): Int {
        if (id >= 0) return -1
        val slot = find(-id)
        if (keys[slot] != -id) return -1
        val state =
            when {
                hovered && active -> 2
                hovered -> 1
                id == followedId -> 3
                else -> 0
            }
        return slot * STYLE_RECORD_SIZE + state * 4
    }

    fun foreground(offset: Int): Int = styles[offset]

    fun background(offset: Int): Int = styles[offset + 1]

    fun underlineColor(offset: Int): Int = styles[offset + 2]

    fun flags(offset: Int): Int = styles[offset + 3]

    /** Returns the previously followed occurrence so both styles can be repainted. */
    fun follow(id: Int): Int {
        val previous = followedId
        if (get(id) != null) followedId = id
        return previous
    }

    private fun writeStyle(
        slot: Int,
        state: Int,
        style: SwingHyperlinkStyle?,
    ) {
        val offset = slot * STYLE_RECORD_SIZE + state * 4
        styles[offset] = style?.foregroundArgb ?: 0
        styles[offset + 1] = style?.backgroundArgb ?: 0
        styles[offset + 2] = style?.underlineArgb ?: 0
        styles[offset + 3] =
            (if (style?.foregroundArgb != null) FOREGROUND else 0) or
            (if (style?.backgroundArgb != null) BACKGROUND else 0) or
            (if (style?.underlineArgb != null) UNDERLINE_COLOR else 0) or
            (if (style?.underlineThickness == 2) BOLD_UNDERLINE else 0) or
            (if (style?.underlineStyle != null) UNDERLINE_STYLE or (style.underlineStyle shl 8) else 0)
    }

    fun retain(id: Int) {
        references[find(-id)]++
    }

    fun release(id: Int) {
        val slot = find(-id)
        check(keys[slot] == -id && references[slot] > 0)
        if (--references[slot] == 0) {
            keys[slot] = -1
            values[slot] = null
            if (followedId == id) followedId = 0
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
        val oldStyles = styles
        keys = IntArray(capacity)
        values = arrayOfNulls(capacity)
        references = IntArray(capacity)
        styles = IntArray(capacity * STYLE_RECORD_SIZE)
        occupied = size
        for (index in oldKeys.indices) {
            val key = oldKeys[index]
            if (key <= 0) continue
            val slot = find(key)
            keys[slot] = key
            values[slot] = oldValues[index]
            references[slot] = oldReferences[index]
            oldStyles.copyInto(styles, slot * STYLE_RECORD_SIZE, index * STYLE_RECORD_SIZE, (index + 1) * STYLE_RECORD_SIZE)
        }
    }

    companion object {
        const val FOREGROUND = 1
        const val BACKGROUND = 2
        const val UNDERLINE_COLOR = 4
        const val UNDERLINE_STYLE = 8
        const val BOLD_UNDERLINE = 16
        private const val STYLE_RECORD_SIZE = 16
    }
}

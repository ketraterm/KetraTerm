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
package io.github.ketraterm.ui.swing.render.cache

import io.github.ketraterm.ui.swing.render.SwingColors

/** Renderer-owned direct-mapped primitive cache; truecolor churn has bounded storage and no entry objects. */
internal class TextContrastCache {
    private val keys = LongArray(1024)
    private val values = IntArray(keys.size)
    private val occupied = BooleanArray(keys.size)
    private var minimumRatio = 1.0

    fun configure(minimumRatio: Double) {
        if (this.minimumRatio == minimumRatio) return
        this.minimumRatio = minimumRatio
        occupied.fill(false)
    }

    fun resolve(
        foreground: Int,
        background: Int,
    ): Int {
        if (minimumRatio <= 1.0) return foreground
        val key = (foreground.toLong() shl 32) or (background.toLong() and 0xffffffffL)
        var hash = key xor (key ushr 33)
        hash *= -49064778989728563L
        val slot = (hash xor (hash ushr 33)).toInt() and (keys.size - 1)
        if (occupied[slot] && keys[slot] == key) return values[slot]
        val resolved = SwingColors.ensureContrast(foreground, background, minimumRatio)
        keys[slot] = key
        values[slot] = resolved
        occupied[slot] = true
        return resolved
    }
}

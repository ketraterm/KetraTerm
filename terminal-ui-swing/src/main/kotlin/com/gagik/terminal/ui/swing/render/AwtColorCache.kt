package com.gagik.terminal.ui.swing.render

import java.awt.Color

/**
 * Primitive-keyed cache for packed ARGB AWT colors.
 *
 * Rendering resolves colors as packed integers in hot paths. This cache avoids
 * per-cell boxing and only creates [Color] instances the first time a distinct
 * ARGB value is painted.
 */
internal class AwtColorCache(
    initialCapacity: Int = DEFAULT_CAPACITY,
) {
    private var keys = IntArray(normalizeCapacity(initialCapacity))
    private var values = arrayOfNulls<Color>(keys.size)
    private var used = BooleanArray(keys.size)
    private var size = 0

    /**
     * Returns a cached AWT color for [argb].
     *
     * @param argb packed ARGB color.
     * @return reusable AWT color instance.
     */
    fun color(argb: Int): Color {
        var index = index(argb, keys.size)
        while (used[index]) {
            if (keys[index] == argb) {
                return values[index]!!
            }
            index = (index + 1) and (keys.size - 1)
        }

        if ((size + 1) * 2 >= keys.size) {
            grow()
            index = index(argb, keys.size)
            while (used[index]) {
                index = (index + 1) and (keys.size - 1)
            }
        }

        val color = Color(argb, true)
        used[index] = true
        keys[index] = argb
        values[index] = color
        size++
        return color
    }

    private fun grow() {
        val oldKeys = keys
        val oldValues = values
        val oldUsed = used

        keys = IntArray(oldKeys.size * 2)
        values = arrayOfNulls(keys.size)
        used = BooleanArray(keys.size)
        size = 0

        var oldIndex = 0
        while (oldIndex < oldKeys.size) {
            if (oldUsed[oldIndex]) {
                insertExisting(oldKeys[oldIndex], oldValues[oldIndex]!!)
            }
            oldIndex++
        }
    }

    private fun insertExisting(argb: Int, color: Color) {
        var index = index(argb, keys.size)
        while (used[index]) {
            index = (index + 1) and (keys.size - 1)
        }
        used[index] = true
        keys[index] = argb
        values[index] = color
        size++
    }

    private companion object {
        private const val DEFAULT_CAPACITY = 64

        private fun normalizeCapacity(capacity: Int): Int {
            var normalized = 1
            while (normalized < capacity) {
                normalized = normalized shl 1
            }
            return maxOf(DEFAULT_CAPACITY, normalized)
        }

        private fun index(argb: Int, capacity: Int): Int {
            val mixed = argb xor (argb ushr 16)
            return mixed and (capacity - 1)
        }
    }
}

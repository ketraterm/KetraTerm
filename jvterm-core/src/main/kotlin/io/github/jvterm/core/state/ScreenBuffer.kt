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
package io.github.jvterm.core.state

import io.github.jvterm.core.buffer.HistoryRing
import io.github.jvterm.core.model.Cursor
import io.github.jvterm.core.model.Line
import io.github.jvterm.core.model.SavedCursorState
import io.github.jvterm.core.store.ClusterStore

/**
 * Represents a single cohesive terminal screen (primary or alternate).
 *
 * Each [ScreenBuffer] owns its entire memory arena: the ring, the cluster
 * store, the cursor, the DECSC save slot, and the active scroll margins.
 *
 * Memory ownership:
 * [store] and [ring] are always co-owned. Every [Line] in the ring holds a
 * reference to [store]. The pair must always be replaced together; never
 * replace one without the other. [replaceStorage] is the single safe entry
 * point for doing this.
 *
 * Resize lifecycle:
 * - the primary buffer must be reflowed by `TerminalResizer` so scrollback survives
 * - the alternate buffer is always wiped via [replaceStorage]
 * - alternate content is transient and recreated on each alt-screen entry
 */
internal class ScreenBuffer(
    initialWidth: Int,
    initialHeight: Int,
    val maxHistory: Int,
) {
    var store = ClusterStore()
        internal set

    var ring = HistoryRing(maxHistory + initialHeight) { Line(initialWidth, store) }
        internal set

    val cursor = Cursor()
    val savedCursor = SavedCursorState()

    var kittyKeyboardFlags: Int = 0
    internal val kittyKeyboardStack = IntArray(32)
    internal var kittyKeyboardDepth = 0
    internal var kittyKeyboardInitialFlags = 0
    internal var hasSavedInitialFlags = false

    var scrollTop: Int = 0
        private set
    var scrollBottom: Int = initialHeight - 1
        private set

    var leftMargin: Int = 0
        private set
    var rightMargin: Int = initialWidth - 1
        private set

    fun isFullViewportScroll(viewportHeight: Int): Boolean = scrollTop == 0 && scrollBottom == viewportHeight - 1

    /**
     * DECSTBM: sets the scroll margins and homes the cursor per the VT spec.
     *
     * [top] and [bottom] are 1-based per the escape sequence convention.
     * Degenerate ranges are silently ignored.
     */
    fun setScrollRegion(
        top: Int,
        bottom: Int,
        isOriginMode: Boolean,
        viewportHeight: Int,
        homeCol: Int = 0,
    ) {
        val t = (top - 1).coerceIn(0, viewportHeight - 1)
        val b = (bottom - 1).coerceIn(0, viewportHeight - 1)
        if (t >= b) return
        scrollTop = t
        scrollBottom = b
        cursor.col = homeCol.coerceIn(0, rightMargin)
        cursor.row = if (isOriginMode) t else 0
        cursor.pendingWrap = false
    }

    /** Resets scroll margins to the full viewport. */
    fun resetScrollRegion(viewportHeight: Int) {
        scrollTop = 0
        scrollBottom = viewportHeight - 1
    }

    /**
     * Sets the horizontal margins from 1-based DECSLRM parameters.
     *
     * Returns `true` when a non-degenerate range was applied and `false` when
     * the request was ignored.
     */
    fun setLeftRightMargins(
        left: Int,
        right: Int,
        viewportWidth: Int,
    ): Boolean {
        val l = (left - 1).coerceIn(0, viewportWidth - 1)
        val r = (right - 1).coerceIn(0, viewportWidth - 1)
        if (l >= r) return false
        leftMargin = l
        rightMargin = r
        return true
    }

    /** Resets horizontal margins to the full viewport width. */
    fun resetLeftRightMargins(viewportWidth: Int) {
        leftMargin = 0
        rightMargin = viewportWidth - 1
    }

    /**
     * Clamps the DECSC save slot to the current viewport bounds after a resize.
     *
     * Pending wrap is preserved only if the clamped cursor still lands on the
     * new right margin; otherwise it is cleared to avoid stale phantom-column
     * state when DECRC restores later.
     */
    fun clampSavedCursorToBounds(
        newWidth: Int,
        newHeight: Int,
    ) {
        if (!savedCursor.isSaved) return
        savedCursor.col = savedCursor.col.coerceIn(0, newWidth - 1)
        savedCursor.row = savedCursor.row.coerceIn(0, newHeight - 1)
        savedCursor.pendingWrap = savedCursor.pendingWrap && savedCursor.col == newWidth - 1
    }

    /**
     * Clears every currently live line to release cluster handles, then rebuilds
     * the visible viewport as blank lines while reusing the existing ring and store.
     *
     * This is the safe history-destroying path when the arena pair stays in
     * place: every live line must be cleared before logical reachability is
     * dropped, otherwise cluster payloads can leak in the shared [store].
     */
    fun clearGrid(
        penAttr: Long,
        viewportHeight: Int,
    ) {
        clearGrid(penAttr, 0L, viewportHeight)
    }

    fun clearGrid(
        penAttr: Long,
        penExtendedAttr: Long,
        viewportHeight: Int,
    ) {
        for (i in 0 until ring.size) {
            ring[i].clear(penAttr, penExtendedAttr)
        }
        ring.clear()
        repeat(viewportHeight) { ring.push().clear(penAttr, penExtendedAttr) }
    }

    /**
     * Replaces the entire memory arena with a fresh store and ring sized to
     * [newWidth] x [newHeight], then fills the new ring with blank lines.
     *
     * This is the only safe way to swap [ring] and [store]; callers must never
     * replace them independently because every [Line] in the ring closes over
     * the active store instance.
     */
    fun replaceStorage(
        newWidth: Int,
        newHeight: Int,
        penAttr: Long,
        penExtendedAttr: Long = 0L,
    ) {
        store = ClusterStore()
        ring = HistoryRing(maxHistory + newHeight) { Line(newWidth, store) }
        repeat(newHeight) { ring.push().clear(penAttr, penExtendedAttr) }
        scrollTop = 0
        scrollBottom = newHeight - 1
        leftMargin = 0
        rightMargin = newWidth - 1

        cursor.col = cursor.col.coerceIn(0, maxOf(0, newWidth - 1))
        cursor.row = cursor.row.coerceIn(0, maxOf(0, newHeight - 1))
        cursor.pendingWrap = false

        if (savedCursor.isSaved) {
            savedCursor.col = savedCursor.col.coerceIn(0, maxOf(0, newWidth - 1))
            savedCursor.row = savedCursor.row.coerceIn(0, maxOf(0, newHeight - 1))
            savedCursor.pendingWrap = false
        }
    }

    fun pushKittyKeyboardFlags(
        flags: Int,
        currentFlags: Int,
    ): Int {
        if (!hasSavedInitialFlags) {
            kittyKeyboardInitialFlags = currentFlags
            hasSavedInitialFlags = true
        }
        if (kittyKeyboardDepth >= kittyKeyboardStack.size) {
            System.arraycopy(kittyKeyboardStack, 1, kittyKeyboardStack, 0, kittyKeyboardStack.size - 1)
            kittyKeyboardStack[kittyKeyboardStack.size - 1] = currentFlags
        } else {
            kittyKeyboardStack[kittyKeyboardDepth] = currentFlags
            kittyKeyboardDepth++
        }
        kittyKeyboardFlags = flags
        return flags
    }

    fun popKittyKeyboardFlags(
        count: Int,
        currentFlags: Int,
    ): Int {
        var nextFlags = currentFlags
        repeat(count) {
            if (kittyKeyboardDepth > 0) {
                kittyKeyboardDepth--
                nextFlags = kittyKeyboardStack[kittyKeyboardDepth]
            } else {
                if (hasSavedInitialFlags) {
                    nextFlags = kittyKeyboardInitialFlags
                } else {
                    nextFlags = 0
                }
            }
        }
        kittyKeyboardFlags = nextFlags
        return nextFlags
    }

    fun clearKittyKeyboardStack() {
        kittyKeyboardDepth = 0
        kittyKeyboardInitialFlags = 0
        hasSavedInitialFlags = false
        kittyKeyboardFlags = 0
    }
}

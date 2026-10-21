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
package io.github.ketraterm.core.graphics

import io.github.ketraterm.protocol.TerminalGraphicsLimits
import io.github.ketraterm.render.api.TerminalRenderImage
import io.github.ketraterm.render.api.TerminalRenderImageSink
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Caller-serialized image resources and primitive placements for both terminal screens.
 *
 * Screen flags use false for primary and true for alternate; both share one budget.
 * Image slots remain stable until eviction/replacement, while placement removal moves
 * the last record into the gap and repairs [placementIndex]. [references] counts live
 * placement records per slot, not references held by published frames. Published
 * [TerminalRenderImage] resources remain immutable after the store releases them.
 *
 * Placement anchors and clip edges use physical cells relative to the live screen;
 * negative rows belong to history. Pixel crops and offsets retain their pixel units.
 */
internal class GraphicsStore {
    /** Invalidates published placements or geometry; unrelated to text-row generations. */
    var generation = 0L
        private set

    /** Reset/screen-switch epoch observed by the parser's incomplete-transfer owner. */
    var transferGeneration = 0L
        private set

    // Last usable dimensions also define margin clipping while pixel reporting is unavailable.
    private var cellWidth = 0
    private var cellHeight = 0
    private var geometryAvailable = false
    private var clock = 0L
    private var nextId = 1L
    private var retainedBytes = 0L
    private var images = arrayOfNulls<TerminalRenderImage>(0)
    private var ids = LongArray(0)
    private var numbers = LongArray(0)
    private var created = LongArray(0)
    private var used = LongArray(0)
    private var screens = BooleanArray(0)
    private var references = IntArray(0)
    private var count = 0
    private var records = IntArray(0)

    // Exact retained source crop. Integer SOURCE_Y/HEIGHT retain the original crop's bounds.
    private var sourceY = DoubleArray(0)
    private var sourceHeight = DoubleArray(0)
    private var placementIds = LongArray(0)
    private var placementSequence = LongArray(0)
    private var order = IntArray(0)
    private var orderGeneration = -1L
    private val placementIndex = PlacementIndex()

    fun invalidateTransfer() {
        transferGeneration++
    }

    fun changed() {
        generation++
    }

    /** Returns the matching image slot, or -1; number lookup selects the newest retained match. */
    fun find(
        screen: Boolean,
        id: Long,
        number: Long,
    ): Int {
        var found = -1
        for (i in images.indices) {
            if (images[i] != null &&
                screens[i] == screen &&
                (if (id != 0L) ids[i] == id else number != 0L && numbers[i] == number) &&
                (found < 0 || created[i] > created[found])
            ) {
                found = i
            }
        }
        return found
    }

    fun image(slot: Int): TerminalRenderImage = checkNotNull(images[slot])

    fun id(slot: Int): Long = ids[slot]

    fun allocateId(screen: Boolean): Long {
        while (true) {
            val id = nextId
            nextId = if (nextId == 4294967295L) 1L else nextId + 1
            if (find(screen, id, 0) < 0) return id
        }
    }

    /**
     * Commits a validated immutable resource and returns its slot. The caller must
     * validate its dimensions/pixel budget and any requested placement first: this
     * step removes old placements for a reused nonzero ID and may evict resources.
     * Unplaced resources are evicted first, then by the logical usage counter.
     */
    fun retain(
        screen: Boolean,
        id: Long,
        number: Long,
        image: TerminalRenderImage,
    ): Int {
        val old = if (id != 0L) find(screen, id, 0) else -1
        val bytes = image.width.toLong() * image.height * 4
        if (old >= 0) removeImage(old)
        if (images.isEmpty()) {
            images = arrayOfNulls(TerminalGraphicsLimits.IMAGES)
            ids = LongArray(images.size)
            numbers = LongArray(images.size)
            created = LongArray(images.size)
            used = LongArray(images.size)
            screens = BooleanArray(images.size)
            references = IntArray(images.size)
        }
        while (retainedBytes + bytes > TerminalGraphicsLimits.RETAINED_BYTES || images.indexOfFirst { it == null } < 0) {
            var victim = -1
            for (i in images.indices) {
                if (images[i] == null) continue
                if (victim < 0 ||
                    (references[i] == 0 && references[victim] != 0) ||
                    ((references[i] == 0) == (references[victim] == 0) && used[i] < used[victim])
                ) {
                    victim = i
                }
            }
            check(victim >= 0)
            removeImage(victim)
        }
        val slot = images.indexOfFirst { it == null }
        images[slot] = image
        ids[slot] = id
        numbers[slot] = number
        screens[slot] = screen
        created[slot] = ++clock
        used[slot] = clock
        retainedBytes += bytes
        changed()
        return slot
    }

    /**
     * Checks record capacity without mutation. A negative [slot] denotes a new image;
     * [replacing] means committing a new resource will release that slot's placements.
     */
    fun canPlace(
        slot: Int,
        placementId: Long,
        replacing: Boolean,
    ): Boolean =
        count < TerminalGraphicsLimits.PLACEMENTS ||
            (slot >= 0 && (if (replacing) references[slot] > 0 else findPlacement(slot, placementId) >= 0))

    /**
     * Copies one validated STRIDE-sized record after [canPlace] succeeds. Nonzero
     * image/placement ID pairs replace a record; anonymous or zero-ID placements append.
     */
    fun put(
        slot: Int,
        placementId: Long,
        values: IntArray,
    ) {
        var index = findPlacement(slot, placementId)
        if (index < 0) {
            if (count == placementIds.size) {
                val capacity = (count.coerceAtLeast(16) * 2).coerceAtMost(TerminalGraphicsLimits.PLACEMENTS)
                records = records.copyOf(capacity * STRIDE)
                sourceY = sourceY.copyOf(capacity)
                sourceHeight = sourceHeight.copyOf(capacity)
                placementIds = placementIds.copyOf(capacity)
                placementSequence = placementSequence.copyOf(capacity)
                order = order.copyOf(capacity)
            }
            index = count++
            placementSequence[index] = ++clock
            references[slot]++
        }
        values.copyInto(records, index * STRIDE, 0, STRIDE)
        records[index * STRIDE + SLOT] = slot
        sourceY[index] = values[SOURCE_Y].toDouble()
        sourceHeight[index] = values[SOURCE_HEIGHT].toDouble()
        placementIds[index] = placementId
        if (placementId != 0L && ids[slot] != 0L) placementIndex.put((slot.toLong() shl 32) or placementId, index)
        used[slot] = ++clock
        changed()
    }

    private fun findPlacement(
        slot: Int,
        placementId: Long,
    ): Int {
        if (placementId == 0L || ids[slot] == 0L) return -1
        return placementIndex.get((slot.toLong() shl 32) or placementId)
    }

    /**
     * Clears the selected screen regions. History-only clearing clips a placement
     * crossing row zero so its visible portion survives. [data] releases every image
     * on this screen and all its placements, regardless of the region flags.
     */
    fun clear(
        screen: Boolean,
        viewport: Boolean = true,
        history: Boolean = true,
        data: Boolean = false,
    ) {
        var i = count - 1
        while (i >= 0) {
            val base = i * STRIDE
            if (screens[records[base + SLOT]] == screen &&
                (
                    (viewport && records[base + ROW].toLong() + records[base + EFFECTIVE_ROWS] > 0) ||
                        (history && records[base + ROW].toLong() + records[base + EFFECTIVE_ROWS] <= 0)
                )
            ) {
                removePlacement(i)
            } else if (history && !viewport && screens[records[base + SLOT]] == screen && records[base + ROW] < 0) {
                records[base + CLIP_TOP] = maxOf(records[base + CLIP_TOP], 0)
                changed()
            }
            i--
        }
        if (data) for (slot in images.indices) if (images[slot] != null && screens[slot] == screen) removeImage(slot)
    }

    /**
     * Applies a validated Kitty deletion selector. Coordinate selectors use one-based
     * [x]/[y]; the cursor selector uses zero-based [cursorX]/[cursorY]. Uppercase forms
     * also release selected resources when no placements reference them.
     */
    fun delete(
        screen: Boolean,
        selector: Char,
        id: Long,
        number: Long,
        placementId: Long,
        x: Long,
        y: Long,
        z: Long,
        cursorX: Int,
        cursorY: Int,
    ) {
        val lower = selector.lowercaseChar()
        val selectedNumber = if (lower == 'n') find(screen, 0, number) else -1
        for (slot in images.indices) {
            if (images[slot] == null || screens[slot] != screen) continue
            val imageSelected =
                when (lower) {
                    'i' -> id != 0L && ids[slot] == id
                    'n' -> slot == selectedNumber
                    'r' -> ids[slot] != 0L && ids[slot] in x..y
                    else -> true
                }
            if (!imageSelected) continue
            var removed = false
            var i = count - 1
            while (i >= 0) {
                val b = i * STRIDE
                if (records[b + SLOT] == slot) {
                    val px = if (lower == 'c') cursorX.toLong() else x - 1
                    val py = if (lower == 'c') cursorY.toLong() else y - 1
                    val hitX = px >= records[b + COLUMN] && px < records[b + COLUMN].toLong() + records[b + EFFECTIVE_COLUMNS]
                    val hitY =
                        py >= maxOf(records[b + ROW], records[b + CLIP_TOP]) &&
                            py < minOf(records[b + ROW].toLong() + records[b + EFFECTIVE_ROWS], records[b + CLIP_BOTTOM].toLong())
                    val selected =
                        when (lower) {
                            'i', 'n' -> placementId == 0L || placementIds[i] == placementId
                            'r' -> true
                            'a' -> records[b + ROW].toLong() + records[b + EFFECTIVE_ROWS] > 0
                            'x' -> hitX
                            'y' -> hitY
                            'p', 'c' -> hitX && hitY
                            'q' -> hitX && hitY && records[b + Z].toLong() == z
                            'z' -> records[b + Z].toLong() == z
                            else -> false
                        }
                    if (selected) {
                        removePlacement(i)
                        removed = true
                    }
                }
                i--
            }
            if (selector.isUpperCase() &&
                references[slot] == 0 &&
                (removed || (lower in "inr" && placementId == 0L))
            ) {
                removeImage(slot)
            }
        }
    }

    private fun removePlacement(index: Int) {
        val slot = records[index * STRIDE + SLOT]
        if (placementIds[index] != 0L && ids[slot] != 0L) placementIndex.remove((slot.toLong() shl 32) or placementIds[index])
        references[slot]--
        count--
        if (index != count) {
            records.copyInto(records, index * STRIDE, count * STRIDE, (count + 1) * STRIDE)
            sourceY[index] = sourceY[count]
            sourceHeight[index] = sourceHeight[count]
            placementIds[index] = placementIds[count]
            placementSequence[index] = placementSequence[count]
            val movedSlot = records[index * STRIDE + SLOT]
            if (placementIds[index] != 0L &&
                ids[movedSlot] != 0L
            ) {
                placementIndex.put((movedSlot.toLong() shl 32) or placementIds[index], index)
            }
        }
        changed()
    }

    private fun removeImage(slot: Int) {
        val image = images[slot] ?: return
        var i = count - 1
        while (i >= 0) {
            if (records[i * STRIDE + SLOT] == slot) removePlacement(i)
            i--
        }
        retainedBytes -= image.width.toLong() * image.height * 4
        images[slot] = null
        changed()
    }

    /**
     * Moves placements by signed cell-row [delta] (negative is upward). Region bounds
     * are zero-based and inclusive; [historySize] is the retained size after scrolling.
     * Margin scrolling moves and permanently crops contained placements, independently
     * of text history admission. [wholeViewport] also moves oversized and off-bottom
     * images; downward scrolling leaves wholly historical placements in place unless
     * [intoHistory] requests history movement. History retention bounds still apply.
     */
    fun scroll(
        screen: Boolean,
        top: Int,
        bottom: Int,
        left: Int,
        right: Int,
        delta: Int,
        intoHistory: Boolean,
        historySize: Int,
        wholeViewport: Boolean = false,
    ) {
        if (count == 0) return
        var i = count - 1
        while (i >= 0) {
            val b = i * STRIDE
            if (screens[records[b + SLOT]] == screen) {
                val row = records[b + ROW]
                val end = row.toLong() + records[b + EFFECTIVE_ROWS]
                val inRegion =
                    records[b + COLUMN] >= left &&
                        records[b + COLUMN].toLong() + records[b + EFFECTIVE_COLUMNS] <= right.toLong() + 1 &&
                        maxOf(row, records[b + CLIP_TOP]) >= top &&
                        minOf(end, records[b + CLIP_BOTTOM].toLong()) <= bottom.toLong() + 1
                val moves = if (wholeViewport) delta <= 0 || intoHistory || end > 0 else inRegion
                if (moves) {
                    records[b + ROW] = (row.toLong() + delta).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
                    val clipTop = if (wholeViewport) -historySize else Int.MIN_VALUE
                    records[b + CLIP_TOP] =
                        maxOf(clipTop.toLong(), records[b + CLIP_TOP].toLong() + delta)
                            .coerceAtMost(Int.MAX_VALUE.toLong())
                            .toInt()
                    if (records[b + CLIP_BOTTOM] != Int.MAX_VALUE) {
                        records[b + CLIP_BOTTOM] =
                            (records[b + CLIP_BOTTOM].toLong() + delta)
                                .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
                                .toInt()
                    }
                    if ((!wholeViewport && !cropToMargins(i, top, bottom)) ||
                        records[b + ROW].toLong() + records[b + EFFECTIVE_ROWS] <= records[b + CLIP_TOP] ||
                        records[b + ROW] >= records[b + CLIP_BOTTOM]
                    ) {
                        removePlacement(i)
                    }
                    changed()
                }
            }
            i--
        }
    }

    /**
     * Crops a moved, previously contained placement in source pixels. Fractional
     * pixels must survive font/cell resizing; a permanent cell-space clip would
     * otherwise select different pixels after geometry changes.
     */
    private fun cropToMargins(
        index: Int,
        top: Int,
        bottom: Int,
    ): Boolean {
        val b = index * STRIDE
        val row = records[b + ROW]
        val end = row.toLong() + records[b + EFFECTIVE_ROWS]
        if (end <= top || row > bottom) return false
        if (row >= top && end <= bottom.toLong() + 1) return true
        val height = sourceHeight[index]
        val scale = height / (destinationHeight(records, b, cellWidth, cellHeight, height) * cellHeight)
        if (row < top) {
            val clippedRows = top - row
            val removedPixels = scale * (clippedRows.toDouble() * cellHeight - records[b + OFFSET_Y])
            if (removedPixels >= height) return false
            sourceY[index] += removedPixels
            sourceHeight[index] -= removedPixels
            records[b + EFFECTIVE_ROWS] -= clippedRows
            if (records[b + ROWS] > 0) records[b + ROWS] -= clippedRows
            records[b + OFFSET_Y] = 0
            records[b + ROW] = top
        } else if (end > bottom.toLong() + 1) {
            val clippedRows = (end - bottom - 1).toInt()
            val remainingRows = records[b + EFFECTIVE_ROWS] - clippedRows
            val remainingPixels = scale * (remainingRows.toDouble() * cellHeight - records[b + OFFSET_Y])
            if (remainingPixels <= 0) return false
            sourceHeight[index] = minOf(height, remainingPixels)
            records[b + EFFECTIVE_ROWS] = remainingRows
            if (records[b + ROWS] > 0) records[b + ROWS] -= clippedRows
        }
        return true
    }

    /**
     * Shifts primary anchors by the change in live-screen top and applies the new
     * history bound. The caller supplies zero for width reflow, which preserves
     * physical graphics coordinates. Alternate resources follow grid recreation.
     */
    fun resizePrimary(
        rowShift: Int,
        historySize: Int,
    ) {
        var i = count - 1
        while (i >= 0) {
            val b = i * STRIDE
            if (!screens[records[b + SLOT]]) {
                records[b + ROW] = (records[b + ROW].toLong() - rowShift).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
                records[b + CLIP_TOP] =
                    maxOf(-historySize.toLong(), records[b + CLIP_TOP].toLong() - rowShift)
                        .coerceAtMost(Int.MAX_VALUE.toLong())
                        .toInt()
                if (records[b + CLIP_BOTTOM] != Int.MAX_VALUE) {
                    records[b + CLIP_BOTTOM] =
                        (records[b + CLIP_BOTTOM].toLong() - rowShift)
                            .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
                            .toInt()
                }
                if (records[b + ROW].toLong() + records[b + EFFECTIVE_ROWS] <= records[b + CLIP_TOP] ||
                    records[b + ROW] >= records[b + CLIP_BOTTOM]
                ) {
                    removePlacement(i)
                }
            }
            i--
        }
        clear(true, data = true)
        changed()
    }

    /** Recomputes cell spans after a pixel-size change; returns whether graphics need publication. */
    fun geometryChanged(
        cellWidth: Int,
        cellHeight: Int,
    ): Boolean {
        val available = cellWidth > 0 && cellHeight > 0
        if (geometryAvailable == available && (!available || (this.cellWidth == cellWidth && this.cellHeight == cellHeight))) return false
        geometryAvailable = available
        if (available) {
            this.cellWidth = cellWidth
            this.cellHeight = cellHeight
            for (i in 0 until count) {
                val b = i * STRIDE
                records[b + OFFSET_X] = minOf(records[b + OFFSET_X], cellWidth - 1)
                records[b + OFFSET_Y] = minOf(records[b + OFFSET_Y], cellHeight - 1)
                resolveSpan(records, b, cellWidth, cellHeight, sourceHeight[i])
            }
        }
        if (count == 0) return false
        changed()
        return true
    }

    /**
     * Emits visible placements in stacking order with fractional viewport-cell geometry.
     * [offset] is the nonnegative scrollback offset; [rows] is the requested viewport
     * height. Pixel resources are shared with [sink], never copied here. Unknown cell
     * dimensions suppress publication until usable pixel geometry is available.
     */
    fun copy(
        screen: Boolean,
        offset: Int,
        rows: Int,
        cellWidth: Int,
        cellHeight: Int,
        sink: TerminalRenderImageSink,
    ) {
        if (count == 0 || cellWidth <= 0 || cellHeight <= 0) return
        sort()
        for (o in 0 until count) {
            val index = order[o]
            val b = index * STRIDE
            val slot = records[b + SLOT]
            if (screens[slot] != screen) continue
            val x = minOf(records[b + OFFSET_X], cellWidth - 1).toDouble() / cellWidth
            val y = minOf(records[b + OFFSET_Y], cellHeight - 1).toDouble() / cellHeight
            val w = destinationWidth(records, b, cellWidth, cellHeight, sourceHeight[index])
            val h = destinationHeight(records, b, cellWidth, cellHeight, sourceHeight[index])
            val destinationTop = records[b + ROW].toDouble() + offset + y
            val top = maxOf(records[b + CLIP_TOP].toDouble() + offset, destinationTop)
            val bottom = minOf(records[b + CLIP_BOTTOM].toDouble() + offset, destinationTop + h)
            if (bottom <= 0 || top >= rows || bottom <= top) continue

            // The render API takes integer source rectangles. Enclose the exact crop,
            // expand its destination by the same scale, then clip back to the exact
            // destination: fractional source edges remain precise without new arrays.
            val sourceTop = floor(sourceY[index]).toInt()
            val sourceBottom =
                minOf(
                    records[b + SOURCE_Y] + records[b + SOURCE_HEIGHT],
                    ceil(sourceY[index] + sourceHeight[index]).toInt(),
                )
            val scale = h / sourceHeight[index]
            sink.accept(
                image(slot),
                records[b + COLUMN] + x,
                destinationTop - (sourceY[index] - sourceTop) * scale,
                w,
                (sourceBottom - sourceTop) * scale,
                records[b + SOURCE_X],
                sourceTop,
                records[b + SOURCE_WIDTH],
                sourceBottom - sourceTop,
                top,
                bottom,
                records[
                    b +
                        Z,
                ],
            )
        }
    }

    private fun sort() {
        if (orderGeneration == generation) return
        for (i in 0 until count) order[i] = i

        // In-place heapsort keeps frame publication bounded without boxed comparators.
        fun down(
            start: Int,
            size: Int,
        ) {
            var root = start
            while (root * 2 + 1 < size) {
                var child = root * 2 + 1
                if (child + 1 < size && compare(order[child], order[child + 1]) < 0) child++
                if (compare(order[root], order[child]) >= 0) return
                val tmp = order[root]
                order[root] = order[child]
                order[child] = tmp
                root = child
            }
        }
        for (i in count / 2 - 1 downTo 0) down(i, count)
        for (end in count - 1 downTo 1) {
            val tmp = order[0]
            order[0] = order[end]
            order[end] = tmp
            down(0, end)
        }
        orderGeneration = generation
    }

    private fun compare(
        a: Int,
        b: Int,
    ): Int {
        val z = records[a * STRIDE + Z].compareTo(records[b * STRIDE + Z])
        if (z != 0) return z
        val resource = created[records[a * STRIDE + SLOT]].compareTo(created[records[b * STRIDE + SLOT]])
        return if (resource != 0) resource else placementSequence[a].compareTo(placementSequence[b])
    }

    companion object {
        // Primitive placement record layout. ROW/COLUMN and requested/effective spans
        // are cells; OFFSET/SOURCE fields are pixels. CLIP_BOTTOM is exclusive, with
        // Int.MIN_VALUE/Int.MAX_VALUE denoting initially unbounded vertical clipping.
        const val SLOT = 0
        const val COLUMN = 1
        const val ROW = 2
        const val OFFSET_X = 3
        const val OFFSET_Y = 4
        const val SOURCE_X = 5
        const val SOURCE_Y = 6
        const val SOURCE_WIDTH = 7
        const val SOURCE_HEIGHT = 8
        const val COLUMNS = 9
        const val ROWS = 10
        const val Z = 11
        const val CLIP_TOP = 12
        const val CLIP_BOTTOM = 13
        const val EFFECTIVE_COLUMNS = 14
        const val EFFECTIVE_ROWS = 15
        const val STRIDE = 16

        /**
         * Writes effective integer cell spans into a record; false rejects an overflowing
         * placement. Positive [cw]/[ch] are required. Kitty's cursor span includes offsets
         * before aspect fitting; destination geometry subtracts them from specified spans.
         * Retained placements supply their exact fractional [sourceHeight] after margin cropping.
         */
        fun resolveSpan(
            v: IntArray,
            b: Int,
            cw: Int,
            ch: Int,
            sourceHeight: Double = v[b + SOURCE_HEIGHT].toDouble(),
        ): Boolean {
            val x = minOf(v[b + OFFSET_X], cw - 1)
            val y = minOf(v[b + OFFSET_Y], ch - 1)
            val w = v[b + SOURCE_WIDTH]
            val h = sourceHeight
            val columns =
                if (v[b + COLUMNS] > 0) {
                    v[b + COLUMNS].toDouble()
                } else if (v[b + ROWS] > 0) {
                    ceil((v[b + ROWS].toDouble() * ch + y) * w / h / cw)
                } else {
                    ceil((w.toDouble() + x) / cw)
                }
            val rows =
                if (v[b + ROWS] > 0) {
                    v[b + ROWS].toDouble()
                } else if (v[b + COLUMNS] > 0) {
                    ceil((v[b + COLUMNS].toDouble() * cw + x) * h / w / ch)
                } else {
                    ceil((h.toDouble() + y) / ch)
                }
            v[b + EFFECTIVE_COLUMNS] = columns.coerceAtMost(Int.MAX_VALUE.toDouble()).toInt()
            v[b + EFFECTIVE_ROWS] = rows.coerceAtMost(Int.MAX_VALUE.toDouble()).toInt()
            return columns <= Int.MAX_VALUE && rows <= Int.MAX_VALUE
        }

        fun destinationWidth(
            v: IntArray,
            b: Int,
            cw: Int,
            ch: Int,
            sourceHeight: Double = v[b + SOURCE_HEIGHT].toDouble(),
        ): Double {
            val x = minOf(v[b + OFFSET_X], cw - 1)
            val y = minOf(v[b + OFFSET_Y], ch - 1)
            return when {
                v[b + COLUMNS] > 0 -> v[b + COLUMNS] - x.toDouble() / cw
                v[b + ROWS] > 0 -> (v[b + ROWS].toDouble() * ch - y) * v[b + SOURCE_WIDTH] / sourceHeight / cw
                else -> v[b + SOURCE_WIDTH].toDouble() / cw
            }
        }

        fun destinationHeight(
            v: IntArray,
            b: Int,
            cw: Int,
            ch: Int,
            sourceHeight: Double = v[b + SOURCE_HEIGHT].toDouble(),
        ): Double {
            val x = minOf(v[b + OFFSET_X], cw - 1)
            val y = minOf(v[b + OFFSET_Y], ch - 1)
            return when {
                v[b + ROWS] > 0 -> v[b + ROWS] - y.toDouble() / ch
                v[b + COLUMNS] > 0 -> (v[b + COLUMNS].toDouble() * cw - x) * sourceHeight / v[b + SOURCE_WIDTH] / ch
                else -> sourceHeight / ch
            }
        }
    }
}

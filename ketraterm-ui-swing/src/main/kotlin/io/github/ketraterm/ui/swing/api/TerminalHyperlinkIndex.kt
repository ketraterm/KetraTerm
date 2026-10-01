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

import io.github.ketraterm.render.api.TerminalRenderBufferKind
import io.github.ketraterm.render.api.TerminalRenderFrameReader
import io.github.ketraterm.render.cache.TerminalRenderCache
import java.util.*

/**
 * EDT-owned retained logical lines. Projection never constructs text or actions for prepared rows.
 * Absolute coordinates are scoped to a buffer's history generation; occurrence IDs are scoped to
 * this index and are never reused, including across eviction and binding replacement.
 */
internal class TerminalHyperlinkIndex {
    private val primary = Buffer()
    private val alternate = Buffer()
    private var buffer = primary
    private val builder = TerminalHyperlinkLineSnapshotBuilder()
    val actions = TerminalHyperlinkActions()
    private var ids = IntArray(0)
    private var nextIds = IntArray(0)
    private var hasFrame = false
    private var frameGeneration = 0L
    private var structureGeneration = 0L
    private var scrollbackOffset = 0
    private var discardedCount = 0L
    private var activeBuffer = TerminalRenderBufferKind.PRIMARY
    private var columns = 0
    private var rows = 0

    val orderedEpoch: Long get() = buffer.orderedEpoch
    val firstRetainedRow: Long get() = buffer.firstRetainedRow

    fun hasPending(context: SwingHyperlinkDetectionContext): Boolean =
        if (context == SwingHyperlinkDetectionContext.ORDERED_CONTENT) {
            lineAt(maxOf(buffer.orderedNextRow, buffer.firstRetainedRow))?.sourceCurrent == true
        } else {
            needsAnalysis
        }

    /** Discard provider continuation after a cancelled, failed or invalidated ordered call. */
    fun restartOrdered() {
        buffer.orderedEpoch++
        buffer.orderedNextRow = buffer.firstRetainedRow
    }

    var nextSourceRow: Long
        get() = buffer.nextSourceRow
        set(value) {
            buffer.nextSourceRow = value
        }

    fun isSourceAnalyzed(contentGeneration: Long): Boolean =
        buffer.hasAnalyzedContent && buffer.analyzedContentGeneration == contentGeneration

    fun finishSourceScan(contentGeneration: Long) {
        buffer.analyzedContentGeneration = contentGeneration
        buffer.hasAnalyzedContent = true
    }

    /** A rejected source copy must be retried even without another published frame. */
    fun invalidateSourceScan() {
        buffer.hasAnalyzedContent = false
    }

    fun missingSourceRow(
        cache: TerminalRenderCache,
        fromRow: Long,
    ): Long {
        val top = cache.discardedCount + cache.historySize - cache.scrollbackOffset
        var row = maxOf(top, fromRow)
        while (row < top + cache.rows) {
            val line = lineAt(row)
            if (line == null || !line.sourceCurrent) return row
            row = line.snapshot.lastAbsoluteRow + 1L
        }
        return -1L
    }

    /** Invalid copied text cannot be detected again until the source has been recopied. */
    fun invalidateSource(
        snapshots: List<TerminalHyperlinkLineSnapshot>,
        validLines: BooleanArray? = null,
    ) {
        invalidateSourceScan()
        for (index in snapshots.indices) {
            if (validLines?.get(index) == true) continue
            val snapshot = snapshots[index]
            val line = lineAt(snapshot.firstAbsoluteRow) ?: continue
            if (line.snapshot !== snapshot) continue
            release(line)
            line.sourceCurrent = false
            line.complete = false
            restartOrdered()
            invalidateDependencies(snapshot.firstAbsoluteRow, snapshot.lastAbsoluteRow)
            buffer.nextSourceRow = minOf(buffer.nextSourceRow, snapshot.firstAbsoluteRow)
        }
    }

    /**
     * A visible wrapped prefix may depend on live rows outside the viewport. On content changes,
     * inspect only those previously live row stamps in bounded locked reads; mutate the index
     * after unlocking. Complete text reconstruction stays on the worker.
     */
    fun invalidateUnobservedEdits(
        reader: TerminalRenderFrameReader,
        cache: TerminalRenderCache,
        previousLiveTop: Long,
    ) {
        val top = cache.discardedCount + cache.historySize - cache.scrollbackOffset
        val end = top + cache.rows
        var row = top
        while (row < end) {
            val line = lineAt(row)
            if (line == null) {
                row++
                continue
            }
            val snapshot = line.snapshot
            row = snapshot.lastAbsoluteRow + 1L
            if (!line.sourceCurrent || line.links.isEmpty()) continue
            var next = maxOf(snapshot.firstAbsoluteRow, previousLiveTop, cache.discardedCount)
            var valid = true
            while (valid && next <= snapshot.lastAbsoluteRow) {
                if (next in top until end) {
                    next = end
                    continue
                }
                val last = minOf(snapshot.lastAbsoluteRow, next + ANALYSIS_LINES - 1L, if (next < top) top - 1L else Long.MAX_VALUE)
                valid = false
                reader.readRenderFrameForAbsoluteRange(next, last) { frame ->
                    val first = frame.discardedCount + frame.historySize - frame.scrollbackOffset
                    valid =
                        frame.historyContentGeneration == cache.historyContentGeneration &&
                        first <= next &&
                        first + frame.rows > last
                    if (valid) {
                        for (absolute in next..last) {
                            if (!snapshot.matchesSourceRow(frame, (absolute - first).toInt(), absolute)) {
                                valid = false
                                break
                            }
                        }
                    }
                }
                next = last + 1L
            }
            if (!valid) {
                release(line)
                line.sourceCurrent = false
                line.complete = false
                invalidateSourceScan()
                restartOrdered()
                invalidateDependencies(snapshot.firstAbsoluteRow, snapshot.lastAbsoluteRow)
                buffer.nextSourceRow = minOf(buffer.nextSourceRow, snapshot.firstAbsoluteRow)
            }
        }
    }

    fun beginSourceScan(
        contentGeneration: Long,
        liveTop: Long,
    ) {
        if (buffer.hasScanDemand && buffer.scanContentGeneration == contentGeneration) return
        buffer.scanContentGeneration = contentGeneration
        buffer.hasScanDemand = true
        buffer.nextSourceRow = minOf(buffer.nextSourceRow, liveTop)
    }

    val needsAnalysis: Boolean
        get() = buffer.pending.isNotEmpty()

    fun clear() {
        clear(primary)
        clear(alternate)
        hasFrame = false
    }

    private fun clear(state: Buffer) {
        for (index in state.head until state.lines.size) release(state.lines[index], state)
        state.lines.clear()
        state.pending.clear()
        state.contextual.clear()
        state.orderedResults.clear()
        state.head = 0
        state.generation = Long.MIN_VALUE
        state.nextSourceRow = 0L
        state.analyzedContentGeneration = Long.MIN_VALUE
        state.scanContentGeneration = Long.MIN_VALUE
        state.hasAnalyzedContent = false
        state.hasScanDemand = false
        state.firstRetainedRow = 0L
        state.orderedNextRow = 0L
        state.orderedEpoch++
    }

    /** Observes only supplied rows; a session source supplies missing complete lines asynchronously. */
    fun update(
        cache: TerminalRenderCache,
        context: SwingHyperlinkDetectionContext = SwingHyperlinkDetectionContext.INDEPENDENT_LINE,
        captureVisible: Boolean = true,
    ): Boolean {
        if (matches(cache)) return false
        buffer = if (cache.activeBuffer == TerminalRenderBufferKind.PRIMARY) primary else alternate
        if (buffer.generation != cache.historyContentGeneration || buffer.columns != cache.columns) {
            clear(buffer)
            buffer.generation = cache.historyContentGeneration
            buffer.columns = cache.columns
            buffer.nextSourceRow = cache.discardedCount
        }
        evictBefore(cache.discardedCount)
        val top = cache.discardedCount + cache.historySize - cache.scrollbackOffset
        var row = 0
        while (row < cache.rows) {
            val previous = lineAt(top + row)
            if (previous != null && previous.snapshot.matchesRow(cache, row, top + row)) {
                row++
                continue
            }
            if (previous != null && previous.snapshot.firstAbsoluteRow >= top) {
                row = (previous.snapshot.firstAbsoluteRow - top).toInt()
            }
            var end = row + 1
            while (end < cache.rows && cache.lineWrapped[end - 1]) end++
            // A clipped line must be rebuilt from the source, never from its visible suffix.
            val completeStart = previous == null || previous.snapshot.firstAbsoluteRow == top + row
            val completeEnd = !cache.lineWrapped[end - 1]
            if ((captureVisible || previous != null) && completeStart && (captureVisible || completeEnd)) {
                ingest(builder.snapshot(cache, row, end), context)
            } else if (previous != null) {
                release(previous)
                previous.complete = false
                previous.sourceCurrent = false
                invalidateSourceScan()
                restartOrdered()
                invalidateDependencies(previous.snapshot.firstAbsoluteRow, previous.snapshot.lastAbsoluteRow)
                buffer.nextSourceRow = minOf(buffer.nextSourceRow, previous.snapshot.firstAbsoluteRow)
            }
            row = end
        }
        hasFrame = true
        frameGeneration = cache.frameGeneration
        structureGeneration = cache.structureGeneration
        scrollbackOffset = cache.scrollbackOffset
        discardedCount = cache.discardedCount
        activeBuffer = cache.activeBuffer
        columns = cache.columns
        rows = cache.rows
        return true
    }

    fun ingest(
        captured: TerminalHyperlinkLineSnapshot,
        context: SwingHyperlinkDetectionContext,
    ) {
        if (captured.lastAbsoluteRow < buffer.firstRetainedRow) return
        val position = lowerBound(captured.firstAbsoluteRow)
        var start = position
        if (start > buffer.head && buffer.lines[start - 1].snapshot.lastAbsoluteRow >= captured.firstAbsoluteRow) start--
        var end = start
        while (end < buffer.lines.size && buffer.lines[end].snapshot.firstAbsoluteRow <= captured.lastAbsoluteRow) end++
        val existing = buffer.lines.getOrNull(start)?.snapshot
        val snapshot =
            if (end == start + 1 &&
                existing != null &&
                existing.firstAbsoluteRow < buffer.firstRetainedRow &&
                captured.firstAbsoluteRow == buffer.firstRetainedRow
            ) {
                existing.withRetainedSuffix(captured) ?: captured
            } else {
                captured
            }
        val previous =
            buffer.lines.getOrNull(start)?.takeIf {
                end == start + 1 &&
                    it.snapshot.firstAbsoluteRow == snapshot.firstAbsoluteRow &&
                    it.snapshot.firstLineId == snapshot.firstLineId
            }
        if (previous?.snapshot === snapshot) return
        val line = Line(snapshot)
        if (previous != null) {
            val sameText = previous.snapshot.text == snapshot.text
            line.complete = sameText && previous.complete
            line.links = previous.links.filter { sameText || it.detected.remainsValid(previous.snapshot.text, snapshot.text) }
            for (link in line.links) actions.retain(link.id)
        }
        val changedText = previous == null || previous.snapshot.text != snapshot.text
        for (index in start until end) release(buffer.lines[index])
        if (end > start) buffer.lines.subList(start, end).clear()
        buffer.lines.add(start, line)
        if (!line.complete) buffer.pending.add(line)
        registerResults(line)
        if (changedText && end > start && context != SwingHyperlinkDetectionContext.INDEPENDENT_LINE) {
            if (previous != null || snapshot.firstAbsoluteRow < buffer.orderedNextRow) restartOrdered()
            invalidateDependencies(snapshot.firstAbsoluteRow, snapshot.lastAbsoluteRow)
        }
    }

    private fun invalidateDependencies(
        first: Long,
        last: Long,
    ) {
        val iterator = buffer.contextual.iterator()
        while (iterator.hasNext()) {
            val line = iterator.next()
            val retained =
                line.links.filter {
                    val dependency = it.detected.hyperlink.dependencyRange
                    !it.detected.viewportDependent || dependency.end.absoluteRow < first || dependency.start.absoluteRow > last
                }
            if (retained.size != line.links.size) {
                unregisterOrderedResults(line, buffer)
                for (link in line.links) if (link !in retained) actions.release(link.id)
                line.links = retained
                registerOrderedResults(line)
                line.complete = false
                buffer.pending.add(line)
                if (retained.none { it.detected.viewportDependent }) iterator.remove()
            }
        }
    }

    fun pendingLines(
        context: SwingHyperlinkDetectionContext = SwingHyperlinkDetectionContext.INDEPENDENT_LINE,
        visibleFirst: Long = -1L,
        visibleLast: Long = -1L,
    ): List<TerminalHyperlinkLineSnapshot> {
        val result = ArrayList<TerminalHyperlinkLineSnapshot>()
        if (context == SwingHyperlinkDetectionContext.INDEPENDENT_LINE) {
            for (line in buffer.pending) {
                if (line.snapshot.lastAbsoluteRow >= visibleFirst && line.snapshot.firstAbsoluteRow <= visibleLast) {
                    result.add(line.snapshot)
                    if (result.size == ANALYSIS_LINES) break
                }
            }
            for (line in buffer.pending) {
                if (result.size == ANALYSIS_LINES) break
                if (line.snapshot.lastAbsoluteRow < visibleFirst || line.snapshot.firstAbsoluteRow > visibleLast) result.add(line.snapshot)
            }
            result.sortBy { it.firstAbsoluteRow }
            return result
        }
        var next = maxOf(buffer.orderedNextRow, buffer.firstRetainedRow)
        while (result.size < ANALYSIS_LINES) {
            val line = lineAt(next) ?: break
            if (!line.sourceCurrent) break
            result.add(line.snapshot)
            next = line.snapshot.lastAbsoluteRow + 1L
        }
        return result
    }

    fun accept(
        snapshots: List<TerminalHyperlinkLineSnapshot>,
        detected: List<List<TerminalDetectedHyperlink>>,
        context: SwingHyperlinkDetectionContext = SwingHyperlinkDetectionContext.INDEPENDENT_LINE,
        validSources: BooleanArray? = null,
    ) {
        var sameContext = validSources?.all { it } != false
        for (snapshot in snapshots) {
            if (lineAt(snapshot.firstAbsoluteRow)?.snapshot?.text != snapshot.text) {
                sameContext = false
                break
            }
        }
        val occurrences = IdentityHashMap<SwingHyperlink, Int>()
        for (index in snapshots.indices) {
            if (validSources?.get(index) == false) continue
            val snapshot = snapshots[index]
            val line = lineAt(snapshot.firstAbsoluteRow) ?: continue
            if (line.snapshot.firstAbsoluteRow != snapshot.firstAbsoluteRow ||
                line.snapshot.firstLineId != snapshot.firstLineId
            ) {
                continue
            }
            val sameText = line.snapshot.text == snapshot.text
            val links =
                detected[index].filter {
                    (!it.viewportDependent || sameContext) && (sameText || it.remainsValid(snapshot.text, line.snapshot.text))
                }
            // Replayed context may rediscover an unchanged occurrence. Keep its prepared action
            // and identity when its source, dependencies and presentation still describe it.
            if (sameText &&
                line.complete &&
                links.size == line.links.count { it.context == context } &&
                line.links
                    .filter { it.context == context }
                    .zip(links)
                    .all { (old, new) -> sameOccurrence(old.detected, new) }
            ) {
                continue
            }
            val previousByOccurrence = HashMap<OccurrenceKey, Link>(line.links.size)
            for (previous in line.links) if (previous.context == context) previousByOccurrence[occurrenceKey(previous.detected)] = previous
            val preserved = line.links.filter { it.context != context }
            for (link in preserved) actions.retain(link.id)
            val retained =
                links.map {
                    val previous = previousByOccurrence[occurrenceKey(it)]
                    val id = occurrences.getOrPut(it.hyperlink) { previous?.id ?: actions.add(it.hyperlink) }
                    actions.retain(id)
                    Link(id, previous?.detected ?: it, context, previous?.spanLength ?: sourceLength(it.hyperlink.sourceRange))
                }
            val complete = if (context == SwingHyperlinkDetectionContext.ORDERED_CONTENT) line.complete else sameText
            release(line)
            line.links = (preserved + retained).sortedWith(LINK_ORDER)
            line.complete = complete
            if (!line.complete) buffer.pending.add(line)
            registerResults(line)
        }
        if (context == SwingHyperlinkDetectionContext.ORDERED_CONTENT && sameContext && snapshots.isNotEmpty()) {
            buffer.orderedNextRow = snapshots.last().lastAbsoluteRow + 1L
        }
    }

    /** Applies a bounded publication, resolving backward ordered ranges against retained source. */
    fun acceptResults(
        requested: List<TerminalHyperlinkLineSnapshot>,
        results: List<SwingHyperlink>,
        context: SwingHyperlinkDetectionContext,
        validSources: BooleanArray?,
    ) {
        if (context == SwingHyperlinkDetectionContext.INDEPENDENT_LINE) {
            val sink = TerminalHyperlinkDetectionAccumulator(requested)
            results.forEach(sink::addHyperlink)
            accept(requested, sink.links, context, validSources)
            return
        }
        if (validSources?.any { !it } == true) return
        val affected = TreeMap<Long, TerminalHyperlinkLineSnapshot>()
        requested.forEach { affected[it.firstAbsoluteRow] = it }
        val producedFirst = requested.first().firstAbsoluteRow
        val producedLast = requested.last().lastAbsoluteRow
        for (lines in buffer.orderedResults.subMap(producedFirst, true, producedLast, true).values) {
            for (line in lines) affected[line.snapshot.firstAbsoluteRow] = line.snapshot
        }
        val mapped = HashMap<Long, MutableList<TerminalDetectedHyperlink>>()
        for (result in results) {
            val source = result.sourceRange
            val dependency = result.dependencyRange
            val first = lineAt(source.start.absoluteRow) ?: continue
            val last = lineAt(source.end.absoluteRow) ?: continue
            val producer = lineAt(result.consumedThrough.absoluteRow) ?: continue
            if (source.start.absoluteRow != first.snapshot.firstAbsoluteRow ||
                source.end.absoluteRow != last.snapshot.firstAbsoluteRow ||
                source.start.offset >= first.snapshot.text.length ||
                source.end.offset > last.snapshot.text.length ||
                result.consumedThrough.absoluteRow !in producedFirst..producedLast ||
                result.consumedThrough.offset > producer.snapshot.text.length
            ) {
                continue
            }
            val dependencyLast = lineAt(dependency.end.absoluteRow) ?: continue
            if (dependency.end.offset > dependencyLast.snapshot.text.length) continue
            if (dependency.start.absoluteRow >= buffer.firstRetainedRow) {
                val dependencyFirst = lineAt(dependency.start.absoluteRow) ?: continue
                if (dependency.start.offset > dependencyFirst.snapshot.text.length) continue
            }
            var row = source.start.absoluteRow
            while (row <= source.end.absoluteRow) {
                val line = lineAt(row) ?: break
                val snapshot = line.snapshot
                val start = if (row == source.start.absoluteRow) source.start.offset else 0
                val end = minOf(if (row == source.end.absoluteRow) source.end.offset else snapshot.text.length, snapshot.text.length - 1)
                affected[row] = snapshot
                if (start <
                    end
                ) {
                    mapped
                        .getOrPut(
                            row,
                        ) { ArrayList() }
                        .add(TerminalDetectedHyperlink(start, end, result, 0, snapshot.text.length, true))
                }
                row = snapshot.lastAbsoluteRow + 1L
            }
        }
        val snapshots = affected.values.toList()
        val detected =
            snapshots.map { snapshot ->
                val retained =
                    lineAt(snapshot.firstAbsoluteRow)
                        ?.links
                        .orEmpty()
                        .filter {
                            it.context == context && it.detected.hyperlink.consumedThrough.absoluteRow !in producedFirst..producedLast
                        }.map { it.detected }
                retained + mapped[snapshot.firstAbsoluteRow].orEmpty()
            }
        accept(snapshots, detected, context)
        buffer.orderedNextRow = requested.last().lastAbsoluteRow + 1L
    }

    fun idsFor(cache: TerminalRenderCache): IntArray = if (matches(cache)) ids else cache.hyperlinkIds

    fun hyperlinkFor(
        id: Int,
        cache: TerminalRenderCache,
    ): SwingHyperlink? = if (id < 0 && matches(cache)) actions.get(id) else null

    fun writeOverlay(
        cache: TerminalRenderCache,
        repaint: (Int, Int, Int, Int) -> Unit,
    ): Boolean {
        if (!matches(cache)) return false
        var changed = false
        val size = rows * columns
        if (nextIds.size < size) nextIds = IntArray(size)
        cache.hyperlinkIds.copyInto(nextIds, endIndex = size)
        val top = cache.discardedCount + cache.historySize - cache.scrollbackOffset
        var row = 0
        while (row < rows) {
            val line = lineAt(top + row)
            if (line == null) {
                row++
                continue
            }
            val snapshot = line.snapshot
            val lastRow = minOf(rows - 1L, snapshot.lastAbsoluteRow - top).toInt()
            val firstCell = maxOf(0L, top - snapshot.firstAbsoluteRow) * columns
            val lastCell = (minOf(top + rows - 1L, snapshot.lastAbsoluteRow) - snapshot.firstAbsoluteRow + 1L) * columns
            val firstOffset = snapshot.firstOffsetAfterCell(firstCell)
            val endOffset = snapshot.endOffsetBeforeCell(lastCell)
            for (linkIndex in line.links.indices) {
                val link = line.links[linkIndex]
                for (offset in maxOf(link.detected.startOffset, firstOffset) until minOf(link.detected.endOffset, endOffset)) {
                    val start = maxOf(firstCell, snapshot.cellStarts[offset].toLong())
                    val end = minOf(lastCell, snapshot.cellEnds[offset].toLong())
                    for (cell in start until end) {
                        val target = ((snapshot.firstAbsoluteRow - top) * columns + cell).toInt()
                        if (nextIds[target] == 0) nextIds[target] = link.id
                    }
                }
            }
            row = lastRow + 1
        }
        for (r in 0 until rows) {
            val offset = cache.rowOffset(r)
            var column = 0
            while (column < columns) {
                if ((if (offset + column < ids.size) ids[offset + column] else 0) == nextIds[offset + column]) {
                    column++
                    continue
                }
                val start = column++
                while (column < columns &&
                    (if (offset + column < ids.size) ids[offset + column] else 0) != nextIds[offset + column]
                ) {
                    column++
                }
                repaint(r, start, r, column)
                changed = true
            }
        }
        val old = ids
        ids = nextIds
        nextIds = old
        return changed
    }

    fun evictBefore(first: Long) {
        val retainedFirst = maxOf(buffer.firstRetainedRow, first)
        buffer.firstRetainedRow = retainedFirst
        while (buffer.head < buffer.lines.size && buffer.lines[buffer.head].snapshot.lastAbsoluteRow < retainedFirst) {
            release(buffer.lines[buffer.head++])
        }
        if (buffer.head > 128 && buffer.head * 2 >= buffer.lines.size) {
            buffer.lines.subList(0, buffer.head).clear()
            buffer.head = 0
        }
        buffer.nextSourceRow = maxOf(buffer.nextSourceRow, retainedFirst)
    }

    private fun sourceLength(range: SwingHyperlinkTextRange): Long {
        var row = range.start.absoluteRow
        var length = 0L
        while (row <= range.end.absoluteRow) {
            val snapshot = lineAt(row)?.snapshot ?: return Long.MAX_VALUE
            length += (if (row == range.end.absoluteRow) range.end.offset else snapshot.text.length) -
                (if (row == range.start.absoluteRow) range.start.offset else 0)
            row = snapshot.lastAbsoluteRow + 1L
        }
        return length
    }

    private fun release(
        line: Line,
        state: Buffer = buffer,
    ) {
        unregisterOrderedResults(line, state)
        for (link in line.links) actions.release(link.id)
        line.links = emptyList()
        state.pending.remove(line)
        state.contextual.remove(line)
    }

    private fun registerResults(line: Line) {
        if (line.links.any { it.detected.viewportDependent }) buffer.contextual.add(line)
        registerOrderedResults(line)
    }

    private fun registerOrderedResults(line: Line) {
        for (link in line.links) {
            if (link.context == SwingHyperlinkDetectionContext.ORDERED_CONTENT) {
                buffer.orderedResults.getOrPut(link.detected.hyperlink.consumedThrough.absoluteRow) { HashSet() }.add(line)
            }
        }
    }

    private fun unregisterOrderedResults(
        line: Line,
        state: Buffer,
    ) {
        for (link in line.links) {
            if (link.context != SwingHyperlinkDetectionContext.ORDERED_CONTENT) continue
            val row = link.detected.hyperlink.consumedThrough.absoluteRow
            val lines = state.orderedResults[row] ?: continue
            lines.remove(line)
            if (lines.isEmpty()) state.orderedResults.remove(row)
        }
    }

    private fun sameOccurrence(
        first: TerminalDetectedHyperlink,
        second: TerminalDetectedHyperlink,
    ): Boolean = occurrenceKey(first) == occurrenceKey(second)

    private fun occurrenceKey(link: TerminalDetectedHyperlink): OccurrenceKey {
        val hyperlink = link.hyperlink
        return OccurrenceKey(
            link.startOffset,
            link.endOffset,
            hyperlink.sourceRange,
            hyperlink.dependencyRange,
            hyperlink.consumedThrough,
            hyperlink.uri,
            hyperlink.activation,
            hyperlink.presentation,
            hyperlink.providerOrder,
        )
    }

    private fun lowerBound(row: Long): Int {
        var low = buffer.head
        var high = buffer.lines.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (buffer.lines[mid].snapshot.firstAbsoluteRow < row) low = mid + 1 else high = mid
        }
        return low
    }

    private fun lineAt(row: Long): Line? {
        val index = lowerBound(row)
        if (index < buffer.lines.size && buffer.lines[index].snapshot.firstAbsoluteRow == row) return buffer.lines[index]
        if (index > buffer.head) return buffer.lines[index - 1].takeIf { it.snapshot.lastAbsoluteRow >= row }
        return null
    }

    private fun matches(cache: TerminalRenderCache): Boolean =
        hasFrame &&
            frameGeneration == cache.frameGeneration &&
            structureGeneration == cache.structureGeneration &&
            scrollbackOffset == cache.scrollbackOffset &&
            discardedCount == cache.discardedCount &&
            activeBuffer == cache.activeBuffer &&
            columns == cache.columns &&
            rows == cache.rows

    private class Buffer {
        val lines = ArrayList<Line>()
        val pending = LinkedHashSet<Line>()
        val contextual = HashSet<Line>()
        val orderedResults = TreeMap<Long, MutableSet<Line>>()
        var head = 0
        var generation = Long.MIN_VALUE
        var columns = 0
        var nextSourceRow = 0L
        var firstRetainedRow = 0L
        var analyzedContentGeneration = Long.MIN_VALUE
        var scanContentGeneration = Long.MIN_VALUE
        var hasAnalyzedContent = false
        var hasScanDemand = false
        var orderedNextRow = 0L
        var orderedEpoch = 0L
    }

    private class Line(
        val snapshot: TerminalHyperlinkLineSnapshot,
    ) {
        var complete = snapshot.text == "\n"
        var sourceCurrent = true
        var links: List<Link> = emptyList()
    }

    private class Link(
        val id: Int,
        val detected: TerminalDetectedHyperlink,
        val context: SwingHyperlinkDetectionContext,
        val spanLength: Long,
    )

    /** Transient changed-result lookup; excludes actions because retained source owns their lifetime. */
    private data class OccurrenceKey(
        val startOffset: Int,
        val endOffset: Int,
        val source: SwingHyperlinkTextRange,
        val dependency: SwingHyperlinkTextRange,
        val consumedThrough: SwingHyperlinkTextPosition,
        val uri: String?,
        val activation: SwingHyperlinkActivation,
        val presentation: SwingHyperlinkPresentation,
        val providerOrder: Int,
    )

    private companion object {
        const val ANALYSIS_LINES = 64
        val LINK_ORDER =
            compareByDescending<Link> { it.detected.hyperlink.presentation.isVisible }
                .thenBy { it.spanLength }
                .thenBy { it.detected.hyperlink.providerOrder }
    }
}

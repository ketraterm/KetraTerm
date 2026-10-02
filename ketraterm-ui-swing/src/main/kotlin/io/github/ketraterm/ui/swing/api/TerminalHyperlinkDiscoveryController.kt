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

import io.github.ketraterm.render.api.TerminalRenderFrameReader
import io.github.ketraterm.render.cache.TerminalRenderCache
import kotlinx.coroutines.*
import java.awt.event.MouseEvent

internal interface TerminalHyperlinkDiscoveryHost {
    val renderCache: TerminalRenderCache
    val hyperlinkDetector: SwingHyperlinkDetector

    /** Bound content source; null means the terminal is unbound. */
    val hyperlinkSource: TerminalRenderFrameReader?

    /** Reconciles interaction state after asynchronously detected links have been installed. */
    fun hyperlinksChanged()

    fun repaintHyperlinkSpan(
        startRow: Int,
        startColumn: Int,
        endRow: Int,
        endColumn: Int,
    )
}

/**
 * EDT-owned, binding-lifetime discovery. Each context serially drains coalesced content demand;
 * prepared viewport changes only project the index. Failure recovery is bounded per demand.
 */
internal class TerminalHyperlinkDiscoveryController(
    private val host: TerminalHyperlinkDiscoveryHost,
    private val scope: CoroutineScope,
    private val analysisDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val index = TerminalHyperlinkIndex()
    private val independent = AnalysisLane(SwingHyperlinkDetectionContext.INDEPENDENT_LINE)
    private val ordered = AnalysisLane(SwingHyperlinkDetectionContext.ORDERED_CONTENT)
    private val lanes = arrayOf(independent, ordered)
    private var configurationJob: Job? = null
    private var bindingEpoch = 0L
    private var sourceEpoch = 0L
    private var providerEpoch = 0L
    private var providerGeneration = host.hyperlinkDetector.configurationGeneration
    private var sourceGeneration = Long.MIN_VALUE
    private var sourceColumns = 0
    private var sourceBuffer = host.renderCache.activeBuffer
    private var contentGeneration = 0L
    private var demandRevision = 0L
    private var enabled = false
    private var disposed = false
    private var detector = host.hyperlinkDetector
    private var priorityTop = -1L
    private var priorityNextRow = -1L
    private var previousLiveTop = -1L
    private val repaintSpan = host::repaintHyperlinkSpan

    /** Ends the binding, cancels owned work, and releases retained text/actions. */
    fun reset() {
        enabled = false
        bindingEpoch++
        if (ordered.analysisJob == null) detector.discardOrderedState()
        configurationJob?.cancel(CancellationException("Hyperlink binding replaced"))
        configurationJob = null
        for (lane in lanes) {
            lane.recoveryJob?.cancel(CancellationException("Hyperlink binding replaced"))
            lane.recoveryJob = null
            lane.recoveryAttempt = 0
            lane.recoveryExhausted = false
            lane.analysisJob?.cancel(CancellationException("Hyperlink binding replaced"))
            // An uncooperative provider retains the serialization slot until it returns.
            lane.sourceScan = null
        }
        index.clear()
        priorityTop = -1L
        previousLiveTop = -1L
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        reset()
    }

    fun scheduleForFrame() {
        val cache = host.renderCache
        if (disposed) return
        val source = host.hyperlinkSource
        if (source == null) {
            if (enabled) reset()
            return
        }
        if (!cache.hasFrame) return
        var changed = !enabled || contentGeneration != cache.contentGeneration
        var sourceReplaced = false
        if (detector !== host.hyperlinkDetector || providerGeneration != host.hyperlinkDetector.configurationGeneration) {
            val replaced = detector !== host.hyperlinkDetector
            if (ordered.analysisJob == null) detector.discardOrderedState()
            providerEpoch++
            if (replaced) {
                index.clear()
                configurationJob?.cancel(CancellationException("Hyperlink provider replaced"))
                configurationJob = null
            } else {
                index.refreshProvider(providerEpoch)
            }
            detector = host.hyperlinkDetector
            providerGeneration = detector.configurationGeneration
            changed = true
            lanes.forEach { it.analysisJob?.cancel(CancellationException("Hyperlink provider changed")) }
        }
        if (sourceGeneration != cache.historyContentGeneration || sourceColumns != cache.columns || sourceBuffer != cache.activeBuffer) {
            sourceEpoch++
            sourceReplaced = true
            sourceGeneration = cache.historyContentGeneration
            sourceColumns = cache.columns
            sourceBuffer = cache.activeBuffer
            previousLiveTop = -1L
            changed = true
            lanes.forEach { it.analysisJob?.cancel(CancellationException("Hyperlink source replaced")) }
        }
        enabled = detector !== SwingHyperlinkDetector.NONE
        contentGeneration = cache.contentGeneration
        if (!enabled) {
            host.hyperlinksChanged()
            return
        }
        val projectionChanged = index.update(cache, detector.context)
        if (sourceReplaced) index.restartOrdered()
        if (configurationJob == null) {
            val changes = detector.configurationChanges
            configurationJob = scope.launch { changes.collect { scheduleForFrame() } }
        }
        if (changed && previousLiveTop >= 0L) index.invalidateUnobservedEdits(source, cache, previousLiveTop)
        previousLiveTop = cache.discardedCount + cache.historySize
        if (projectionChanged || changed) {
            if (index.writeOverlay(cache, repaintSpan)) host.hyperlinksChanged()
        }
        if (changed) requestAnalysis()
    }

    /** Reconciles a returning binding/show/focus without needing output or a scroll event. */
    fun reconcile() {
        scheduleForFrame()
        if (enabled && (lanes.any { usesLane(it) && index.hasPending(it.context) } || needsSourceScan())) requestAnalysis()
        host.hyperlinksChanged()
    }

    private fun requestAnalysis() {
        demandRevision++
        for (lane in lanes) {
            lane.recoveryAttempt = 0
            lane.recoveryExhausted = false
            lane.recoveryJob?.cancel(CancellationException("Hyperlink demand superseded"))
            lane.recoveryJob = null
        }
        priorityTop = -1L
        startAnalysis()
    }

    fun hyperlinkIdsFor(cache: TerminalRenderCache): IntArray = index.idsFor(cache)

    val followedHyperlinkId: Int get() = index.actions.followedId

    fun hyperlinkPresentationsFor(cache: TerminalRenderCache): Array<SwingHyperlinkPresentation?>? = index.presentationsFor(cache)

    fun hyperlinkIdAt(
        row: Int,
        column: Int,
        cache: TerminalRenderCache,
    ): Int {
        if (row !in 0 until cache.rows || column !in 0 until cache.columns) return 0
        return hyperlinkIdsFor(cache)[cache.rowOffset(row) + column]
    }

    fun isDiscoveredHyperlinkResolvable(
        hyperlinkId: Int,
        cache: TerminalRenderCache,
    ): Boolean = index.hyperlinkFor(hyperlinkId, cache) != null

    fun openDiscoveredHyperlink(
        hyperlinkId: Int,
        cache: TerminalRenderCache,
        event: MouseEvent? = null,
    ): Boolean {
        val link = index.hyperlinkFor(hyperlinkId, cache) ?: return false
        if (!(if (event == null) link.action.open() else link.action.open(event))) return false
        markFollowed(hyperlinkId, cache)
        return true
    }

    fun markFollowed(
        id: Int,
        cache: TerminalRenderCache,
    ) {
        val previous = index.actions.follow(id)
        val ids = hyperlinkIdsFor(cache)
        for (row in 0 until cache.rows) {
            var column = 0
            while (column < cache.columns) {
                val cellId = ids[cache.rowOffset(row) + column]
                if (cellId != id && (previous == 0 || cellId != previous)) {
                    column++
                    continue
                }
                val start = column++
                while (column < cache.columns && ids[cache.rowOffset(row) + column] == cellId) column++
                host.repaintHyperlinkSpan(row, start, row, column)
            }
        }
    }

    fun discoveredHyperlink(
        hyperlinkId: Int,
        cache: TerminalRenderCache,
    ): SwingHyperlink? = index.hyperlinkFor(hyperlinkId, cache)

    private fun needsSourceScan(): Boolean = !index.isSourceAnalyzed(host.renderCache.contentGeneration)

    private fun usesLane(lane: AnalysisLane): Boolean =
        detector.context == lane.context || detector.context == SwingHyperlinkDetectionContext.INDEPENDENT_AND_ORDERED

    private fun startAnalysis() {
        for (lane in lanes) {
            if (usesLane(lane)) startAnalysis(lane)
        }
    }

    private fun startAnalysis(lane: AnalysisLane) {
        val requestSource = host.hyperlinkSource ?: return
        val scansSource = lane === independent || detector.context == SwingHyperlinkDetectionContext.ORDERED_CONTENT
        if (lane === ordered && !scansSource && needsSourceScan()) return
        if (lane.analysisJob != null ||
            lane.recoveryJob != null ||
            lane.recoveryExhausted ||
            disposed ||
            !enabled ||
            !scope.isActive ||
            (!index.hasPending(lane.context) && !(scansSource && needsSourceScan()))
        ) {
            return
        }
        val requestBindingEpoch = bindingEpoch
        val requestSourceEpoch = sourceEpoch
        val requestProviderEpoch = providerEpoch
        val requestDetector = detector
        val requestContext = lane.context
        val requestAnalysisEpoch = index.orderedEpoch
        val scanner = lane.sourceScan ?: TerminalHyperlinkSourceScan().also { lane.sourceScan = it }
        var entered = false
        // Assign the slot before an immediate dispatcher can complete the coroutine.
        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                entered = true
                val requestRevision = demandRevision
                var completed = false
                var recover = false
                try {
                    if (!isCurrent(requestBindingEpoch, requestSourceEpoch, requestProviderEpoch)) return@launch
                    val cache = host.renderCache
                    val history = cache.historyContentGeneration
                    if (scansSource && needsSourceScan()) {
                        val top = cache.discardedCount + cache.historySize - cache.scrollbackOffset
                        if (priorityTop != top) {
                            priorityTop = top
                            priorityNextRow = top
                        }
                        val missing = index.missingSourceRow(cache, priorityNextRow)
                        index.beginSourceScan(cache.contentGeneration, cache.discardedCount + cache.historySize)
                        val nextRow = if (missing >= 0L) missing else index.nextSourceRow
                        val buffer = cache.activeBuffer
                        val columns = cache.columns
                        val observedContent = cache.contentGeneration
                        val scanned =
                            withContext(analysisDispatcher) {
                                scanner.scan(requestSource, nextRow, buffer, columns, history)
                            }
                        if (!isCurrent(requestBindingEpoch, requestSourceEpoch, requestProviderEpoch)) return@launch
                        if (scanned == null) {
                            recover = true
                            return@launch
                        }
                        index.evictBefore(scanned.firstRetainedRow)
                        var rejected = false
                        for (snapshot in scanned.lines) {
                            val currentTop = cache.discardedCount + cache.historySize - cache.scrollbackOffset
                            var matchesVisible = true
                            val first = maxOf(snapshot.firstAbsoluteRow, currentTop)
                            val last = minOf(snapshot.lastAbsoluteRow, currentTop + cache.rows - 1L)
                            for (row in first..last) {
                                if (!snapshot.matchesTextRow(cache, (row - currentTop).toInt(), row)) {
                                    matchesVisible = false
                                    break
                                }
                            }
                            if (matchesVisible) index.ingest(snapshot, requestDetector.context) else rejected = true
                        }
                        if (missing >= 0L) {
                            priorityNextRow = scanned.nextSourceRow
                        } else {
                            index.nextSourceRow = if (scanned.reachedEnd) scanned.liveTop else scanned.nextSourceRow
                            if (scanned.reachedEnd && !rejected) index.finishSourceScan(observedContent)
                        }
                        if (rejected) {
                            recover = true
                            return@launch
                        }
                    }
                    val top = cache.discardedCount + cache.historySize - cache.scrollbackOffset
                    if (lane === ordered && needsSourceScan()) {
                        completed = true
                        return@launch
                    }
                    val lines = index.pendingLines(requestContext, top, top + cache.rows - 1L)
                    if (lines.isEmpty()) {
                        publishOverlay()
                        completed = true
                        return@launch
                    }
                    var validationLines =
                        if (lane === ordered) {
                            index.orderedValidationLines(lines, cache.discardedCount + cache.historySize)
                        } else {
                            lines
                        }
                    val request =
                        detectionRequest(
                            lines,
                            requestContext,
                            requestBindingEpoch,
                            requestSourceEpoch,
                            requestProviderEpoch,
                            requestAnalysisEpoch,
                            index.firstRetainedRow,
                        )
                    val result = detect(requestDetector, request)
                    if (result == null) {
                        recover = true
                        return@launch
                    }
                    if (!isCurrent(requestBindingEpoch, requestSourceEpoch, requestProviderEpoch)) return@launch
                    if (lane === independent) validationLines = index.independentValidationLines(lines)
                    val validSources: BooleanArray
                    val validation =
                        withContext(analysisDispatcher) {
                            scanner.validate(requestSource, validationLines, history)
                        }
                    if (!isCurrent(requestBindingEpoch, requestSourceEpoch, requestProviderEpoch)) return@launch
                    var current = false
                    requestSource.readRenderFrame { frame ->
                        current = validation != null &&
                            frame.contentGeneration == validation.contentGeneration &&
                            frame.historyContentGeneration == history &&
                            frame.activeBuffer == sourceBuffer &&
                            frame.columns == sourceColumns
                    }
                    if (!current) {
                        index.invalidateSource(validationLines)
                        publishOverlay()
                        priorityTop = -1L
                        recover = true
                        return@launch
                    }
                    val validLines = checkNotNull(validation).validLines
                    if (lane === ordered && validLines.any { !it }) {
                        index.invalidateSource(validationLines, validLines)
                        publishOverlay()
                        priorityTop = -1L
                        recover = true
                        return@launch
                    }
                    validSources =
                        if (validationLines.size == lines.size) {
                            validLines
                        } else {
                            validLines.copyOfRange(validationLines.size - lines.size, validLines.size)
                        }
                    if (validSources.any { !it }) {
                        index.invalidateSource(validationLines, validSources)
                        priorityTop = -1L
                        recover = true
                    }
                    // Configuration can change while the worker is running, before another UI frame.
                    if (requestDetector !== host.hyperlinkDetector || providerGeneration != requestDetector.configurationGeneration) {
                        scheduleForFrame()
                        return@launch
                    }
                    if (requestContext == SwingHyperlinkDetectionContext.ORDERED_CONTENT && requestAnalysisEpoch != index.orderedEpoch) {
                        completed = true
                        return@launch
                    }
                    index.acceptResults(lines, result, requestContext, validSources)
                    publishOverlay()
                    completed = !recover
                } finally {
                    if (lane === ordered &&
                        (!isCurrent(requestBindingEpoch, requestSourceEpoch, requestProviderEpoch) || !scope.isActive)
                    ) {
                        requestDetector.discardOrderedState()
                    }
                    if (!completed &&
                        requestContext == SwingHyperlinkDetectionContext.ORDERED_CONTENT &&
                        requestAnalysisEpoch == index.orderedEpoch
                    ) {
                        index.restartOrdered()
                    }
                    lane.analysisJob = null
                    if (!disposed && enabled && scope.isActive) {
                        if (requestRevision != demandRevision ||
                            !isCurrent(requestBindingEpoch, requestSourceEpoch, requestProviderEpoch)
                        ) {
                            startAnalysis()
                        } else if (completed) {
                            lane.recoveryAttempt = 0
                            startAnalysis()
                        } else if (recover) {
                            scheduleRecovery(lane)
                        }
                    }
                }
            }
        lane.analysisJob = job
        job.invokeOnCompletion {
            if (!entered && lane === ordered) requestDetector.discardOrderedState()
            // Cancellation before dispatch never enters the body's finally block.
            scope.launch {
                if (lane.analysisJob === job) {
                    lane.analysisJob = null
                    startAnalysis()
                }
            }
        }
        job.start()
    }

    /** Only the external provider is an exception-isolation boundary; index failures propagate. */
    private suspend fun detect(
        detector: SwingHyperlinkDetector,
        request: SwingHyperlinkDetectionRequest,
    ): List<SwingHyperlink>? =
        withContext(analysisDispatcher) {
            val results =
                try {
                    detector.detect(request)
                } catch (cancelled: CancellationException) {
                    // A provider read may be interrupted while the binding itself remains active.
                    currentCoroutineContext().ensureActive()
                    return@withContext null
                } catch (failure: Exception) {
                    LOGGER.log(System.Logger.Level.WARNING, "Hyperlink provider failed: {0}", failure.javaClass.name)
                    return@withContext null
                }
            ensureActive()
            results
        }

    private fun isCurrent(
        binding: Long,
        source: Long,
        provider: Long,
    ): Boolean = !disposed && enabled && binding == bindingEpoch && source == sourceEpoch && provider == providerEpoch

    private fun scheduleRecovery(lane: AnalysisLane) {
        if (lane.recoveryJob != null) return
        if (lane.recoveryAttempt == RECOVERY_DELAYS.size) {
            lane.recoveryExhausted = true
            return
        }
        val waitMillis = RECOVERY_DELAYS[lane.recoveryAttempt++]
        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                delay(waitMillis)
                lane.recoveryJob = null
                startAnalysis()
            }
        lane.recoveryJob = job
        job.start()
    }

    private fun publishOverlay() {
        index.writeOverlay(host.renderCache, repaintSpan)
        host.hyperlinksChanged()
    }

    private class AnalysisLane(
        val context: SwingHyperlinkDetectionContext,
    ) {
        var sourceScan: TerminalHyperlinkSourceScan? = null
        var analysisJob: Job? = null
        var recoveryJob: Job? = null
        var recoveryAttempt = 0
        var recoveryExhausted = false
    }

    private companion object {
        val RECOVERY_DELAYS = longArrayOf(100L, 500L, 2_000L)
        val LOGGER: System.Logger = System.getLogger(TerminalHyperlinkDiscoveryController::class.java.name)
    }
}

internal fun detectionRequest(
    lines: List<TerminalHyperlinkLineSnapshot>,
    context: SwingHyperlinkDetectionContext = SwingHyperlinkDetectionContext.INDEPENDENT_LINE,
    bindingEpoch: Long = 0L,
    sourceEpoch: Long = 0L,
    providerEpoch: Long = 0L,
    analysisEpoch: Long = 0L,
    firstRetainedRow: Long = 0L,
): SwingHyperlinkDetectionRequest =
    SwingHyperlinkDetectionRequest(
        lines.map { it.text },
        LongArray(lines.size) { lines[it].firstAbsoluteRow },
        LongArray(lines.size) { lines[it].lastAbsoluteRow },
        context,
        bindingEpoch,
        sourceEpoch,
        providerEpoch,
        analysisEpoch,
        firstRetainedRow,
        LongArray(lines.size) { lines[it].firstLineId },
    )

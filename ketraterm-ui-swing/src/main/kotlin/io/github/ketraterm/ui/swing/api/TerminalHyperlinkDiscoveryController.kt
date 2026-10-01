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

internal interface TerminalHyperlinkDiscoveryHost {
    val renderCache: TerminalRenderCache
    val hyperlinkDetector: SwingHyperlinkDetector

    /** Bound source, or null when only explicitly supplied render frames are available. */
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
 * EDT-owned, binding-lifetime discovery. One serialized worker drains coalesced content demand;
 * prepared viewport changes only project the index. Failure recovery is bounded per demand.
 */
internal class TerminalHyperlinkDiscoveryController(
    private val host: TerminalHyperlinkDiscoveryHost,
    private val scope: CoroutineScope,
    private val analysisDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val index = TerminalHyperlinkIndex()
    private var sourceScan: TerminalHyperlinkSourceScan? = null
    private var analysisJob: Job? = null
    private var recoveryJob: Job? = null
    private var recoveryAttempt = 0
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
        recoveryJob?.cancel(CancellationException("Hyperlink binding replaced"))
        recoveryJob = null
        recoveryAttempt = 0
        analysisJob?.cancel(CancellationException("Hyperlink binding replaced"))
        // An uncooperative provider retains the serialization slot until it returns.
        sourceScan = null
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
        if (disposed || !cache.hasFrame) return
        var changed = !enabled || contentGeneration != cache.contentGeneration
        if (detector !== host.hyperlinkDetector || providerGeneration != host.hyperlinkDetector.configurationGeneration) {
            providerEpoch++
            index.clear()
            detector = host.hyperlinkDetector
            providerGeneration = detector.configurationGeneration
            changed = true
            analysisJob?.cancel(CancellationException("Hyperlink provider changed"))
        }
        if (sourceGeneration != cache.historyContentGeneration || sourceColumns != cache.columns || sourceBuffer != cache.activeBuffer) {
            sourceEpoch++
            sourceGeneration = cache.historyContentGeneration
            sourceColumns = cache.columns
            sourceBuffer = cache.activeBuffer
            previousLiveTop = -1L
            changed = true
            analysisJob?.cancel(CancellationException("Hyperlink source replaced"))
        }
        enabled = detector !== SwingHyperlinkDetector.NONE
        contentGeneration = cache.contentGeneration
        if (!enabled) {
            host.hyperlinksChanged()
            return
        }
        val projectionChanged = index.update(cache, detector.context, captureVisible = host.hyperlinkSource == null)
        val source = host.hyperlinkSource
        if (changed && source != null && previousLiveTop >= 0L) index.invalidateUnobservedEdits(source, cache, previousLiveTop)
        previousLiveTop = cache.discardedCount + cache.historySize
        if (projectionChanged || changed) {
            if (index.writeOverlay(cache, repaintSpan)) host.hyperlinksChanged()
        }
        if (changed) requestAnalysis()
    }

    /** Reconciles a returning binding/show/focus without needing output or a scroll event. */
    fun reconcile() {
        scheduleForFrame()
        if (enabled && (index.needsAnalysis || needsSourceScan())) requestAnalysis()
        host.hyperlinksChanged()
    }

    private fun requestAnalysis() {
        demandRevision++
        recoveryAttempt = 0
        recoveryJob?.cancel(CancellationException("Hyperlink demand superseded"))
        recoveryJob = null
        priorityTop = -1L
        startAnalysis()
    }

    fun hyperlinkIdsFor(cache: TerminalRenderCache): IntArray = index.idsFor(cache)

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
    ): Boolean = index.hyperlinkFor(hyperlinkId, cache)?.action?.open() == true

    fun discoveredHyperlink(
        hyperlinkId: Int,
        cache: TerminalRenderCache,
    ): SwingHyperlink? = index.hyperlinkFor(hyperlinkId, cache)

    private fun needsSourceScan(): Boolean = host.hyperlinkSource != null && !index.isSourceAnalyzed(host.renderCache.contentGeneration)

    private fun startAnalysis() {
        if (analysisJob != null ||
            recoveryJob != null ||
            disposed ||
            !enabled ||
            !scope.isActive ||
            (!index.needsAnalysis && !needsSourceScan())
        ) {
            return
        }
        val requestBindingEpoch = bindingEpoch
        val requestSourceEpoch = sourceEpoch
        val requestProviderEpoch = providerEpoch
        val requestDetector = detector
        val requestSource = host.hyperlinkSource
        val scanner = sourceScan ?: TerminalHyperlinkSourceScan().also { sourceScan = it }
        // Assign the slot before an immediate dispatcher can complete the coroutine.
        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                val requestRevision = demandRevision
                var completed = false
                var recover = false
                try {
                    if (!isCurrent(requestBindingEpoch, requestSourceEpoch, requestProviderEpoch)) return@launch
                    val cache = host.renderCache
                    val history = cache.historyContentGeneration
                    if (requestSource != null && needsSourceScan()) {
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
                    val lines = index.pendingLines(requestDetector.context, top, top + cache.rows - 1L)
                    if (lines.isEmpty()) {
                        publishOverlay()
                        completed = true
                        return@launch
                    }
                    val request =
                        detectionRequest(lines, requestDetector.context, requestBindingEpoch, requestSourceEpoch, requestProviderEpoch)
                    val result = detect(requestDetector, request, lines)
                    if (result == null) {
                        recover = true
                        return@launch
                    }
                    if (!isCurrent(requestBindingEpoch, requestSourceEpoch, requestProviderEpoch)) return@launch
                    var validSources: BooleanArray? = null
                    if (requestSource != null) {
                        val validation =
                            withContext(analysisDispatcher) {
                                scanner.validate(requestSource, lines, history)
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
                            index.invalidateSource(lines)
                            publishOverlay()
                            priorityTop = -1L
                            recover = true
                            return@launch
                        }
                        validSources = validation?.validLines
                        if (validSources?.any { !it } == true) {
                            index.invalidateSource(lines, validSources)
                            priorityTop = -1L
                            recover = true
                        }
                    }
                    // Configuration can change while the worker is running, before another UI frame.
                    if (requestDetector !== host.hyperlinkDetector || providerGeneration != requestDetector.configurationGeneration) {
                        scheduleForFrame()
                        return@launch
                    }
                    index.accept(lines, result, requestDetector.context, validSources)
                    publishOverlay()
                    completed = !recover
                } finally {
                    analysisJob = null
                    if (!disposed && enabled && scope.isActive) {
                        if (requestRevision != demandRevision ||
                            !isCurrent(requestBindingEpoch, requestSourceEpoch, requestProviderEpoch)
                        ) {
                            startAnalysis()
                        } else if (completed) {
                            recoveryAttempt = 0
                            startAnalysis()
                        } else if (recover) {
                            scheduleRecovery()
                        }
                    }
                }
            }
        analysisJob = job
        job.invokeOnCompletion {
            // Cancellation before dispatch never enters the body's finally block.
            scope.launch {
                if (analysisJob === job) {
                    analysisJob = null
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
        lines: List<TerminalHyperlinkLineSnapshot>,
    ): List<List<TerminalDetectedHyperlink>>? =
        withContext(analysisDispatcher) {
            val sink = TerminalHyperlinkDetectionAccumulator(lines)
            try {
                detector.detect(request, sink)
            } catch (cancelled: CancellationException) {
                // A provider read may be interrupted while the binding itself remains active.
                currentCoroutineContext().ensureActive()
                return@withContext null
            } catch (failure: Exception) {
                LOGGER.log(System.Logger.Level.WARNING, "Hyperlink provider failed: {0}", failure.javaClass.name)
                return@withContext null
            }
            ensureActive()
            sink.links
        }

    private fun isCurrent(
        binding: Long,
        source: Long,
        provider: Long,
    ): Boolean = !disposed && enabled && binding == bindingEpoch && source == sourceEpoch && provider == providerEpoch

    private fun scheduleRecovery() {
        if (recoveryJob != null || recoveryAttempt == RECOVERY_DELAYS.size) return
        val waitMillis = RECOVERY_DELAYS[recoveryAttempt++]
        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                delay(waitMillis)
                recoveryJob = null
                startAnalysis()
            }
        recoveryJob = job
        job.start()
    }

    private fun publishOverlay() {
        index.writeOverlay(host.renderCache, repaintSpan)
        host.hyperlinksChanged()
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
): SwingHyperlinkDetectionRequest =
    SwingHyperlinkDetectionRequest(
        lines.map { it.text },
        LongArray(lines.size) { lines[it].firstAbsoluteRow },
        LongArray(lines.size) { lines[it].lastAbsoluteRow },
        context,
        bindingEpoch,
        sourceEpoch,
        providerEpoch,
    )

/** Resolves absolute logical coordinates against owned snapshots, outside the mutation lock. */
internal class TerminalHyperlinkDetectionAccumulator(
    private val lines: List<TerminalHyperlinkLineSnapshot>,
) : SwingHyperlinkDetectionSink {
    val links = List(lines.size) { ArrayList<TerminalDetectedHyperlink>() }

    override fun addHyperlink(hyperlink: SwingHyperlink) {
        val source = hyperlink.sourceRange
        val dependency = hyperlink.dependencyRange
        val first = lines.indexOfFirst { it.firstAbsoluteRow == source.start.absoluteRow }
        val last = lines.indexOfFirst { it.firstAbsoluteRow == source.end.absoluteRow }
        val dependencyFirst = lines.indexOfFirst { it.firstAbsoluteRow == dependency.start.absoluteRow }
        val dependencyLast = lines.indexOfFirst { it.firstAbsoluteRow == dependency.end.absoluteRow }
        val producer = lines.indexOfFirst { it.firstAbsoluteRow == hyperlink.consumedThrough.absoluteRow }
        if (first < 0 || last < first || dependencyFirst < 0 || dependencyLast < dependencyFirst) return
        if (producer < 0 || hyperlink.consumedThrough.offset > lines[producer].text.length) return
        if (source.start.offset >= lines[first].text.length || source.end.offset > lines[last].text.length) return
        if (dependency.start.offset > lines[dependencyFirst].text.length ||
            dependency.end.offset > lines[dependencyLast].text.length
        ) {
            return
        }
        for (index in first..last) {
            val start = if (index == first) source.start.offset else 0
            val end = minOf(if (index == last) source.end.offset else lines[index].text.length, lines[index].text.length - 1)
            if (start >= end) continue
            val independent = dependencyFirst == index && dependencyLast == index
            links[index] +=
                TerminalDetectedHyperlink(
                    start,
                    end,
                    hyperlink,
                    if (independent) dependency.start.offset else 0,
                    if (independent) dependency.end.offset else lines[index].text.length,
                    viewportDependent = !independent,
                )
        }
    }
}

internal class TerminalDetectedHyperlink(
    val startOffset: Int,
    val endOffset: Int,
    val hyperlink: SwingHyperlink,
    val validationStartOffset: Int,
    val validationEndOffset: Int,
    val viewportDependent: Boolean = false,
) {
    fun remainsValid(
        previousText: String,
        currentText: String,
    ): Boolean =
        validationEndOffset <= currentText.length &&
            previousText.regionMatches(
                validationStartOffset,
                currentText,
                validationStartOffset,
                validationEndOffset - validationStartOffset,
            )
}

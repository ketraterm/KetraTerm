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
package io.github.ketraterm.ui.swing.search

import io.github.ketraterm.render.api.TerminalRenderBufferKind
import io.github.ketraterm.render.cache.TerminalRenderCache
import io.github.ketraterm.session.TerminalSession
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** EDT-owned search lifecycle and navigation. One worker owns copying/matching; completed buffers transfer on the EDT. */
internal class TerminalSearchController(
    private val host: TerminalSearchHost,
    private val scope: CoroutineScope,
    private val analysisDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private var query = ""
    private var ignoreCase = true
    private var highlights: TerminalSearchHighlights? = null
    private var model = TerminalSearchModel()
    private var publishedModel = TerminalSearchModel()
    private val scanner = TerminalSearchScan()
    private var job: Job? = null
    private var epoch = 0L
    private var pending = false
    private var scrollOnCompletion = false
    private var searchedSession: TerminalSession? = null
    private var searchedContentGeneration = 0L
    private var searchedBuffer: TerminalRenderBufferKind? = null
    private var searchedColumns = 0
    private var failure: Throwable? = null
    private val mutableState = MutableStateFlow(TerminalSearchState("", 0, -1))
    val states = mutableState.asStateFlow()
    val viewportHighlights = TerminalSearchViewportHighlights()

    fun state(): TerminalSearchState = states.value

    fun reset(viewportRows: Int) {
        epoch++
        job?.cancel()
        query = ""
        pending = false
        highlights = null
        failure = null
        searchedSession = null
        viewportHighlights.reset(viewportRows)
        publishState()
    }

    fun search(query: String) = applyQuery(query)

    fun clear() = applyQuery("")

    fun setIgnoreCase(ignoreCase: Boolean) {
        if (this.ignoreCase == ignoreCase) return
        this.ignoreCase = ignoreCase
        if (query.isNotEmpty()) applyQuery(query)
    }

    fun findNext(): Boolean = activateRelativeResult(1)

    fun findPrevious(): Boolean = activateRelativeResult(-1)

    fun refreshForFrame() {
        if (highlights != null && (searchedBuffer != host.renderCache.activeBuffer || searchedColumns != host.renderCache.columns)) {
            highlights = null
        }
        if (query.isNotEmpty() && failure == null) {
            val session = host.session
            if (session != null && (searchedSession !== session || searchedContentGeneration != host.renderCache.contentGeneration)) {
                pending = true
                startAnalysis()
            }
        }
        updateViewportHighlights()
    }

    fun updateViewportHighlights() {
        val current = highlights
        if (current == null) {
            viewportHighlights.reset(host.renderCache.rows)
        } else {
            current.buildViewportHighlights(host.renderCache, viewportHighlights)
        }
    }

    private fun applyQuery(nextQuery: String) {
        epoch++
        job?.cancel()
        query = nextQuery
        highlights = null
        failure = null
        searchedSession = null
        pending = query.isNotEmpty() && host.session != null
        scrollOnCompletion = pending
        updateViewportHighlights()
        startAnalysis()
        publishState()
        host.repaint()
    }

    private fun publishState() {
        mutableState.value =
            TerminalSearchState(
                query,
                highlights?.resultCount ?: 0,
                highlights?.activeResultIndex ?: -1,
                query.isNotEmpty() && (pending || job != null),
                failure,
            )
    }

    private fun startAnalysis() {
        if (job != null || !pending || !scope.isActive) return
        val session = host.session ?: return
        val requestEpoch = epoch
        val requestQuery = query
        val requestIgnoreCase = ignoreCase
        pending = false
        val task =
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    val result = withContext(analysisDispatcher) { scanner.scan(session, model, requestQuery, requestIgnoreCase) }
                    if (requestEpoch != epoch || host.session !== session) return@launch
                    if (result == null || result.columns != host.renderCache.columns || result.buffer != host.renderCache.activeBuffer) {
                        highlights = null
                        updateViewportHighlights()
                        return@launch
                    }
                    val oldRow = highlights?.activeStartAbsoluteRow() ?: NO_ACTIVE_ROW
                    val oldColumn = highlights?.activeStartColumn() ?: -1
                    val previous = publishedModel
                    publishedModel = model
                    model = previous
                    highlights = result.highlights
                    if (oldRow != NO_ACTIVE_ROW) result.highlights.activateNearest(oldRow, oldColumn)
                    searchedSession = session
                    searchedContentGeneration = result.generation
                    searchedBuffer = result.buffer
                    searchedColumns = result.columns
                    // Output may continue throughout a pass. Publish completed work before catching up.
                    pending = result.changedDuringScan || (pending && host.renderCache.contentGeneration != result.generation)
                    if (scrollOnCompletion) {
                        scrollOnCompletion = false
                        scrollToActiveResult()
                    }
                    updateViewportHighlights()
                    host.repaint()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (requestEpoch == epoch && host.session === session) {
                        failure = error
                        pending = false
                    }
                } finally {
                    job = null
                    publishState()
                    startAnalysis()
                }
            }
        job = task
        publishState()
        task.start()
    }

    private fun activateRelativeResult(delta: Int): Boolean {
        val currentHighlights = highlights ?: return false
        if (currentHighlights.resultCount == 0) return false
        val current =
            if (currentHighlights.activeResultIndex in 0 until currentHighlights.resultCount) {
                currentHighlights.activeResultIndex
            } else {
                0
            }
        val next = (current + delta + currentHighlights.resultCount) % currentHighlights.resultCount
        currentHighlights.activate(next)
        scrollToActiveResult()
        updateViewportHighlights()
        publishState()
        host.repaint()
        return true
    }

    private fun scrollToActiveResult() {
        val currentHighlights = highlights ?: return
        val activeRow = currentHighlights.activeStartAbsoluteRow()
        if (activeRow == NO_ACTIVE_ROW) return
        val centerRow = host.visibleGridRows() / 2
        val desiredOffset = host.renderCache.discardedCount + host.renderCache.historySize + centerRow - activeRow
        host.scrollViewportTo(
            desiredOffset.coerceIn(0L, host.renderCache.historySize.toLong()).toInt(),
            host.renderCache.historySize,
            host.session ?: return,
        )
    }

    private companion object {
        const val NO_ACTIVE_ROW = Long.MIN_VALUE
    }
}

/** UI-only hooks. Search scratch storage belongs exclusively to the worker. */
internal interface TerminalSearchHost {
    val session: TerminalSession?
    val renderCache: TerminalRenderCache

    fun visibleGridRows(): Int

    fun scrollViewportTo(
        offsetRows: Int,
        historySize: Int,
        boundSession: TerminalSession,
    ): Boolean

    fun repaint()
}

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
import java.util.regex.Pattern

/** EDT-owned search lifecycle and navigation. One worker owns copying/matching; completed buffers transfer on the EDT. */
internal class TerminalSearchController(
    private val host: TerminalSearchHost,
    private val scope: CoroutineScope,
    private val analysisDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private var limited = false
    private var activeMatch: TerminalSearchHighlights? = null
    private var visibleMatches: TerminalSearchHighlights? = null
    private var navigationJob: Job? = null
    private var pendingNavigation = 0
    private var viewportJob: Job? = null
    private var viewportTop = Long.MIN_VALUE
    private var viewportGeneration = Long.MIN_VALUE
    private var query = ""
    private var ignoreCase = true
    private var wholeWord = false
    private var regex = false
    private var preparedPattern: Pattern? = null
    private var highlights: TerminalSearchHighlights? = null
    private var model = TerminalSearchModel()
    private var publishedModel = TerminalSearchModel()
    private var scanner = TerminalSearchScan()
    private var job: Job? = null
    private var analysisStarted = false
    private var releaseStoragePending = false
    private var epoch = 0L
    private var pending = false
    private var revealFirstResult = false
    private var searchedSession: TerminalSession? = null
    private var searchedContentGeneration = 0L
    private var searchedHistoryContentGeneration = 0L
    private var searchedBuffer: TerminalRenderBufferKind? = null
    private var searchedColumns = 0
    private var failure: Throwable? = null
    private val mutableState = MutableStateFlow(TerminalSearchState("", 0, -1))
    val states = mutableState.asStateFlow()
    val viewportHighlights = TerminalSearchViewportHighlights()

    fun state(): TerminalSearchState = states.value

    fun reset(viewportRows: Int) {
        epoch++
        cancelAuxiliarySearches()
        cancelAnalysis()
        releaseStoragePending = true
        query = ""
        preparedPattern = null
        pending = false
        highlights = null
        failure = null
        searchedSession = null
        viewportHighlights.reset(viewportRows)
        releaseWorkingStorageIfIdle()
        publishState()
    }

    fun search(query: String) = applyQuery(query)

    fun clear() = applyQuery("")

    fun setIgnoreCase(ignoreCase: Boolean) {
        if (this.ignoreCase == ignoreCase) return
        this.ignoreCase = ignoreCase
        if (query.isNotEmpty()) applyQuery(query)
    }

    fun setWholeWord(wholeWord: Boolean) {
        if (this.wholeWord == wholeWord) return
        this.wholeWord = wholeWord
        if (query.isNotEmpty()) applyQuery(query)
    }

    fun setRegex(regex: Boolean) {
        if (this.regex == regex) return
        this.regex = regex
        if (query.isNotEmpty()) applyQuery(query)
    }

    fun findNext(): Boolean = activateRelativeResult(1)

    fun findPrevious(): Boolean = activateRelativeResult(-1)

    fun refreshForFrame() {
        if (highlights != null &&
            (
                searchedBuffer != host.renderCache.activeBuffer ||
                    searchedColumns != host.renderCache.columns ||
                    searchedHistoryContentGeneration != host.renderCache.historyContentGeneration
            )
        ) {
            epoch++
            cancelAuxiliarySearches()
            cancelAnalysis()
            highlights = null
            pending = query.isNotEmpty()
            publishState()
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
        if (limited) refreshViewportSearch()
        val current = if (limited) visibleMatches else highlights
        if (current == null) {
            viewportHighlights.reset(host.renderCache.rows)
        } else {
            current.buildViewportHighlights(host.renderCache, viewportHighlights)
        }
    }

    private fun applyQuery(nextQuery: String) {
        epoch++
        cancelAuxiliarySearches()
        cancelAnalysis()
        query = nextQuery
        preparedPattern = null
        if (query.isEmpty()) releaseStoragePending = true
        highlights = null
        failure = null
        searchedSession = null
        pending = query.isNotEmpty() && host.session != null
        revealFirstResult = pending
        updateViewportHighlights()
        startAnalysis()
        publishState()
        host.repaint()
    }

    private fun publishState() {
        mutableState.value =
            TerminalSearchState(
                query,
                minOf(highlights?.resultCount ?: 0, SEARCH_RESULT_LIMIT + 1),
                if (limited) -1 else highlights?.activeResultIndex ?: -1,
                query.isNotEmpty() && (pending || job != null || navigationJob != null),
                failure,
            )
    }

    private fun cancelAnalysis() {
        job?.cancel()
        // A cancelled queued coroutine never enters its body or its finally block.
        if (!analysisStarted) job = null
    }

    private fun releaseWorkingStorageIfIdle() {
        if (!releaseStoragePending || job != null) return
        model = TerminalSearchModel()
        publishedModel = TerminalSearchModel()
        scanner = TerminalSearchScan()
        releaseStoragePending = false
    }

    private fun startAnalysis() {
        releaseWorkingStorageIfIdle()
        if (job != null || !pending || !scope.isActive) return
        val session = host.session ?: return
        val requestEpoch = epoch
        val requestQuery = query
        val requestIgnoreCase = ignoreCase
        val requestWholeWord = wholeWord
        val requestRegex = regex
        val requestScanner = scanner
        val requestHistoryContentGeneration = host.renderCache.historyContentGeneration
        val showProgress = highlights == null
        val bounded = showProgress || limited || requestWholeWord || requestRegex
        val origin = host.renderCache.discardedCount + host.renderCache.historySize - host.renderCache.scrollbackOffset
        pending = false
        val task =
            scope.launch(start = CoroutineStart.LAZY) {
                analysisStarted = true
                try {
                    val pattern =
                        if (requestWholeWord || requestRegex) {
                            preparedPattern ?: withContext(analysisDispatcher) {
                                compileSearchPattern(requestQuery, requestIgnoreCase, requestWholeWord, requestRegex)
                            }
                        } else {
                            null
                        }
                    if (requestEpoch != epoch || host.session !== session) return@launch
                    preparedPattern = pattern
                    val publicationContext = currentCoroutineContext()
                    val result =
                        withContext(analysisDispatcher) {
                            val progress: (suspend (TerminalSearchScan.Progress) -> Unit)? =
                                if (!showProgress) {
                                    null
                                } else {
                                    { progress ->
                                        withContext(publicationContext.minusKey(Job)) {
                                            if (requestEpoch == epoch &&
                                                host.session === session &&
                                                progress.columns == host.renderCache.columns &&
                                                progress.buffer == host.renderCache.activeBuffer &&
                                                progress.historyContentGeneration == host.renderCache.historyContentGeneration
                                            ) {
                                                val accumulated =
                                                    highlights ?: TerminalSearchHighlights().also {
                                                        it.validatesRows = true
                                                        highlights = it
                                                    }
                                                accumulated.appendResults(progress.highlights)
                                                limited = accumulated.resultCount > SEARCH_RESULT_LIMIT
                                                if (!accumulated.hasActiveResult) accumulated.activate(0)
                                                searchedColumns = progress.columns
                                                searchedBuffer = progress.buffer
                                                searchedHistoryContentGeneration = progress.historyContentGeneration
                                                if (revealFirstResult) {
                                                    revealFirstResult = false
                                                    scrollToActiveResult()
                                                }
                                                updateViewportHighlights()
                                                publishState()
                                                host.repaint()
                                            }
                                        }
                                    }
                                }
                            if (bounded) {
                                TerminalSearchWindow(session, requestQuery, requestIgnoreCase, pattern).collect(origin, progress)
                            } else {
                                requestScanner.scan(session, model, requestQuery, requestIgnoreCase, SEARCH_RESULT_LIMIT + 1, progress)
                            }
                        }
                    if (requestEpoch != epoch || host.session !== session) return@launch
                    if (result == null ||
                        result.columns != host.renderCache.columns ||
                        result.buffer != host.renderCache.activeBuffer ||
                        requestHistoryContentGeneration != host.renderCache.historyContentGeneration
                    ) {
                        highlights = null
                        updateViewportHighlights()
                        return@launch
                    }
                    val oldActive = activeMatch ?: highlights
                    val oldRow = oldActive?.activeStartAbsoluteRow() ?: NO_ACTIVE_ROW
                    val oldColumn = oldActive?.activeStartColumn() ?: -1
                    if (!bounded) {
                        val previous = publishedModel
                        publishedModel = model
                        model = previous
                    }
                    highlights = result.highlights
                    limited = result.highlights.resultCount > SEARCH_RESULT_LIMIT
                    if (!limited) activeMatch = null
                    if (oldRow != NO_ACTIVE_ROW) result.highlights.activateNearest(oldRow, oldColumn)
                    if (limited && activeMatch == null && result.highlights.hasActiveResult) {
                        val selected = result.highlights.activeResultIndex
                        activeMatch =
                            TerminalSearchHighlights().also {
                                it.appendResults(result.highlights, selected, selected + 1)
                                it.activate(0)
                            }
                    }
                    searchedSession = session
                    searchedContentGeneration = result.generation
                    searchedHistoryContentGeneration = result.historyContentGeneration
                    searchedBuffer = result.buffer
                    searchedColumns = result.columns
                    // Output may continue throughout a pass. Publish completed work before catching up.
                    pending = result.changedDuringScan || (pending && host.renderCache.contentGeneration != result.generation)
                    if (revealFirstResult) {
                        revealFirstResult = false
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
                        if (showProgress) {
                            highlights = null
                            updateViewportHighlights()
                            host.repaint()
                        }
                    }
                } finally {
                    job = null
                    analysisStarted = false
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
        if (limited) return navigateLimited(delta < 0)
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
        val currentHighlights = activeMatch ?: highlights ?: return
        val activeRow = currentHighlights.activeStartAbsoluteRow()
        if (activeRow == NO_ACTIVE_ROW) return
        host.scrollToAbsoluteRow(activeRow, center = true)
    }

    private fun cancelAuxiliarySearches() {
        navigationJob?.cancel()
        navigationJob = null
        pendingNavigation = 0
        viewportJob?.cancel()
        viewportJob = null
        limited = false
        activeMatch = null
        visibleMatches = null
        viewportTop = Long.MIN_VALUE
        viewportGeneration = Long.MIN_VALUE
    }

    private fun navigateLimited(backwards: Boolean): Boolean {
        if (navigationJob != null) {
            pendingNavigation += if (backwards) -1 else 1
            return true
        }
        val session = host.session ?: return false
        val active = activeMatch ?: highlights ?: return false
        if (!active.hasActiveResult) return false
        val row = active.activeStartAbsoluteRow()
        val column = active.activeStartColumn()
        val requestEpoch = epoch
        val requestQuery = query
        val requestIgnoreCase = ignoreCase
        val requestPattern = preparedPattern
        navigationJob?.cancel()
        val task =
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    val found =
                        withContext(analysisDispatcher) {
                            TerminalSearchWindow(session, requestQuery, requestIgnoreCase, requestPattern).find(row, column, backwards)
                        }
                    if (requestEpoch != epoch || host.session !== session) return@launch
                    activeMatch = found
                    scrollToActiveResult()
                    viewportGeneration = Long.MIN_VALUE
                    updateViewportHighlights()
                    host.repaint()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (requestEpoch == epoch) failure = error
                } finally {
                    if (navigationJob === currentCoroutineContext()[Job]) {
                        navigationJob = null
                        if (pendingNavigation != 0 && requestEpoch == epoch && failure == null) {
                            val previous = pendingNavigation < 0
                            pendingNavigation += if (previous) 1 else -1
                            navigateLimited(previous)
                        }
                        publishState()
                    }
                }
            }
        navigationJob = task
        task.start()
        publishState()
        return true
    }

    private fun refreshViewportSearch() {
        val session = host.session ?: return
        val cache = host.renderCache
        val top = cache.discardedCount + cache.historySize - cache.scrollbackOffset
        if (top == viewportTop && cache.contentGeneration == viewportGeneration) return
        viewportTop = top
        viewportGeneration = cache.contentGeneration
        val generation = viewportGeneration
        val bottom = top + cache.rows - 1
        val requestEpoch = epoch
        val requestQuery = query
        val requestIgnoreCase = ignoreCase
        val requestPattern = preparedPattern
        viewportJob?.cancel()
        visibleMatches = null
        viewportJob =
            scope.launch {
                try {
                    val found =
                        withContext(analysisDispatcher) {
                            TerminalSearchWindow(session, requestQuery, requestIgnoreCase, requestPattern).visible(top, bottom)
                        }
                    if (requestEpoch != epoch ||
                        host.session !== session ||
                        top != viewportTop ||
                        generation != viewportGeneration
                    ) {
                        return@launch
                    }
                    val active = activeMatch ?: highlights
                    found.activate(-1)
                    if (active != null && active.hasActiveResult) {
                        found.activateNearest(active.activeStartAbsoluteRow(), active.activeStartColumn())
                        if (found.activeStartAbsoluteRow() != active.activeStartAbsoluteRow() ||
                            found.activeStartColumn() != active.activeStartColumn()
                        ) {
                            found.activate(-1)
                        }
                    }
                    visibleMatches = found
                    found.buildViewportHighlights(cache, viewportHighlights)
                    host.repaint()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (requestEpoch == epoch) {
                        failure = error
                        publishState()
                    }
                }
            }
    }

    private companion object {
        const val NO_ACTIVE_ROW = Long.MIN_VALUE
    }
}

/** UI-only hooks. Search scratch storage belongs exclusively to the worker. */
internal interface TerminalSearchHost {
    val session: TerminalSession?
    val renderCache: TerminalRenderCache

    /** Reveals a source row using the component's current presentation geometry. */
    fun scrollToAbsoluteRow(
        row: Long,
        center: Boolean,
    ): Boolean

    fun repaint()
}

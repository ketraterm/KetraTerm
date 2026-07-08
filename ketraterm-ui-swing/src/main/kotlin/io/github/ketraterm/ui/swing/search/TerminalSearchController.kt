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

import io.github.ketraterm.render.cache.TerminalRenderCache
import io.github.ketraterm.session.TerminalSession

/**
 * EDT-owned terminal search controller.
 *
 * The controller owns literal match scanning, active-result navigation, and
 * viewport highlight projection. It does not own visible search chrome, does
 * not paint, and does not allocate from the frame paint loop; painting consumes
 * [viewportHighlights], which is rebuilt only when search state or viewport
 * mapping changes.
 *
 * @param host Swing terminal hooks needed to refresh caches and move viewport.
 */
internal class TerminalSearchController(
    private val host: TerminalSearchHost,
) {
    private var query: String = ""
    private var highlights: TerminalSearchHighlights? = null
    private var ignoreCase: Boolean = true

    private val model = TerminalSearchModel()

    val viewportHighlights = TerminalSearchViewportHighlights()

    fun reset(viewportRows: Int) {
        query = ""
        highlights = null
        viewportHighlights.reset(viewportRows)
    }

    fun search(query: String) {
        applyQuery(query)
    }

    fun clear() {
        applyQuery("")
    }

    fun setIgnoreCase(ignoreCase: Boolean) {
        if (this.ignoreCase == ignoreCase) return
        this.ignoreCase = ignoreCase
        if (query.isNotEmpty()) applyQuery(query)
    }

    fun findNext(): Boolean = activateRelativeResult(1)

    fun findPrevious(): Boolean = activateRelativeResult(-1)

    fun state(): TerminalSearchState =
        TerminalSearchState(
            query = query,
            resultCount = highlights?.resultCount ?: 0,
            activeResultIndex = highlights?.activeResultIndex ?: NO_ACTIVE_RESULT,
        )

    fun refreshForFrame() {
        if (query.isEmpty()) {
            updateViewportHighlights()
            return
        }

        val boundSession = host.session ?: return
        val oldActive = highlights?.activeResultIndex ?: NO_ACTIVE_RESULT
        refreshSearchCache(boundSession)
        val nextHighlights = model.search(host.searchCache, query, ignoreCase = ignoreCase)
        if (oldActive in 0 until nextHighlights.resultCount) {
            nextHighlights.activate(oldActive)
        }
        highlights = nextHighlights
        updateViewportHighlights()
    }

    fun updateViewportHighlights() {
        val currentHighlights = highlights
        if (currentHighlights == null) {
            viewportHighlights.reset(host.renderCache.rows)
            return
        }
        currentHighlights.buildViewportHighlights(host.renderCache, viewportHighlights)
    }

    private fun applyQuery(nextQuery: String) {
        query = nextQuery
        if (nextQuery.isEmpty()) {
            highlights = null
            viewportHighlights.reset(host.renderCache.rows)
            host.repaint()
            return
        }

        val boundSession = host.session ?: return
        refreshSearchCache(boundSession)
        highlights = model.search(host.searchCache, nextQuery, ignoreCase = ignoreCase)
        scrollToActiveResult()
        updateViewportHighlights()
        host.repaint()
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

    private fun refreshSearchCache(boundSession: TerminalSession) {
        val historySize = host.renderCache.historySize
        host.searchCache.updateFrom(
            reader = boundSession,
            scrollbackOffset = historySize,
            viewportRows = (historySize + host.visibleGridRows()).coerceAtLeast(1),
        )
    }

    private companion object {
        private const val NO_ACTIVE_RESULT = -1
        private const val NO_ACTIVE_ROW = Long.MIN_VALUE
    }
}

/**
 * Narrow host contract required by [TerminalSearchController].
 */
internal interface TerminalSearchHost {
    val session: TerminalSession?
    val renderCache: TerminalRenderCache
    val searchCache: TerminalRenderCache

    fun visibleGridRows(): Int

    fun scrollViewportTo(
        offsetRows: Int,
        historySize: Int,
        boundSession: TerminalSession,
    ): Boolean

    fun repaint()
}

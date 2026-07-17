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
package io.github.ketraterm.intellij.ui

import com.intellij.execution.filters.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.psi.search.GlobalSearchScope
import io.github.ketraterm.ui.swing.api.SwingHyperlinkAction
import io.github.ketraterm.ui.swing.api.SwingHyperlinkDetectionRequest
import io.github.ketraterm.ui.swing.api.SwingHyperlinkDetectionSink
import io.github.ketraterm.ui.swing.api.SwingHyperlinkDetector

/**
 * IntelliJ-backed detector for visible terminal text hyperlinks.
 *
 * The detector adapts IntelliJ console filters to KetraTerm's primitive
 * viewport hyperlink overlay. Filter execution happens under read access on
 * the reusable terminal's background analysis worker; only compact actions are
 * handed back to Swing for later explicit activation.
 */
internal class IntellijTerminalHyperlinkDetector(
    private val project: Project,
) : SwingHyperlinkDetector {
    override fun detect(
        request: SwingHyperlinkDetectionRequest,
        sink: SwingHyperlinkDetectionSink,
    ) {
        if (project.isDisposed) return
        ApplicationManager.getApplication().runReadAction {
            if (project.isDisposed) return@runReadAction
            val filter = CompositeFilter(project, defaultFilters())
            val emittedRanges = HashSet<DetectedRangeKey>()
            var lineIndex = 0
            while (lineIndex < request.lineCount) {
                applyFilter(request, sink, filter, emittedRanges, lineIndex)
                lineIndex++
            }
        }
    }

    private fun applyFilter(
        request: SwingHyperlinkDetectionRequest,
        sink: SwingHyperlinkDetectionSink,
        filter: CompositeFilter,
        emittedRanges: MutableSet<DetectedRangeKey>,
        lineIndex: Int,
    ) {
        val lineText = request.lineText(lineIndex)
        val lineStartOffset = request.lineStartOffset(lineIndex)
        val lineEndOffset = request.lineEndOffset(lineIndex)
        val result =
            try {
                filter.applyFilter(lineText, lineEndOffset)
            } catch (exception: ProcessCanceledException) {
                throw exception
            } catch (_: Exception) {
                null
            }
        if (result == null) return

        for (item in result.resultItems) {
            val hyperlinkInfo = item.hyperlinkInfo ?: continue
            val startOffset = item.highlightStartOffset.coerceIn(lineStartOffset, lineEndOffset)
            val endOffset = item.highlightEndOffset.coerceIn(startOffset, lineEndOffset)
            if (startOffset >= endOffset) continue

            val rangeKey = DetectedRangeKey(lineIndex, startOffset, endOffset)
            if (!emittedRanges.add(rangeKey)) continue

            sink.addHyperlink(
                lineIndex = lineIndex,
                startOffset = startOffset - lineStartOffset,
                endOffset = endOffset - lineStartOffset,
                action = IntellijTerminalHyperlinkAction(project, hyperlinkInfo),
            )
        }
    }

    private fun defaultFilters(): List<Filter> {
        val filters = ArrayList<Filter>()
        filters += UrlFilter(project)

        val scope = GlobalSearchScope.allScope(project)
        val providers =
            try {
                ConsoleFilterProvider.FILTER_PROVIDERS.getExtensionList(ApplicationManager.getApplication())
            } catch (exception: ProcessCanceledException) {
                throw exception
            } catch (_: IllegalArgumentException) {
                emptyList()
            }
        for (provider in providers) {
            val providerFilters =
                try {
                    if (provider is ConsoleFilterProviderEx) {
                        provider.getDefaultFilters(project, scope)
                    } else {
                        provider.getDefaultFilters(project)
                    }
                } catch (exception: ProcessCanceledException) {
                    throw exception
                } catch (_: Throwable) {
                    Filter.EMPTY_ARRAY
                }
            for (filter in providerFilters) {
                if (filter.javaClass == UrlFilter::class.java) continue
                filters += filter
            }
        }
        return filters
    }

    private data class DetectedRangeKey(
        val lineIndex: Int,
        val startOffset: Int,
        val endOffset: Int,
    )
}

private class IntellijTerminalHyperlinkAction(
    private val project: Project,
    private val hyperlinkInfo: HyperlinkInfo,
) : SwingHyperlinkAction {
    override fun open(): Boolean {
        if (project.isDisposed) return false
        return try {
            hyperlinkInfo.navigate(project)
            true
        } catch (_: Exception) {
            false
        }
    }
}

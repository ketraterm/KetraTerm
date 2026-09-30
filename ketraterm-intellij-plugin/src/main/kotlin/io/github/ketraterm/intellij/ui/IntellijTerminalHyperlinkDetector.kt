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
import com.intellij.openapi.application.readActionBlocking
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.search.GlobalSearchScope
import io.github.ketraterm.ui.swing.api.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.CancellationException

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
    // Console filters can carry exception/navigation context between consecutive lines.
    override val context: SwingHyperlinkDetectionContext = SwingHyperlinkDetectionContext.ORDERED_CONTENT

    override suspend fun detect(
        request: SwingHyperlinkDetectionRequest,
        sink: SwingHyperlinkDetectionSink,
    ) {
        val coroutineContext = currentCoroutineContext()
        coroutineContext.ensureActive()
        if (project.isDisposed) return
        try {
            // Filters and sink writes are stateful, so this batch cannot be retried by readAction.
            readActionBlocking {
                if (project.isDisposed) return@readActionBlocking
                val urlFilter = UrlFilter(project)
                val providerFilter = CompositeFilter(project, providerFilters())
                val emittedRanges = HashSet<DetectedRangeKey>()
                var lineIndex = 0
                while (lineIndex < request.lineCount) {
                    coroutineContext.ensureActive()
                    ProgressManager.checkCanceled()
                    applyFilter(request, sink, urlFilter, providerFilter, emittedRanges, lineIndex)
                    lineIndex++
                }
            }
        } catch (
            @Suppress("IncorrectCancellationExceptionHandling") exception: ProcessCanceledException,
        ) {
            // Platform cancellation must not become a cached "no links" result in the reusable worker.
            throw CancellationException("IntelliJ hyperlink detection cancelled").apply { initCause(exception) }
        }
    }

    private fun applyFilter(
        request: SwingHyperlinkDetectionRequest,
        sink: SwingHyperlinkDetectionSink,
        urlFilter: UrlFilter,
        providerFilter: CompositeFilter,
        emittedRanges: MutableSet<DetectedRangeKey>,
        lineIndex: Int,
    ) {
        val lineText = request.lineText(lineIndex)
        val lineEndOffset = request.lineEndOffset(lineIndex)
        // UrlFilter precedes provider filters and returns EXIT for every match.
        // Keep its provenance so only its text-derived actions can outlive edits elsewhere.
        var urlResult: Filter.Result? = null
        val result =
            try {
                urlResult = urlFilter.applyFilter(lineText, lineEndOffset)
                urlResult ?: providerFilter.applyFilter(lineText, lineEndOffset)
            } catch (exception: ProcessCanceledException) {
                throw exception
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                null
            }
        if (result == null) return

        for (item in result.resultItems) {
            val hyperlinkInfo = item.hyperlinkInfo ?: continue
            val startOffset = item.highlightStartOffset
            val endOffset = item.highlightEndOffset
            if (startOffset < 0 || endOffset <= startOffset || endOffset > lineEndOffset) continue
            val first =
                (0..lineIndex).firstOrNull { startOffset >= request.lineStartOffset(it) && startOffset < request.lineEndOffset(it) }
                    ?: continue
            val last = (first..lineIndex).firstOrNull { endOffset <= request.lineEndOffset(it) } ?: continue
            if (!emittedRanges.add(DetectedRangeKey(first, startOffset, endOffset))) continue
            val source = request.range(first, startOffset - request.lineStartOffset(first), last, endOffset - request.lineStartOffset(last))
            val validation =
                if (urlResult != null && first == last) {
                    urlValidationRange(request.lineText(first), source.start.offset, source.end.offset)
                } else {
                    null
                }
            val dependency =
                if (validation == null) {
                    request.range(0, 0, lineIndex, lineText.length)
                } else {
                    request.range(first, validation.startOffset, first, validation.endOffset)
                }
            val uri =
                if (urlResult != null &&
                    first == last
                ) {
                    request.lineText(first).substring(source.start.offset, source.end.offset)
                } else {
                    null
                }
            sink.addHyperlink(
                SwingHyperlink(
                    sourceRange = source,
                    dependencyRange = dependency,
                    action = IntellijTerminalHyperlinkAction(project, hyperlinkInfo),
                    uri = uri,
                    consumedThrough = SwingHyperlinkTextPosition(request.lineFirstAbsoluteRow(lineIndex), lineText.length),
                ),
            )
        }
    }

    private fun providerFilters(): List<Filter> {
        val filters = ArrayList<Filter>()

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
                } catch (exception: CancellationException) {
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

/** Includes the full token and its delimiters because punctuation can extend a URL match later. */
internal fun urlValidationRange(
    lineText: String,
    startOffset: Int,
    endOffset: Int,
): TextRange {
    var start = startOffset
    while (start > 0 && !isUrlTokenDelimiter(lineText[start - 1])) start--
    if (start > 0) start--
    var end = endOffset
    while (end < lineText.length && !isUrlTokenDelimiter(lineText[end])) end++
    if (end < lineText.length) end++
    return TextRange(start, end)
}

// URLUtil's URL and file patterns stop at ASCII regex whitespace, not all Unicode whitespace.
private fun isUrlTokenDelimiter(character: Char): Boolean = character == ' ' || character in '\t'..'\r'

private class IntellijTerminalHyperlinkAction(
    private val project: Project,
    private val hyperlinkInfo: HyperlinkInfo,
) : SwingHyperlinkAction {
    override fun open(): Boolean =
        !project.isDisposed &&
            try {
                hyperlinkInfo.navigate(project)
                true
            } catch (_: Exception) {
                false
            }
}

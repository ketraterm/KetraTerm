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
import com.intellij.openapi.application.readAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootEvent
import com.intellij.openapi.roots.ModuleRootListener
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.psi.search.GlobalSearchScope
import io.github.ketraterm.ui.swing.api.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.net.URI
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.*
import java.util.concurrent.atomic.AtomicLong

/**
 * Independent URL/path discovery and a serialized ordered console-filter chain.
 * Ordered offsets are rebuilt across binding/source/configuration/replay epochs;
 * console providers control the lifetime of the filter instances they return.
 */
internal class IntellijTerminalHyperlinkDetector(
    private val project: Project,
    private val launchDirectory: Path = Path.of(System.getProperty("user.home")),
    private val directoryForLine: (Long) -> String? = { null },
) : SwingHyperlinkDetector {
    override val context = SwingHyperlinkDetectionContext.INDEPENDENT_AND_ORDERED
    private val revision = AtomicLong()
    override val configurationGeneration: Long
        get() = revision.get() + DumbService.getInstance(project).modificationTracker.modificationCount

    override val configurationChanges: Flow<Unit>
        get() =
            callbackFlow {
                val lifetime = Disposer.newDisposable("KetraTerm hyperlink providers")
                val changed = {
                    revision.incrementAndGet()
                    trySend(Unit)
                    Unit
                }
                val connection = project.messageBus.connect(lifetime)
                connection.subscribe(
                    DumbService.DUMB_MODE,
                    object : DumbService.DumbModeListener {
                        override fun enteredDumbMode() = changed()

                        override fun exitDumbMode() = changed()
                    },
                )
                connection.subscribe(
                    ModuleRootListener.TOPIC,
                    object : ModuleRootListener {
                        override fun rootsChanged(event: ModuleRootEvent) = changed()
                    },
                )
                val applicationConnection = ApplicationManager.getApplication().messageBus.connect(lifetime)
                applicationConnection.subscribe(
                    VirtualFileManager.VFS_CHANGES,
                    object : BulkFileListener {
                        override fun after(events: List<VFileEvent>) {
                            if (events.any { it !is VFileContentChangeEvent }) changed()
                        }
                    },
                )
                applicationConnection.subscribe(
                    EditorColorsManager.TOPIC,
                    object : EditorColorsListener {
                        override fun globalSchemeChange(scheme: EditorColorsScheme?) = changed()
                    },
                )
                ConsoleFilterProvider.FILTER_PROVIDERS.addChangeListener(this, Runnable { changed() })
                awaitClose { Disposer.dispose(lifetime) }
            }

    private val urlFilter = UrlFilter(project)

    // Only ordered requests access this state. Independent calls do not wait for it.
    private var ordered: OrderedState? = null

    override fun discardOrderedState() {
        ordered = null
    }

    override suspend fun detect(request: SwingHyperlinkDetectionRequest): List<SwingHyperlink> {
        currentCoroutineContext().ensureActive()
        if (project.isDisposed) return emptyList()
        return when (request.context) {
            SwingHyperlinkDetectionContext.INDEPENDENT_LINE -> detectIndependent(request)
            SwingHyperlinkDetectionContext.ORDERED_CONTENT -> detectOrdered(request)
            SwingHyperlinkDetectionContext.INDEPENDENT_AND_ORDERED -> error("Discovery requests must select one lane")
        }
    }

    private suspend fun detectIndependent(request: SwingHyperlinkDetectionRequest): List<SwingHyperlink> {
        val results = ArrayList<SwingHyperlink>()
        for (line in 0 until request.lineCount) {
            currentCoroutineContext().ensureActive()
            val text = request.lineText(line)
            val urls =
                readAction {
                    urlFilter.applyFilter(text, text.length)?.resultItems.orEmpty().mapNotNull { item ->
                        val info = item.hyperlinkInfo ?: return@mapNotNull null
                        val start = item.highlightStartOffset
                        val end = item.highlightEndOffset
                        if (start < 0 || end <= start || end > text.length) return@mapNotNull null
                        val validation = urlValidationRange(text, start, end)
                        request.hyperlink(
                            line,
                            start,
                            end,
                            IntellijTerminalHyperlinkAction(project, info),
                            validation.startOffset,
                            validation.endOffset,
                            text.substring(start, end),
                            intellijHyperlinkPresentation(item),
                            intellijHyperlinkActivation(item),
                        )
                    }
                }
            results.addAll(urls)
            val directory = outputDirectory(request.lineFirstId(line))
            for (match in FILE_REFERENCE.findAll(text)) {
                currentCoroutineContext().ensureActive()
                val start = match.range.first
                val end = match.range.last + 1
                if (urls.any { start < it.sourceRange.end.offset && end > it.sourceRange.start.offset }) continue
                val raw = match.groups[1]?.value ?: match.groups[2]?.value ?: match.groups[3]?.value ?: continue
                if (raw == "." || raw == ".." || raw.all { it == '/' || it == '\\' }) continue
                val path =
                    try {
                        val parsed = Path.of(raw)
                        if (parsed.isAbsolute) parsed.normalize() else (directory ?: continue).resolve(parsed).normalize()
                    } catch (_: InvalidPathException) {
                        continue
                    }
                val lineNumber = fileCoordinate(match.groups[4]?.value ?: match.groups[6]?.value) ?: continue
                val column = fileCoordinate(match.groups[5]?.value ?: match.groups[7]?.value) ?: continue
                val result =
                    readAction {
                        val file = LocalFileSystem.getInstance().findFileByNioFile(path) ?: return@readAction null
                        if (!file.isValid) return@readAction null
                        val info = OpenFileHyperlinkInfo(project, file, lineNumber, column)
                        val item = Filter.ResultItem(start, end, info).also { it.isInvisibleLink = true }
                        request.hyperlink(
                            line,
                            start,
                            end,
                            IntellijTerminalHyperlinkAction(project, info),
                            uri = path.toUri().toASCIIString(),
                            presentation = intellijHyperlinkPresentation(item),
                            activation = intellijHyperlinkActivation(item),
                        )
                    }
                if (result != null) results.add(result)
            }
        }
        return results
    }

    private fun fileCoordinate(value: String?): Int? = if (value == null) 0 else value.toIntOrNull()?.takeIf { it > 0 }?.minus(1)

    private fun outputDirectory(lineId: Long): Path? {
        val uri = directoryForLine(lineId) ?: return launchDirectory
        return try {
            val parsed = URI(uri)
            if (parsed.scheme != "file" || !parsed.authority.isNullOrEmpty() && parsed.authority != "localhost") return null
            Path.of(URI("file", null, parsed.path, null)).normalize()
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: java.net.URISyntaxException) {
            null
        }
    }

    private suspend fun detectOrdered(request: SwingHyperlinkDetectionRequest): List<SwingHyperlink> {
        if (request.lineCount == 0) return emptyList()
        val key = Epochs(request.bindingEpoch, request.sourceEpoch, request.providerEpoch, request.analysisEpoch, configurationGeneration)
        var state = ordered
        if (state == null || state.epochs != key) {
            val filters = readAction { providerFilters() }
            state = OrderedState(key, filters)
            ordered = state
        }
        val chain = state
        val results = ArrayList<SwingHyperlink>()
        try {
            while (chain.lines
                    .firstEntry()
                    ?.value
                    ?.lastRow
                    ?.let { it < request.firstRetainedRow } == true
            ) {
                chain.lines.pollFirstEntry()
            }
            for (line in 0 until request.lineCount) {
                currentCoroutineContext().ensureActive()
                val text = request.lineText(line)
                val start = chain.offset
                val end = Math.addExact(start, text.length)
                val source = OrderedLine(request.lineFirstAbsoluteRow(line), request.lineLastAbsoluteRow(line))
                chain.lines[start] = source
                if (chain.start == null) chain.start = SwingHyperlinkTextPosition(source.firstRow, 0)
                for ((order, filter) in chain.filters.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    var invoked = false
                    var retried = false
                    val providerResults =
                        readAction {
                            // A write action can retry this lambda. Never retry mutated provider state.
                            if (invoked) {
                                retried = true
                                return@readAction emptyList()
                            }
                            if (!DumbService.getInstance(project).isUsableInCurrentContext(filter)) return@readAction emptyList()
                            invoked = true
                            val result = filter.applyFilter(text, end)
                            ProgressManager.checkCanceled()
                            result?.resultItems.orEmpty().mapNotNull { item ->
                                val info = item.hyperlinkInfo ?: return@mapNotNull null
                                val first = chain.lines.floorEntry(item.highlightStartOffset) ?: return@mapNotNull null
                                val last = chain.lines.floorEntry(item.highlightEndOffset - 1) ?: return@mapNotNull null
                                if (item.highlightEndOffset <= item.highlightStartOffset ||
                                    item.highlightEndOffset > end
                                ) {
                                    return@mapNotNull null
                                }
                                val range =
                                    SwingHyperlinkTextRange(
                                        SwingHyperlinkTextPosition(first.value.firstRow, item.highlightStartOffset - first.key),
                                        SwingHyperlinkTextPosition(last.value.firstRow, item.highlightEndOffset - last.key),
                                    )
                                val consumed = SwingHyperlinkTextPosition(source.firstRow, text.length)
                                SwingHyperlink(
                                    range,
                                    SwingHyperlinkTextRange(checkNotNull(chain.start), consumed),
                                    IntellijTerminalHyperlinkAction(project, info),
                                    presentation = intellijHyperlinkPresentation(item),
                                    activation = intellijHyperlinkActivation(item),
                                    consumedThrough = consumed,
                                    providerOrder = order + 2,
                                )
                            }
                        }
                    if (retried) throw CancellationException("Console read interrupted; ordered replay required")
                    results.addAll(providerResults)
                }
                chain.offset = end
            }
        } catch (failure: Throwable) {
            ordered = null
            throw failure
        }
        return results
    }

    private fun providerFilters(): List<Filter> {
        val scope = GlobalSearchScope.allScope(project)
        val providers =
            try {
                ConsoleFilterProvider.FILTER_PROVIDERS.getExtensionList(ApplicationManager.getApplication())
            } catch (_: IllegalArgumentException) {
                emptyList()
            }
        return providers.flatMap { provider ->
            try {
                (
                    if (provider is ConsoleFilterProviderEx) {
                        provider.getDefaultFilters(
                            project,
                            scope,
                        )
                    } else {
                        provider.getDefaultFilters(project)
                    }
                ).filter { it.javaClass != UrlFilter::class.java }
            } catch (
                cancelled: ProcessCanceledException,
            ) {
                throw cancelled
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                LOG.warn("Console hyperlink provider configuration failed: " + provider.javaClass.name, failure)
                throw failure
            }
        }
    }

    private data class Epochs(
        val binding: Long,
        val source: Long,
        val provider: Long,
        val replay: Long,
        val configuration: Long,
    )

    private class OrderedState(
        val epochs: Epochs,
        val filters: List<Filter>,
    ) {
        val lines = TreeMap<Int, OrderedLine>()
        var offset = 0
        var start: SwingHyperlinkTextPosition? = null
    }

    private class OrderedLine(
        val firstRow: Long,
        val lastRow: Long,
    )

    private companion object {
        val LOG = Logger.getInstance(IntellijTerminalHyperlinkDetector::class.java)
        val FILE_REFERENCE =
            Regex("""(?:"([^"\r\n]+)"|'([^'\r\n]+)'|((?:[A-Za-z]:[\\/])?[^\s"'<>|:()]+))(?::(\d+)(?::(\d+))?|\((\d+)(?:,(\d+))?\))?""")
    }
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

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
package io.github.ketraterm.intellij.services

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.psi.codeStyle.NameUtil
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.Processor
import com.intellij.util.text.matching.MatchingMode
import io.github.ketraterm.completion.api.*
import io.github.ketraterm.completion.host.TerminalLocalFileUriResolver
import java.nio.file.Path

/**
 * Queries IntelliJ's project filename index for a bounded fuzzy path snapshot.
 *
 * Matching occurs before bounding, so large projects cannot hide relevant files
 * behind an arbitrary traversal cutoff. The result remains a shell-facing path
 * snapshot relative to the terminal working directory; shared completion still
 * owns path context, hidden-file policy, replacement ranges, and shell quoting.
 *
 * @property project project whose filename index is queried.
 */
internal class IntellijProjectFileLoader(
    private val project: Project,
) {
    /**
     * Returns bounded deterministic project paths matching [prefix].
     *
     * @param workingDirectoryUri local `file` URI used to relativize project entries.
     * @param prefix decoded active terminal path token.
     * @return ready project paths, or an empty list for disposed projects and
     * unsupported working-directory URIs.
     */
    fun load(
        workingDirectoryUri: String?,
        prefix: String,
    ): List<TerminalFuzzyPathEntry> {
        if (project.isDisposed) return emptyList()
        val normalizedPrefix = prefix.replace('\\', '/')
        val fileNamePrefix = activeFileNamePrefix(normalizedPrefix)
        if (fileNamePrefix.isEmpty()) return emptyList()
        val workingDirectory =
            TerminalLocalFileUriResolver.resolve(workingDirectoryUri)
                ?: project.basePath?.let { runCatching { Path.of(it) }.getOrNull() }
                ?: return emptyList()
        return ApplicationManager.getApplication().runReadAction<List<TerminalFuzzyPathEntry>> {
            if (project.isDisposed) return@runReadAction emptyList()
            val scope = GlobalSearchScope.projectScope(project)
            val fileIndex = ProjectRootManager.getInstance(project).fileIndex
            val fileNameMatcher = IntellijFuzzyMatcher(fileNamePrefix)
            val pathMatcher = normalizedPrefix.takeIf { it.contains('/') }?.let(::IntellijFuzzyMatcher)
            val matchedNames = BoundedSnapshotCollector(MAX_MATCHED_NAMES, FILE_NAME_MATCH_ORDER)
            FilenameIndex.processAllFileNames(
                Processor { fileName ->
                    ProgressManager.checkCanceled()
                    val score = fileNameMatcher.score(fileName) ?: return@Processor true
                    matchedNames.add(FileNameMatch(fileName, score))
                    true
                },
                scope,
                null,
            )

            val retained = BoundedSnapshotCollector(MAX_RETAINED_ENTRIES, PATH_MATCH_ORDER)
            for (nameMatch in matchedNames.toSortedList()) {
                ProgressManager.checkCanceled()
                FilenameIndex.processFilesByName(
                    nameMatch.fileName,
                    true,
                    scope,
                    Processor { file ->
                        if (!fileIndex.isInContent(file) || fileIndex.isExcluded(file)) return@Processor true
                        val filePath = runCatching(file::toNioPath).getOrNull() ?: return@Processor true
                        val path = relativePath(workingDirectory, filePath) ?: return@Processor true
                        val score =
                            if (pathMatcher == null) {
                                nameMatch.score
                            } else {
                                pathMatcher.score(path) ?: return@Processor true
                            }
                        retained.add(PathMatch(TerminalFuzzyPathEntry(path, file.isDirectory), score))
                        true
                    },
                )
            }
            retained.toSortedList().map(PathMatch::entry)
        }
    }

    private fun activeFileNamePrefix(prefix: String): String = prefix.substring(prefix.lastIndexOf('/') + 1)

    private fun relativePath(
        workingDirectory: Path,
        file: Path,
    ): String? = toRelativeCompletionPath(workingDirectory, file).takeIf(String::isNotEmpty)

    private companion object {
        private const val MAX_MATCHED_NAMES = 1_024
        private const val MAX_RETAINED_ENTRIES = 4_096
        private val FILE_NAME_MATCH_ORDER =
            compareByDescending<FileNameMatch> { it.score }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.fileName }
                .thenBy(FileNameMatch::fileName)
        private val PATH_MATCH_ORDER =
            compareByDescending<PathMatch> { it.score }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.entry.path }
                .thenBy { it.entry.path }
                .thenBy { it.entry.isDirectory }
    }

    private data class FileNameMatch(
        val fileName: String,
        val score: Int,
    )

    private data class PathMatch(
        val entry: TerminalFuzzyPathEntry,
        val score: Int,
    )
}

/** IntelliJ-native fuzzy matcher retained once per asynchronous query. */
internal class IntellijFuzzyMatcher(prefix: String) {
    init {
        require(prefix.isNotEmpty()) { "prefix must not be empty" }
    }

    private val matcher = NameUtil.buildMatcher("*$prefix", MatchingMode.IGNORE_CASE)

    /** Returns IntelliJ's match degree for [fileName], or `null` when it does not match. */
    fun score(fileName: String): Int? {
        val fragments = matcher.match(fileName) ?: return null
        return matcher.matchingDegree(fileName, false, fragments)
    }
}

private data class ProjectPathQuery(
    val workingDirectoryUri: String?,
    val prefix: String,
)

/** Adds query-aware IntelliJ project fuzzy paths without leaking VFS APIs into the shared engine. */
internal class IntellijProjectFileProviderFactory(
    private val loader: (String?, String) -> List<TerminalFuzzyPathEntry>,
) : IntellijCompletionProviderFactory {
    override fun create(context: IntellijCompletionProviderContext): IntellijCompletionProviderRegistration {
        val snapshotProvider =
            context.snapshotService.createValueProvider(
                loader = { query: ProjectPathQuery -> loader(query.workingDirectoryUri, query.prefix) },
                onSnapshotChanged = context.onSnapshotChanged,
            )
        val source =
            TerminalCompletionSources.fuzzyPath(
                sourceId = SOURCE_ID,
                entriesProvider =
                    TerminalFuzzyPathProvider { prefix ->
                        snapshotProvider.values(ProjectPathQuery(context.workingDirectoryUriProvider(), prefix))
                    },
                commandSpecs = context.commandSpecs,
            )
        return IntellijCompletionProviderRegistration(
            sourceEntry = TerminalCompletionSourceEntry(source, TerminalCompletionSourcePrior.PROJECT_FUZZY_PATH),
            resources = listOf(snapshotProvider),
        )
    }

    private companion object {
        private const val SOURCE_ID = "intellij-project-file"
    }
}

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

import com.intellij.ide.util.gotoByName.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFileSystemItem
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.Processor
import com.intellij.util.indexing.FindSymbolParameters
import io.github.ketraterm.completion.api.*
import io.github.ketraterm.completion.host.TerminalLocalFileUriResolver
import java.nio.file.Path

/**
 * Queries IntelliJ's Go to File engine for a bounded fuzzy path snapshot.
 *
 * IntelliJ owns project discovery, fuzzy matching, path qualification, and result
 * ordering. This adapter only converts PSI items into shell-facing paths relative
 * to the terminal working directory. Shared completion still owns hidden-file
 * policy, replacement ranges, and shell quoting.
 *
 * @property project project whose Go to File provider is queried.
 * @property filePathResolver converts native VFS files into local paths.
 */
internal class IntellijProjectFileLoader(
    private val project: Project,
    private val filePathResolver: (VirtualFile) -> Path? = { file -> runCatching(file::toNioPath).getOrNull() },
) {
    /**
     * Returns IntelliJ-ranked project paths matching [prefix].
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
        if (normalizedPrefix.substringAfterLast('/').isEmpty()) return emptyList()
        val workingDirectory =
            TerminalLocalFileUriResolver.resolve(workingDirectoryUri)
                ?: project.basePath?.let { runCatching { Path.of(it) }.getOrNull() }
                ?: return emptyList()
        return ApplicationManager.getApplication().runReadAction<List<TerminalFuzzyPathEntry>> {
            if (project.isDisposed) return@runReadAction emptyList()
            val model = GotoFileModel(project)
            try {
                val viewModel = IntellijProjectFileSearchViewModel(project, model)
                val results = ArrayList<TerminalFuzzyPathEntry>(INITIAL_RESULT_CAPACITY)
                val paths = HashSet<String>(INITIAL_RESULT_CAPACITY)
                val indicator = ProgressManager.getInstance().progressIndicator ?: EmptyProgressIndicator()
                val itemProvider = model.getItemProvider(null) as ChooseByNameInScopeItemProvider
                val parameters = FindSymbolParameters.wrap(normalizedPrefix, GlobalSearchScope.projectScope(project))
                itemProvider.filterElementsWithWeights(
                    viewModel,
                    parameters,
                    indicator,
                    Processor { descriptor ->
                        ProgressManager.checkCanceled()
                        val file = (descriptor.item as? PsiFileSystemItem)?.virtualFile ?: return@Processor true
                        val filePath = filePathResolver(file) ?: return@Processor true
                        val path = relativePath(workingDirectory, filePath) ?: return@Processor true
                        if (paths.add(path)) {
                            results.add(TerminalFuzzyPathEntry(path, file.isDirectory))
                        }
                        results.size < MAX_RETAINED_ENTRIES
                    },
                )
                results
            } finally {
                Disposer.dispose(model)
            }
        }
    }

    private fun relativePath(
        workingDirectory: Path,
        file: Path,
    ): String? = toRelativeCompletionPath(workingDirectory, file).takeIf(String::isNotEmpty)

    private companion object {
        private const val INITIAL_RESULT_CAPACITY = 64
        private const val MAX_RETAINED_ENTRIES = 4_096
    }
}

/** Minimal public-API equivalent of Search Everywhere's package-private view-model adapter. */
private class IntellijProjectFileSearchViewModel(
    private val project: Project,
    private val model: ChooseByNameModel,
) : ChooseByNameViewModel {
    override fun getProject(): Project = project

    override fun getModel(): ChooseByNameModel = model

    override fun isSearchInAnyPlace(): Boolean = model.useMiddleMatching()

    override fun transformPattern(pattern: String): String = ChooseByNamePopup.getTransformedPattern(pattern, model)

    override fun canShowListForEmptyPattern(): Boolean = false

    override fun getMaximumListSizeLimit(): Int = 0
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

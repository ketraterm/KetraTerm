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
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import io.github.ketraterm.completion.api.TerminalCompletionSourceEntry
import io.github.ketraterm.completion.api.TerminalCompletionSources
import io.github.ketraterm.completion.api.TerminalFuzzyPathEntry
import io.github.ketraterm.completion.host.TerminalLocalFileUriResolver
import java.nio.file.Path
import java.util.*

/**
 * Loads a bounded project-content snapshot for fuzzy path completion.
 *
 * Project content is traversed under an IntelliJ read action. The result is a
 * shell-facing path snapshot relative to the terminal working directory, so
 * the shared completion engine can apply path context and shell quoting without
 * depending on IntelliJ APIs.
 *
 * @property project project whose content index is traversed.
 */
internal class IntellijProjectFileLoader(
    private val project: Project,
) {
    /**
     * Returns a bounded deterministic project-content snapshot for one local directory.
     *
     * @param workingDirectoryUri local `file` URI used to relativize project entries.
     * @return ready project paths, or an empty list for disposed projects and
     * unsupported working-directory URIs.
     */
    fun load(workingDirectoryUri: String?): List<TerminalFuzzyPathEntry> {
        if (project.isDisposed) return emptyList()
        val workingDirectory = TerminalLocalFileUriResolver.resolve(workingDirectoryUri) ?: return emptyList()
        return ApplicationManager.getApplication().runReadAction<List<TerminalFuzzyPathEntry>> {
            if (project.isDisposed) return@runReadAction emptyList()
            val fileIndex = ProjectRootManager.getInstance(project).fileIndex
            val retained = PriorityQueue(MAX_RETAINED_ENTRIES, ENTRY_ORDER.reversed())
            var visited = 0
            fileIndex.iterateContent { file ->
                if (visited++ >= MAX_VISITED_ENTRIES) return@iterateContent false
                if (!fileIndex.isInContent(file) || fileIndex.isExcluded(file)) return@iterateContent true
                val filePath = runCatching(file::toNioPath).getOrNull() ?: return@iterateContent true
                val path = relativePath(workingDirectory, filePath) ?: return@iterateContent true
                val candidate = TerminalFuzzyPathEntry(path, file.isDirectory)
                if (retained.size < MAX_RETAINED_ENTRIES) {
                    retained += candidate
                } else if (ENTRY_ORDER.compare(candidate, retained.peek()) < 0) {
                    retained.remove()
                    retained += candidate
                }
                true
            }
            ArrayList(retained).apply { sortWith(ENTRY_ORDER) }
        }
    }

    private fun relativePath(
        workingDirectory: Path,
        file: Path,
    ): String? {
        val relative = runCatching { workingDirectory.relativize(file) }.getOrElse { file }
        val value = relative.toString().replace('\\', '/').removeSuffix("/")
        return value.takeIf(String::isNotEmpty)
    }

    private companion object {
        private const val MAX_VISITED_ENTRIES = 8_192
        private const val MAX_RETAINED_ENTRIES = 4_096
        private val ENTRY_ORDER =
            compareBy<TerminalFuzzyPathEntry, String>(String.CASE_INSENSITIVE_ORDER) { it.path }
                .thenBy { it.path }
                .thenBy(TerminalFuzzyPathEntry::isDirectory)
    }
}

/** Adds bounded IntelliJ project-content fuzzy paths without leaking VFS APIs into the shared engine. */
internal class IntellijProjectFileProviderFactory(
    private val loader: (String?) -> List<TerminalFuzzyPathEntry>,
) : IntellijCompletionProviderFactory {
    override fun create(context: IntellijCompletionProviderContext): IntellijCompletionProviderRegistration {
        val snapshotProvider =
            context.snapshotService.createValueProvider(
                keyProvider = context.workingDirectoryUriProvider,
                loader = loader,
                onSnapshotChanged = context.onSnapshotChanged,
            )
        val source =
            TerminalCompletionSources.fuzzyPath(
                sourceId = SOURCE_ID,
                entriesProvider = snapshotProvider::values,
                commandSpecs = context.commandSpecs,
            )
        return IntellijCompletionProviderRegistration(
            sourceEntry = TerminalCompletionSourceEntry(source = source, priority = PRIORITY),
            resources = listOf(snapshotProvider),
        )
    }

    private companion object {
        private const val PRIORITY = 120
        private const val SOURCE_ID = "intellij-project-file"
    }
}

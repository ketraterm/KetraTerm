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

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.ChangeListManager
import io.github.ketraterm.completion.api.TerminalCompletionSources
import io.github.ketraterm.completion.api.TerminalFuzzyPathEntry
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.nio.file.Path

/**
 * Loads changed and unversioned paths tracked by IntelliJ for the Git repository containing a terminal directory.
 *
 * The IDE change-list model supplies the paths; no Git command is started. Collection runs under one read action
 * on a completion worker and returns only paths belonging to the selected, deepest containing repository.
 *
 * @property project IntelliJ project that owns the change-list and Git repository models.
 */
internal class IntellijGitStatusPathLoader(
    private val project: Project,
) {
    /**
     * Loads bounded, deterministic changed paths for one terminal directory.
     *
     * Renamed paths use their post-rename path when available; deleted paths use their prior path.
     *
     * @param workingDirectoryUri local `file` URI used to select and relativize a repository.
     * @return at most 2,048 changed paths, or an empty list for unusable project, URI, or repository state.
     */
    suspend fun load(workingDirectoryUri: String?): List<TerminalFuzzyPathEntry> {
        val cancellationContext = currentCoroutineContext()
        cancellationContext.ensureActive()
        return readIntellijGitRepository(project, workingDirectoryUri) { repository, workingDirectory ->
            val repositoryRoot = repository.root.toNioPath()
            val retained = BoundedSnapshotCollector(MAX_RETAINED_PATHS, ENTRY_ORDER)
            val visitBudget =
                BoundedVisitBudget(MAX_VISITED_CHANGES) {
                    cancellationContext.ensureActive()
                    ProgressManager.checkCanceled()
                }
            val changeListManager = ChangeListManager.getInstance(project)

            fun retain(
                path: Path,
                isDirectory: Boolean,
                detail: String,
            ) {
                if (!path.startsWith(repositoryRoot)) return
                val relativePath = relativePath(workingDirectory, path) ?: return
                val entry = TerminalFuzzyPathEntry(relativePath, isDirectory = isDirectory, detail = detail)
                retained.add(entry)
            }
            visitBudget.visit(changeListManager.allChanges) { change ->
                val filePath = change.afterRevision?.file ?: change.beforeRevision?.file ?: return@visit
                val path = runCatching { Path.of(filePath.path) }.getOrNull() ?: return@visit
                retain(path, isDirectory = false, detail = "changed file")
            }
            visitBudget.visit(changeListManager.unversionedFilesPaths) { filePath ->
                val path = runCatching { Path.of(filePath.path) }.getOrNull() ?: return@visit
                retain(path, isDirectory = false, detail = "untracked file")
            }
            retained.toSortedList()
        } ?: emptyList()
    }

    private fun relativePath(
        workingDirectory: Path,
        file: Path,
    ): String? = toRelativeCompletionPath(workingDirectory, file).takeIf(String::isNotEmpty)

    private companion object {
        private const val MAX_VISITED_CHANGES = 8_192
        private const val MAX_RETAINED_PATHS = 2_048
        private val ENTRY_ORDER =
            compareBy<TerminalFuzzyPathEntry, String>(String.CASE_INSENSITIVE_ORDER) { it.path }
                .thenBy { it.path }
    }
}

/** Creates changed-Git-path completion without exposing IntelliJ VCS APIs to the shared engine. */
internal fun intellijGitStatusPathCompletionSource(loader: suspend (String?) -> List<TerminalFuzzyPathEntry>) =
    TerminalCompletionSources.fuzzyPath(
        sourceId = "intellij-git-status-path",
        entriesProvider = { request -> loader(request.workingDirectoryUri) },
        requiresNonEmptyPrefix = false,
        allowedCommandNames = setOf("add", "restore", "rm", "diff"),
    )

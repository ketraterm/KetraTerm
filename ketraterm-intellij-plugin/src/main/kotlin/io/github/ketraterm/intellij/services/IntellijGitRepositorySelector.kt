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

import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.Project
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager
import io.github.ketraterm.completion.host.TerminalLocalFileUriResolver
import java.nio.file.Path

/** Selects the deepest Git repository containing a terminal working directory. */
internal fun selectIntellijGitRepository(
    repositories: List<GitRepository>,
    workingDirectory: Path,
): GitRepository? =
    repositories
        .asSequence()
        .filter { repository -> workingDirectory.startsWith(repository.root.toNioPath()) }
        .maxByOrNull { repository -> repository.root.path.length }

/**
 * Resolves one local terminal directory and reads its deepest containing Git repository.
 *
 * URI validation, project disposal, read-action ownership, and nested-repository
 * selection are centralized here so every Git completion loader follows the same policy.
 */
internal suspend fun <T> loadIntellijGitRepositorySnapshot(
    project: Project,
    workingDirectoryUri: String?,
    loader: (repository: GitRepository, workingDirectory: Path) -> List<T>,
): List<T> {
    if (project.isDisposed) return emptyList()
    val workingDirectory =
        TerminalLocalFileUriResolver.resolve(workingDirectoryUri)
            ?: project.basePath?.let { runCatching { Path.of(it) }.getOrNull() }
            ?: return emptyList()
    return readAction {
        if (project.isDisposed) return@readAction emptyList()
        val repository =
            selectIntellijGitRepository(GitRepositoryManager.getInstance(project).repositories, workingDirectory)
                ?: return@readAction emptyList()
        loader(repository, workingDirectory)
    }
}

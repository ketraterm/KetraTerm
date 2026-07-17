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
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager
import io.github.ketraterm.completion.host.TerminalLocalFileUriResolver
import io.github.ketraterm.completion.host.TerminalValueSnapshotProvider
import io.github.ketraterm.completion.model.TerminalCompletionDomainValue
import java.nio.file.Path

/**
 * Reads local Git branches from the repository containing a terminal directory.
 *
 * Repository state is accessed under an IntelliJ read action. Invalid,
 * non-local, remote-authority, disposed-project, and non-repository inputs
 * produce an empty snapshot rather than falling back to another repository.
 *
 * @property project IntelliJ project whose Git repository model is queried.
 */
internal class IntellijGitBranchLoader(
    private val project: Project,
) {
    /**
     * Loads a bounded, deterministically ordered local-branch snapshot.
     *
     * The current branch is excluded because inserting it would not change the
     * command. When repositories are nested, the deepest containing repository
     * wins.
     *
     * @param workingDirectoryUri local `file` URI used to select a repository.
     * @return at most 2,048 immutable completion values, or an empty list when
     * the directory cannot be mapped to a usable project repository.
     */
    fun load(workingDirectoryUri: String?): List<TerminalCompletionDomainValue> {
        if (project.isDisposed) return emptyList()
        val workingDirectory = TerminalLocalFileUriResolver.resolve(workingDirectoryUri) ?: return emptyList()
        return ApplicationManager.getApplication().runReadAction<List<TerminalCompletionDomainValue>> {
            if (project.isDisposed) return@runReadAction emptyList()
            val repository = selectRepository(GitRepositoryManager.getInstance(project).repositories, workingDirectory)
                ?: return@runReadAction emptyList()
            val currentBranchName = repository.currentBranch?.name
            repository.branches.localBranches
                .asSequence()
                .filterNot { branch -> branch.name == currentBranchName }
                .map { branch ->
                    TerminalCompletionDomainValue(
                        value = branch.name,
                        detail = "local branch",
                    )
                }
                .sortedWith(BRANCH_ORDER)
                .take(MAX_BRANCHES)
                .toList()
        }
    }

    private companion object {
        private const val MAX_BRANCHES = 2_048
        private val BRANCH_ORDER =
            compareBy<TerminalCompletionDomainValue, String>(String.CASE_INSENSITIVE_ORDER) { it.value }
                .thenBy { it.value }

        private fun selectRepository(
            repositories: List<GitRepository>,
            workingDirectory: Path,
        ): GitRepository? =
            repositories
                .asSequence()
                .filter { repository -> workingDirectory.startsWith(repository.root.toNioPath()) }
                .maxByOrNull { repository -> repository.root.path.length }
    }
}

/** IntelliJ-local name for the shared generation-safe Git value snapshot. */
internal typealias IntellijGitBranchCompletionProvider =
        TerminalValueSnapshotProvider<String?, TerminalCompletionDomainValue>

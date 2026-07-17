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

import com.intellij.openapi.project.Project
import io.github.ketraterm.completion.api.TerminalCompletionSources
import io.github.ketraterm.completion.model.TerminalCompletionDomainValue
import io.github.ketraterm.completion.model.TerminalCompletionValueDomain

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
    fun load(workingDirectoryUri: String?): List<TerminalCompletionDomainValue> =
        loadIntellijGitRepositorySnapshot(project, workingDirectoryUri) { repository, _ ->
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

    private companion object {
        private const val MAX_BRANCHES = 2_048
        private val BRANCH_ORDER =
            compareBy<TerminalCompletionDomainValue, String>(String.CASE_INSENSITIVE_ORDER) { it.value }
                .thenBy { it.value }
    }
}

/** Adds local Git branch values without coupling the registry to Git4Idea. */
internal class IntellijGitBranchProviderFactory(
    private val loader: (String?) -> List<TerminalCompletionDomainValue>,
) : IntellijCompletionProviderFactory {
    override fun create(context: IntellijCompletionProviderContext): IntellijCompletionProviderRegistration =
        context.createSnapshotRegistration(PRIORITY, loader) { valuesProvider ->
            TerminalCompletionSources.valueDomain(
                domain = TerminalCompletionValueDomain.GIT_BRANCH,
                sourceId = SOURCE_ID,
                valuesProvider = valuesProvider,
                commandSpecs = context.commandSpecs,
            )
        }

    private companion object {
        private const val PRIORITY = 150
        private const val SOURCE_ID = "intellij-git-branch"
    }
}

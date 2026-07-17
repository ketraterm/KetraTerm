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
import git4idea.repo.GitRepositoryManager
import io.github.ketraterm.completion.api.TerminalCompletionSourceEntry
import io.github.ketraterm.completion.api.TerminalCompletionSources
import io.github.ketraterm.completion.host.TerminalLocalFileUriResolver
import io.github.ketraterm.completion.model.TerminalCompletionDomainValue
import io.github.ketraterm.completion.model.TerminalCompletionValueDomain

/** Reads remote Git branches from the repository containing a terminal directory. */
internal class IntellijGitRemoteBranchLoader(
    private val project: Project,
) {
    /**
     * Loads a bounded deterministic remote-branch snapshot.
     *
     * @param workingDirectoryUri local `file` URI used to select a repository.
     * @return at most 2,048 remote branch values, or an empty list for unusable project, URI, or repository state.
     */
    fun load(workingDirectoryUri: String?): List<TerminalCompletionDomainValue> {
        if (project.isDisposed) return emptyList()
        val workingDirectory = TerminalLocalFileUriResolver.resolve(workingDirectoryUri) ?: return emptyList()
        return ApplicationManager.getApplication().runReadAction<List<TerminalCompletionDomainValue>> {
            if (project.isDisposed) return@runReadAction emptyList()
            val repository =
                selectIntellijGitRepository(GitRepositoryManager.getInstance(project).repositories, workingDirectory)
                    ?: return@runReadAction emptyList()
            repository.branches.remoteBranches
                .asSequence()
                .map { branch -> TerminalCompletionDomainValue(branch.name, detail = "remote branch") }
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
    }
}

/** Adds remote branch values only where Git accepts a remote revision directly. */
internal class IntellijGitRemoteBranchProviderFactory(
    private val loader: (String?) -> List<TerminalCompletionDomainValue>,
) : IntellijCompletionProviderFactory {
    override fun create(context: IntellijCompletionProviderContext): IntellijCompletionProviderRegistration {
        val snapshotProvider =
            context.snapshotService.createValueProvider(
                keyProvider = context.workingDirectoryUriProvider,
                loader = loader,
                onSnapshotChanged = context.onSnapshotChanged,
            )
        val source =
            TerminalCompletionSources.valueDomain(
                domain = TerminalCompletionValueDomain.GIT_BRANCH,
                sourceId = SOURCE_ID,
                valuesProvider = snapshotProvider::values,
                allowedCommandNames = ALLOWED_COMMAND_NAMES,
                commandSpecs = context.commandSpecs,
            )
        return IntellijCompletionProviderRegistration(
            sourceEntry = TerminalCompletionSourceEntry(source = source, priority = PRIORITY),
            resources = listOf(snapshotProvider),
        )
    }

    private companion object {
        private const val PRIORITY = 145
        private const val SOURCE_ID = "intellij-git-remote-branch"
        private val ALLOWED_COMMAND_NAMES = setOf("checkout", "merge", "rebase")
    }
}

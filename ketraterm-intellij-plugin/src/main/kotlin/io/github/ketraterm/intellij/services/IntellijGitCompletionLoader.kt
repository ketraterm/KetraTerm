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
import io.github.ketraterm.completion.model.TerminalCompletionDomainValue
import io.github.ketraterm.completion.model.TerminalGitCompletionSnapshot
import io.github.ketraterm.intellij.KetraTermBundle
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.CoroutineContext

/** Lazy Git-reference view whose iterables are consumed inside the owning IDE read action. */
internal data class GitReferenceReadModel(
    val currentBranchName: String?,
    val localBranchNames: Iterable<String>,
    val remoteBranchNames: Iterable<String>,
    val tagNames: Iterable<String>,
    val remoteNames: Iterable<String> = emptyList(),
)

/** Executes bounded Git-reference collection inside an implementation-owned read action. */
internal fun interface GitReferenceReadPort {
    suspend fun read(
        workingDirectoryUri: String?,
        collector: (GitReferenceReadModel) -> TerminalGitCompletionSnapshot,
    ): TerminalGitCompletionSnapshot?
}

private class IntellijGitReferenceReadPort(
    private val project: Project,
) : GitReferenceReadPort {
    override suspend fun read(
        workingDirectoryUri: String?,
        collector: (GitReferenceReadModel) -> TerminalGitCompletionSnapshot,
    ): TerminalGitCompletionSnapshot? =
        readIntellijGitRepository(project, workingDirectoryUri) { repository, _ ->
            collector(
                GitReferenceReadModel(
                    currentBranchName = repository.currentBranch?.name,
                    remoteNames =
                        repository.remotes
                            .asSequence()
                            .map { it.name }
                            .asIterable(),
                    localBranchNames =
                        repository.branches.localBranches
                            .asSequence()
                            .map { it.name }
                            .asIterable(),
                    remoteBranchNames =
                        repository.branches.remoteBranches
                            .asSequence()
                            .map { it.name }
                            .asIterable(),
                    tagNames =
                        repository.tagsHolder.state.value.tagsToCommitHashes.keys
                            .asSequence()
                            .map { it.name }
                            .asIterable(),
                ),
            )
        }
}

/** Reads all Git-reference candidate groups from one selected repository read action. */
internal class IntellijGitCompletionLoader(
    private val readPort: GitReferenceReadPort,
) {
    constructor(project: Project) : this(IntellijGitReferenceReadPort(project))

    suspend fun load(workingDirectoryUri: String?): TerminalGitCompletionSnapshot {
        val cancellationContext = currentCoroutineContext()
        cancellationContext.ensureActive()
        val localBranchDetail = KetraTermBundle.message("completion.detail.localBranch")
        val remoteBranchDetail = KetraTermBundle.message("completion.detail.remoteBranch")
        val tagDetail = KetraTermBundle.message("completion.detail.tag")
        val remoteDetail = KetraTermBundle.message("completion.detail.remote")
        return readPort.read(workingDirectoryUri) { model ->
            TerminalGitCompletionSnapshot(
                currentBranchName = model.currentBranchName,
                remotes =
                    collectValues(
                        model.remoteNames,
                        cancellationContext,
                    ) { TerminalCompletionDomainValue(it, detail = remoteDetail) },
                localBranches =
                    collectValues(model.localBranchNames, cancellationContext) { branchName ->
                        TerminalCompletionDomainValue(
                            value = branchName,
                            detail = localBranchDetail,
                            scoreAdjustment = LOCAL_SCORE_ADJUSTMENT,
                        )
                    },
                remoteBranches =
                    collectValues(model.remoteBranchNames, cancellationContext) { branchName ->
                        TerminalCompletionDomainValue(
                            value = branchName,
                            detail = remoteBranchDetail,
                            scoreAdjustment = REMOTE_SCORE_ADJUSTMENT,
                        )
                    },
                tags =
                    collectValues(model.tagNames, cancellationContext) { tagName ->
                        TerminalCompletionDomainValue(tagName, detail = tagDetail)
                    },
            )
        } ?: TerminalGitCompletionSnapshot()
    }

    private fun collectValues(
        values: Iterable<String>,
        cancellationContext: CoroutineContext,
        toValue: (String) -> TerminalCompletionDomainValue?,
    ): List<TerminalCompletionDomainValue> {
        val retained = ArrayList<TerminalCompletionDomainValue>(INITIAL_VALUE_CAPACITY)
        val visitBudget =
            BoundedVisitBudget(MAX_VISITED_VALUES_PER_GROUP) {
                cancellationContext.ensureActive()
                ProgressManager.checkCanceled()
            }
        visitBudget.visit(values) { value ->
            toValue(value)?.let(retained::add)
            true
        }
        retained.sortWith(VALUE_ORDER)
        return retained
    }

    private companion object {
        private const val MAX_VISITED_VALUES_PER_GROUP = 8_192
        private const val INITIAL_VALUE_CAPACITY = 64
        private const val LOCAL_SCORE_ADJUSTMENT = 2
        private const val REMOTE_SCORE_ADJUSTMENT = 1
        private val VALUE_ORDER =
            compareBy<TerminalCompletionDomainValue, String>(String.CASE_INSENSITIVE_ORDER) { it.value }
                .thenBy { it.value }
    }
}

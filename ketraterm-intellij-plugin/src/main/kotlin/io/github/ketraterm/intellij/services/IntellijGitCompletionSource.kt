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
import io.github.ketraterm.completion.api.*
import io.github.ketraterm.completion.model.TerminalCompletionDomainValue
import io.github.ketraterm.completion.model.TerminalCompletionValueDomain

/** Immutable Git references captured from one IntelliJ repository read action. */
internal data class IntellijGitCompletionSnapshot(
    val localBranches: List<TerminalCompletionDomainValue>,
    val remoteBranches: List<TerminalCompletionDomainValue>,
    val tags: List<TerminalCompletionDomainValue>,
) {
    companion object {
        val EMPTY = IntellijGitCompletionSnapshot(emptyList(), emptyList(), emptyList())
    }
}

/** Reads all Git-reference candidate groups from one selected repository read action. */
internal class IntellijGitCompletionLoader(
    private val project: Project,
) {
    suspend fun load(workingDirectoryUri: String?): IntellijGitCompletionSnapshot =
        readIntellijGitRepository(project, workingDirectoryUri) { repository, _ ->
            val currentBranchName = repository.currentBranch?.name
            IntellijGitCompletionSnapshot(
                localBranches =
                    repository.branches.localBranches
                        .asSequence()
                        .filterNot { branch -> branch.name == currentBranchName }
                        .map { branch ->
                            TerminalCompletionDomainValue(
                                value = branch.name,
                                detail = "local branch",
                                scoreAdjustment = LOCAL_SCORE_ADJUSTMENT,
                            )
                        }.sortedWith(VALUE_ORDER)
                        .take(MAX_VALUES_PER_GROUP)
                        .toList(),
                remoteBranches =
                    repository.branches.remoteBranches
                        .asSequence()
                        .map { branch ->
                            TerminalCompletionDomainValue(
                                value = branch.name,
                                detail = "remote branch",
                                scoreAdjustment = REMOTE_SCORE_ADJUSTMENT,
                            )
                        }.sortedWith(VALUE_ORDER)
                        .take(MAX_VALUES_PER_GROUP)
                        .toList(),
                tags =
                    repository.tagsHolder.state.value.tagsToCommitHashes.keys
                        .asSequence()
                        .map { tag -> TerminalCompletionDomainValue(tag.name, detail = "tag") }
                        .sortedWith(VALUE_ORDER)
                        .take(MAX_VALUES_PER_GROUP)
                        .toList(),
            )
        } ?: IntellijGitCompletionSnapshot.EMPTY

    private companion object {
        private const val MAX_VALUES_PER_GROUP = 2_048
        private const val LOCAL_SCORE_ADJUSTMENT = 2
        private const val REMOTE_SCORE_ADJUSTMENT = 1
        private val VALUE_ORDER =
            compareBy<TerminalCompletionDomainValue, String>(String.CASE_INSENSITIVE_ORDER) { it.value }
                .thenBy { it.value }
    }
}

/** Creates one completion source backed by one composite Git-reference load per request. */
internal fun intellijGitCompletionSource(
    loader: suspend (String?) -> IntellijGitCompletionSnapshot,
): TerminalCompletionSource =
    TerminalCompletionSource { request, context, limit ->
        if (context.expectedValueDomain != TerminalCompletionValueDomain.GIT_BRANCH) {
            return@TerminalCompletionSource emptyList()
        }
        val commandName = context.currentCommand?.name
        val snapshot = loader(request.workingDirectoryUri)
        val candidates = ArrayList<TerminalCompletionCandidate>(limit)
        candidates += projectValues(request, context, LOCAL_SOURCE_ID, snapshot.localBranches, limit)
        if (commandName in REMOTE_REFERENCE_COMMANDS) {
            candidates += projectValues(request, context, REMOTE_SOURCE_ID, snapshot.remoteBranches, limit)
            candidates += projectValues(request, context, TAG_SOURCE_ID, snapshot.tags, limit)
        }
        candidates
            .sortedWith(CANDIDATE_ORDER)
            .take(limit)
    }

private fun projectValues(
    request: TerminalCompletionRequest,
    context: TerminalCompletionContext,
    sourceId: String,
    values: List<TerminalCompletionDomainValue>,
    limit: Int,
): List<TerminalCompletionCandidate> =
    TerminalCompletionSources.valueDomainCandidates(
        request = request,
        context = context,
        domain = TerminalCompletionValueDomain.GIT_BRANCH,
        sourceId = sourceId,
        values = values,
        limit = limit,
    )

private const val LOCAL_SOURCE_ID = "intellij-git-branch"
private const val REMOTE_SOURCE_ID = "intellij-git-remote-branch"
private const val TAG_SOURCE_ID = "intellij-git-tag"
private val REMOTE_REFERENCE_COMMANDS = setOf("checkout", "merge", "rebase")
private val CANDIDATE_ORDER =
    compareByDescending<TerminalCompletionCandidate> { it.score }
        .thenBy { it.displayText }
        .thenBy { it.replacementText }

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
package io.github.ketraterm.completion.source

import io.github.ketraterm.completion.api.TerminalCompletionCandidate
import io.github.ketraterm.completion.api.TerminalCompletionContext
import io.github.ketraterm.completion.api.TerminalCompletionRequest
import io.github.ketraterm.completion.api.TerminalCompletionSource
import io.github.ketraterm.completion.commandline.GitCompletionContext
import io.github.ketraterm.completion.commandline.GitCompletionTarget
import io.github.ketraterm.completion.model.TerminalCompletionDomainValue
import io.github.ketraterm.completion.model.TerminalGitCompletionSnapshot

/** Shared Git selection and editing policy; hosts supply data, never command-specific candidates. */
internal class GitCompletionSource(
    private val sourceId: String,
    private val loader: suspend (TerminalCompletionRequest, TerminalCompletionContext) -> TerminalGitCompletionSnapshot,
) : TerminalCompletionSource {
    override suspend fun complete(
        request: TerminalCompletionRequest,
        context: TerminalCompletionContext,
        limit: Int,
    ): List<TerminalCompletionCandidate> {
        require(limit > 0)
        val git = context.gitCompletion ?: return emptyList()
        if (!context.isCompletionAllowed) return emptyList()
        val snapshot = loader(request, context)
        if (git.target == GitCompletionTarget.REMOTES) {
            return projectValueDomainCandidates(
                request,
                context,
                context.expectedValueDomain,
                sourceId,
                snapshot.remotes.filter { it.value !in git.excludedRemotes },
                limit,
            )
        }
        val token =
            context.commandLineContext.tokens
                .getOrNull(context.commandLineContext.activeTokenIndex)
                ?.text
                .orEmpty()
        val end = git.refspecEnd ?: token.length
        val prefix = context.activePrefix.substring(git.refspecStart.coerceAtMost(context.activePrefix.length))
        val values = referenceValues(snapshot, git, prefix)
        val candidates =
            projectValueDomainCandidates(
                request,
                context,
                context.expectedValueDomain,
                sourceId,
                values,
                limit,
                prefix,
                omitExactMatch = prefix == token.substring(git.refspecStart, end),
            )
        if (git.refspecEnd == null) return candidates
        val before = token.substring(0, git.refspecStart)
        val after = token.substring(end)
        val rawToken = request.commandLine.substring(context.replacementStartOffset, context.replacementEndOffset)
        // Ordinary refspecs need only a component edit. Quoted/escaped tokens use the
        // existing shell encoder to preserve the other component's literal meaning.
        return candidates.mapNotNull { candidate ->
            if (rawToken == token) {
                candidate.copy(
                    replacementStartOffset = context.replacementStartOffset + git.refspecStart,
                    replacementEndOffset = context.replacementStartOffset + end,
                )
            } else {
                val replacement =
                    ShellReplacementText.encode(
                        before + candidate.displayText + after,
                        context.activeTokenQuote,
                        request.shellCapabilities.quoting,
                    ) ?: return@mapNotNull null
                candidate.copy(replacementText = replacement)
            }
        }
    }

    private fun referenceValues(
        snapshot: TerminalGitCompletionSnapshot,
        git: GitCompletionContext,
        prefix: String,
    ): List<TerminalCompletionDomainValue> =
        buildList {
            val qualified = prefix.startsWith("refs/")

            fun addReference(
                value: TerminalCompletionDomainValue,
                namespace: String,
                name: String = value.value,
            ) {
                val text = if (qualified) namespace + name else name
                add(value.copy(value = text, displayText = text))
            }
            when (git.target) {
                GitCompletionTarget.REMOTE_REFERENCES -> {
                    val remotePrefix = git.remote?.let { "$it/" } ?: return@buildList
                    for (branch in snapshot.remoteBranches) {
                        if (branch.value.startsWith(remotePrefix)) {
                            addReference(branch, "refs/heads/", branch.value.removePrefix(remotePrefix))
                        }
                    }
                }
                GitCompletionTarget.LOCAL_BRANCHES,
                GitCompletionTarget.LOCAL_REFERENCES,
                GitCompletionTarget.ALL_REFERENCES,
                -> {
                    for (branch in snapshot.localBranches) {
                        if (git.includeCurrentBranch || branch.value != snapshot.currentBranchName) addReference(branch, "refs/heads/")
                    }
                    if (git.target != GitCompletionTarget.LOCAL_BRANCHES) {
                        for (tag in snapshot.tags) addReference(tag, "refs/tags/")
                    }
                    if (git.target == GitCompletionTarget.ALL_REFERENCES ||
                        (git.target == GitCompletionTarget.LOCAL_REFERENCES && prefix.startsWith("refs/remotes/"))
                    ) {
                        for (branch in snapshot.remoteBranches) addReference(branch, "refs/remotes/")
                    }
                }
                GitCompletionTarget.NONE, GitCompletionTarget.REMOTES -> Unit
            }
        }
}

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
package io.github.ketraterm.completion.model

import io.github.ketraterm.completion.api.ExperimentalTerminalCompletionApi

/**
 * Bounded Git data loaded by a host for one completion request. Lists must not be
 * mutated during evaluation. Names are literal, unescaped shell values.
 *
 * @property localBranches local branch names without `refs/heads/`, including the current branch.
 * @property remoteBranches locally known tracking names such as `origin/feature/x`, without symbolic aliases.
 * @property tags local tag names without `refs/tags/`; these do not establish remote tag availability.
 * @property remotes configured remote names, independent of whether refs have been fetched.
 * @property currentBranchName current local branch name, or null for detached/unavailable HEAD.
 */
@ExperimentalTerminalCompletionApi
public data class TerminalGitCompletionSnapshot(
    val localBranches: List<TerminalCompletionDomainValue> = emptyList(),
    val remoteBranches: List<TerminalCompletionDomainValue> = emptyList(),
    val tags: List<TerminalCompletionDomainValue> = emptyList(),
    val remotes: List<TerminalCompletionDomainValue> = emptyList(),
    val currentBranchName: String? = null,
)

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
package io.github.ketraterm.completion.commandline

import io.github.ketraterm.completion.api.TerminalCompletionActivePosition
import io.github.ketraterm.completion.model.TerminalCompletionValueDomain

/** Git-specific interpretation of the already resolved command and option occurrences. */
internal data class GitCompletionContext(
    val target: GitCompletionTarget,
    val remote: String? = null,
    val refspecStart: Int = 0,
    val refspecEnd: Int? = null,
    val includeCurrentBranch: Boolean = false,
    val excludedRemotes: List<String> = emptyList(),
)

internal enum class GitCompletionTarget {
    NONE,
    REMOTES,
    LOCAL_BRANCHES,
    LOCAL_REFERENCES,
    REMOTE_REFERENCES,
    ALL_REFERENCES,
}

internal fun resolveGitCompletion(
    analysis: CommandLineSemanticState,
    position: TerminalCompletionActivePosition,
    domain: TerminalCompletionValueDomain,
    line: TerminalCommandLineContext,
): GitCompletionContext? {
    if (analysis.commandPath.first().name != "git" ||
        (domain != TerminalCompletionValueDomain.GIT_BRANCH && domain != TerminalCompletionValueDomain.GIT_REMOTE)
    ) {
        return null
    }
    val command = analysis.commandPath.last()
    val arguments = analysis.positionalArguments

    // Compare resolved option identities: inherited -C values and words after -- are not flags.
    fun hasOption(name: String): Boolean =
        analysis.options.any { occurrence ->
            command.options.any { it === occurrence.option } && name in occurrence.option.names
        }
    val positional = position == TerminalCompletionActivePosition.POSITIONAL_ARGUMENT
    if (positional && command.name == "fetch") {
        if (hasOption("--all")) return GitCompletionContext(GitCompletionTarget.NONE)
        if (hasOption("--multiple")) return GitCompletionContext(GitCompletionTarget.REMOTES, excludedRemotes = arguments)
    }
    if (domain == TerminalCompletionValueDomain.GIT_REMOTE) return GitCompletionContext(GitCompletionTarget.REMOTES)
    val remote = arguments.firstOrNull()
    if (positional && command.name == "push" && (hasOption("--all") || hasOption("--mirror"))) {
        return GitCompletionContext(GitCompletionTarget.NONE)
    }
    val delete = positional && command.name == "push" && hasOption("--delete")
    var target =
        when {
            command.name == "fetch" || command.name == "pull" || delete -> GitCompletionTarget.REMOTE_REFERENCES
            command.name == "push" -> GitCompletionTarget.LOCAL_REFERENCES
            command.name in REFERENCE_COMMANDS -> GitCompletionTarget.ALL_REFERENCES
            else -> GitCompletionTarget.LOCAL_BRANCHES
        }
    val refspec = positional && command.name in REFSPEC_COMMANDS && !delete
    if (!refspec) return GitCompletionContext(target, remote)
    val token =
        line.tokens
            .getOrNull(line.activeTokenIndex)
            ?.text
            .orEmpty()
    // Do not rewrite wildcard/negative patterns or malformed multi-colon expressions.
    if ('*' in token || token.startsWith('^') || token.count { it == ':' } > 1) {
        return GitCompletionContext(GitCompletionTarget.NONE)
    }
    val colon = token.indexOf(':')
    val forceEnd = if (token.startsWith('+')) 1 else 0
    val destination = colon >= 0 && line.activePrefix.length > colon
    val start = if (destination) colon + 1 else forceEnd
    val end = if (destination || colon < 0) token.length else colon
    if (line.activePrefix.length < start) return GitCompletionContext(GitCompletionTarget.NONE)
    if (destination) {
        target = if (command.name == "push") GitCompletionTarget.REMOTE_REFERENCES else GitCompletionTarget.LOCAL_REFERENCES
    }
    return GitCompletionContext(
        target,
        remote,
        start,
        if (forceEnd > 0 || colon >= 0) end else null,
        includeCurrentBranch = command.name == "push" || destination,
    )
}

private val REFERENCE_COMMANDS = setOf("checkout", "merge", "rebase")
private val REFSPEC_COMMANDS = setOf("push", "fetch", "pull")

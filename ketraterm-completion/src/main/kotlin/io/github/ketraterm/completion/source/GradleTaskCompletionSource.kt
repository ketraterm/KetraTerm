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
import io.github.ketraterm.completion.api.TerminalCompletionCandidateKind
import io.github.ketraterm.completion.api.TerminalCompletionRequest
import io.github.ketraterm.completion.api.TerminalGradleTask
import io.github.ketraterm.completion.commandline.*
import io.github.ketraterm.completion.internal.TERMINAL_COMPLETION_CANDIDATE_ORDER
import io.github.ketraterm.completion.model.TerminalCommandSpec

/**
 * Pure Gradle-task source backed by a bounded host-published task snapshot.
 *
 * This source has no Gradle or filesystem dependency. It recognises the
 * declared `gradle` command (including its wrapper aliases), full `:project:task`
 * paths, and task names scoped by a preceding `-p` or `--project-dir` option.
 */
internal class GradleTaskCompletionSource(
    private val sourceId: String,
    private val tasksProvider: () -> List<TerminalGradleTask>,
    commandSpecs: List<TerminalCommandSpec>,
) : ContextAwareCompletionSource {
    private val commandSpecs = commandSpecs.toList()

    init {
        require(sourceId.isNotBlank()) { "sourceId must not be blank" }
    }

    override fun complete(request: TerminalCompletionRequest): List<TerminalCompletionCandidate> =
        complete(
            request,
            TerminalCommandLineTokenizer.parse(
                request.commandLine,
                request.cursorOffset,
                request.shellCapabilities.syntax,
            ),
        )

    override fun complete(
        request: TerminalCompletionRequest,
        commandLineContext: TerminalCommandLineContext,
    ): List<TerminalCompletionCandidate> {
        val context =
            TerminalCompletionContextResolver.resolve(
                commandLine = request.commandLine,
                lineContext = commandLineContext,
                commandSpecs = commandSpecs,
            )
        if (context.command?.name != GRADLE_COMMAND || context.activePosition != TerminalCompletionActivePosition.SUBCOMMAND) {
            return emptyList()
        }

        val prefix = context.activePrefix
        val projectDirectory = projectDirectoryBeforeActiveToken(context)
        val tasks = tasksProvider()
        if (tasks.isEmpty()) return emptyList()

        val candidates = ArrayList<TerminalCompletionCandidate>(minOf(tasks.size, request.maxCandidates))
        val emitted = HashSet<String>()
        for ((index, task) in tasks.withIndex()) {
            val replacement = replacementFor(task, prefix, projectDirectory) ?: continue
            if (!replacement.startsWith(prefix, ignoreCase = true) || replacement.equals(
                    prefix,
                    ignoreCase = true
                )
            ) continue
            if (!emitted.add(replacement)) continue
            val encodedReplacement =
                ShellReplacementText.encode(
                    value = replacement,
                    activeTokenQuote = context.activeTokenQuote,
                    policy = request.shellCapabilities.quoting,
                ) ?: continue
            candidates +=
                TerminalCompletionCandidate(
                    replacementText = encodedReplacement,
                    replacementStartOffset = context.replacementStartOffset,
                    replacementEndOffset = context.replacementEndOffset,
                    displayText = if (replacement == task.path) task.path else replacement,
                    detail = task.description.ifBlank { "Gradle task ${task.path}" },
                    source = sourceId,
                    kind = TerminalCompletionCandidateKind.SUBCOMMAND,
                    score = score(task, replacement, prefix, index),
                )
        }
        return candidates.sortedWith(TERMINAL_COMPLETION_CANDIDATE_ORDER).take(request.maxCandidates)
    }

    private fun replacementFor(
        task: TerminalGradleTask,
        prefix: String,
        projectDirectory: String?,
    ): String? =
        when {
            projectDirectory != null ->
                task.takeIf { normalizeProjectDirectory(it.projectDirectory) == projectDirectory }?.shortName

            prefix.startsWith(':') -> task.path
            task.isRootProjectTask -> task.shortName
            else -> null
        }

    private fun projectDirectoryBeforeActiveToken(context: TerminalCompletionContext): String? {
        var selectedDirectory: String? = null
        val tokens = context.commandLineContext.tokens
        var index = context.commandTokenIndex + 1
        while (index < context.commandLineContext.activeTokenIndex) {
            val token = tokens[index].text
            when {
                token == PROJECT_DIRECTORY_OPTION_SHORT || token == PROJECT_DIRECTORY_OPTION_LONG -> {
                    val value = tokens.getOrNull(++index)?.text ?: return selectedDirectory
                    selectedDirectory = normalizeProjectDirectory(value)
                }

                token.startsWith("$PROJECT_DIRECTORY_OPTION_LONG=") -> {
                    selectedDirectory = normalizeProjectDirectory(token.substringAfter('='))
                }
            }
            index++
        }
        return selectedDirectory
    }

    private fun normalizeProjectDirectory(value: String?): String? {
        var normalized = value?.replace('\\', '/') ?: return null
        while (normalized.startsWith("./")) normalized = normalized.removePrefix("./")
        if (normalized.length > 1) normalized = normalized.removeSuffix("/")
        return normalized.ifBlank { "." }
    }

    private val TerminalGradleTask.shortName: String get() = path.substringAfterLast(':')

    private val TerminalGradleTask.isRootProjectTask: Boolean get() = path.indexOf(':', startIndex = 1) < 0

    private fun score(
        task: TerminalGradleTask,
        replacement: String,
        prefix: String,
        orderIndex: Int,
    ): Int {
        val exactCaseBonus = if (replacement.startsWith(prefix)) 40 else 20
        val rootTaskBonus = if (task.isRootProjectTask) 20 else 0
        return BASE_SCORE + exactCaseBonus + rootTaskBonus - (replacement.length - prefix.length) - orderIndex
    }

    private companion object {
        private const val BASE_SCORE = 300
        private const val GRADLE_COMMAND = "gradle"
        private const val PROJECT_DIRECTORY_OPTION_SHORT = "-p"
        private const val PROJECT_DIRECTORY_OPTION_LONG = "--project-dir"
    }
}

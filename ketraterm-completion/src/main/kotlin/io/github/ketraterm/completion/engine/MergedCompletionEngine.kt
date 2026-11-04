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
package io.github.ketraterm.completion.engine

import io.github.ketraterm.completion.api.*
import io.github.ketraterm.completion.commandline.TerminalCommandLineTokenizer
import io.github.ketraterm.completion.commandline.firstCommandTokenIndex
import io.github.ketraterm.completion.commandline.normalizeTerminalCommandToken
import io.github.ketraterm.completion.internal.*
import io.github.ketraterm.completion.matching.CompletionMatcher
import io.github.ketraterm.completion.model.TerminalCommandSpec
import io.github.ketraterm.completion.model.TerminalCommandSpecs
import io.github.ketraterm.completion.model.TerminalCompletionValueDomain
import io.github.ketraterm.completion.model.TerminalPathArgumentKind
import io.github.ketraterm.completion.ranking.CompletionSourceCandidates
import io.github.ketraterm.completion.ranking.GlobalCompletionRanker
import io.github.ketraterm.completion.source.appendLearnedHistoryCandidates
import io.github.ketraterm.completion.source.hasNonPathValueDomain
import io.github.ketraterm.completion.spec.PathCommandSpecCandidateProjector
import io.github.ketraterm.completion.spec.SpecCompletionSource
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow

/** Coordinates bounded source collection and delegates deterministic fusion to [GlobalCompletionRanker]. */
internal class MergedCompletionEngine(
    sources: List<TerminalCompletionSourceEntry>,
    private val commandSpecs: List<TerminalCommandSpec> = TerminalCommandSpecs.defaults(),
    private val learningStore: TerminalCompletionLearningStore? = null,
    private val clockEpochMillis: () -> Long = System::currentTimeMillis,
    private val sourceFailureHandler: TerminalCompletionSourceFailureHandler =
        TerminalCompletionSourceFailureHandler.SYSTEM_LOGGER,
    messages: TerminalCompletionMessages = TerminalCompletionMessages.forLocale(),
    private val shellProvider: TerminalCompletionShellProvider? = null,
) : TerminalCompletionEngine {
    private val learnedCommandDetail = messages.message("completion.learnedCommand")
    private val learnedDirectoryDetail = messages.message("completion.learnedDirectory")
    private val learnedPathDetail = messages.message("completion.learnedPath")
    private val observedDetail = messages.message("completion.observedToken")
    private val specSource = commandSpecs.takeIf { it.isNotEmpty() }?.let(::SpecCompletionSource)
    private val shellSource =
        shellProvider?.let { provider ->
            TerminalCompletionSourceEntry(
                { request, context, limit -> provider.complete(request, context, limit)?.candidates.orEmpty() },
                TerminalCompletionSourcePrior.NATIVE_SHELL,
            )
        }
    private val sources = sources.toList()
    private val hostSourceIndexOffset = if (specSource == null) 0 else 1
    private val shellSourceIndex = hostSourceIndexOffset + sources.size
    private val learnedSourceIndex = shellSourceIndex + if (shellProvider == null) 0 else 1
    private val ranker = GlobalCompletionRanker()
    private val pathCommandSpecCandidateProjector = PathCommandSpecCandidateProjector(this.commandSpecs)

    override fun completions(request: TerminalCompletionRequest): Flow<List<TerminalCompletionCandidate>> =
        channelFlow {
            if (specSource == null && sources.isEmpty() && learningStore == null && shellProvider == null) {
                send(emptyList())
                return@channelFlow
            }

            val aliases = callShellProvider { shellProvider?.commandAliases() }.orEmpty()
            val resolvedContext = TerminalCompletionContext.resolve(request, commandSpecs, aliases)
            if (!resolvedContext.isCompletionAllowed) {
                send(emptyList())
                return@channelFlow
            }
            val commandPosition = resolvedContext.activePosition == TerminalCompletionActivePosition.COMMAND
            val structuredArguments = resolvedContext.hasStructuredArguments()
            val powerShellOptions =
                request.shellCapabilities.syntax == TerminalShellSyntax.POWERSHELL &&
                    (
                        resolvedContext.activePosition == TerminalCompletionActivePosition.OPTION_NAME ||
                            resolvedContext.activeOption != null ||
                            resolvedContext.hasOptionsBeforeCursor
                    )
            val shellContext =
                if (shellProvider != null && (resolvedContext.hasUnrecognizedOptions || powerShellOptions)) {
                    resolvedContext.withoutArgumentConstraints()
                } else {
                    resolvedContext
                }
            val queryShellFirst = shellProvider != null && (!structuredArguments || powerShellOptions)
            val shellAnswer = if (queryShellFirst) completeShell(request, shellContext) else null
            val deferToCatalog = powerShellOptions && shellAnswer != null && resolvedContext.acceptsCatalogArguments(shellAnswer)
            val shellCandidates = shellAnswer?.takeUnless { deferToCatalog }?.candidates
            val completionContext = if (shellCandidates != null) shellContext else resolvedContext
            val nativeArguments = !commandPosition && shellCandidates != null
            val activeSources = if (nativeArguments) emptyList() else sources
            val includeHistory =
                !nativeArguments &&
                    !completionContext.commandLineContext.precededByOperator &&
                    completionContext.gitCompletion?.refspecEnd == null
            val availableCommands =
                if (commandPosition) {
                    shellCandidates?.mapNotNull { commandName(request, it) }?.toHashSet()
                } else {
                    null
                }

            fun available(candidate: TerminalCompletionCandidate): Boolean =
                availableCommands == null || commandName(request, candidate) in availableCommands
            val learningIndexes = learningStore?.indexesFor(request.shellCapabilities.syntax)
            val nowEpochMillis = clockEpochMillis().coerceAtLeast(0L)
            val rankingState =
                ranker.createRequestState(
                    request = request,
                    context = completionContext,
                    resultLimit = REQUEST_CANDIDATE_LIMIT,
                    learnedIndex = learningIndexes?.evidence,
                    nowEpochMillis = nowEpochMillis,
                )
            var lastPublished: List<TerminalCompletionCandidate>? = null
            var hasStructuredCandidates = false

            suspend fun publish() {
                val ranked =
                    rankingState.rankedCandidates().let { candidates ->
                        // Enrich the winning row, so later equivalent edits cannot erase catalog details.
                        if (commandPosition && shellCandidates != null) candidates.map { describeCommand(request, it) } else candidates
                    }
                if (ranked != lastPublished) {
                    lastPublished = ranked
                    send(ranked)
                }
            }

            if (includeHistory && learningIndexes != null) {
                rankingState.ingest(
                    CompletionSourceCandidates(
                        sourceIndex = learnedSourceIndex,
                        priority = LEARNED_PRIORITY,
                        isFallback = true,
                        candidates = learnedCandidates(request, completionContext, learningIndexes, nowEpochMillis, ::available),
                    ),
                )
            }

            if (shellCandidates == null && specSource != null) {
                val candidates = completeSource(specSource, request, completionContext)
                if (candidates.isNotEmpty()) {
                    hasStructuredCandidates = true
                    rankingState.ingest(
                        CompletionSourceCandidates(
                            sourceIndex = 0,
                            priority = STATIC_SPECIFICATION_PRIORITY,
                            candidates = candidates,
                        ),
                    )
                }
            }

            if (!shellCandidates.isNullOrEmpty()) {
                rankingState.ingest(
                    CompletionSourceCandidates(
                        sourceIndex = shellSourceIndex,
                        priority = TerminalCompletionSourcePrior.NATIVE_SHELL,
                        candidates =
                            shellCandidates.filter { changesText(request, it) }.map {
                                if (commandPosition) rankShellCommand(request, completionContext, it) else it
                            },
                    ),
                )
            }

            // Command authority is resolved before the first publication; argument sources remain progressive.
            val canFallBackToShell = shellCandidates == null && shellProvider != null && (deferToCatalog || !queryShellFirst)
            if ((!canFallBackToShell || hasStructuredCandidates) &&
                (rankingState.rankedCandidates().isNotEmpty() || activeSources.isEmpty())
            ) {
                publish()
            }
            collectSources(activeSources, request, completionContext, ::available) { completed ->
                if (completed.candidates.isNotEmpty()) hasStructuredCandidates = true
                rankingState.ingest(completed)
                if (!canFallBackToShell || hasStructuredCandidates) publish()
            }
            if (canFallBackToShell && !hasStructuredCandidates) {
                val answer = if (queryShellFirst) shellAnswer else completeShell(request, completionContext)
                val fallback =
                    answer
                        ?.candidates
                        .orEmpty()
                        .filter { it.kind != TerminalCompletionCandidateKind.PATH || !completionContext.hasNonPathValueDomain() }
                        .filter { changesText(request, it) }
                if (fallback.isNotEmpty()) {
                    rankingState.ingest(
                        CompletionSourceCandidates(
                            sourceIndex = shellSourceIndex,
                            priority = TerminalCompletionSourcePrior.NATIVE_SHELL,
                            candidates = fallback,
                        ),
                    )
                }
                publish()
            }
        }

    /** Generic native paths do not replace a compatible application's modeled arguments. */
    private fun TerminalCompletionContext.acceptsCatalogArguments(answer: TerminalCompletionShellResult): Boolean {
        val externalName = answer.externalCommandName ?: return false
        val spec = command ?: return false
        if (answer.candidates.any { it.kind != TerminalCompletionCandidateKind.PATH }) return false
        val normalized = normalizeTerminalCommandToken(externalName)
        val stem =
            when (normalized.substringAfterLast('.')) {
                "exe", "com", "cmd", "bat" -> normalized.substringBeforeLast('.')
                else -> normalized
            }
        return sequenceOf(spec.name).plus(spec.aliases).any {
            val name = normalizeTerminalCommandToken(it)
            name == normalized || name == stem
        }
    }

    private fun TerminalCompletionContext.hasStructuredArguments(): Boolean =
        !hasUnrecognizedOptions &&
            activePosition != TerminalCompletionActivePosition.COMMAND &&
            (
                subcommandCandidateSource != null ||
                    activeOption != null ||
                    activePositionalArgument != null ||
                    expectedPathKind != TerminalPathArgumentKind.NONE ||
                    expectedValueDomain != TerminalCompletionValueDomain.NONE ||
                    (activePosition == TerminalCompletionActivePosition.OPTION_NAME && commandPath.any { it.options.isNotEmpty() })
            )

    private fun learnedCandidates(
        request: TerminalCompletionRequest,
        context: TerminalCompletionContext,
        indexes: CompletionLearningIndexes,
        nowEpochMillis: Long,
        available: (TerminalCompletionCandidate) -> Boolean,
    ): List<TerminalCompletionCandidate> {
        val candidates = ArrayList<TerminalCompletionCandidate>()
        appendLearnedHistoryCandidates(
            request = request,
            lineContext = context.commandLineContext,
            completionContext = context,
            index = indexes.history,
            nowEpochMillis = nowEpochMillis,
            destination = candidates,
            commandDetail = learnedCommandDetail,
            directoryDetail = learnedDirectoryDetail,
            pathDetail = learnedPathDetail,
        )
        indexes.observed.appendCandidates(request = request, context = context, destination = candidates, detail = observedDetail)
        candidates.sortWith(TERMINAL_COMPLETION_CANDIDATE_ORDER)
        return pathCommandSpecCandidateProjector
            .project(request, context, candidates)
            .filter { it.hasValidReplacementRangeFor(request) }
            .filter(available)
            .filter { changesText(request, it) }
            .boundedTo(SOURCE_CANDIDATE_LIMIT)
    }

    /** Owns provider children and delivers one result per source, including source-local cancellation. */
    private suspend fun collectSources(
        activeSources: List<TerminalCompletionSourceEntry>,
        request: TerminalCompletionRequest,
        context: TerminalCompletionContext,
        available: (TerminalCompletionCandidate) -> Boolean,
        onCompleted: suspend (CompletionSourceCandidates) -> Unit,
    ) {
        if (activeSources.isEmpty()) return
        val completions = Channel<CompletionSourceCandidates>(activeSources.size)
        try {
            coroutineScope {
                for ((localIndex, entry) in activeSources.withIndex()) {
                    val sourceIndex = localIndex + hostSourceIndexOffset
                    launch {
                        val candidates =
                            try {
                                completeSource(entry.source, request, context)
                                    .filter(available)
                                    .also { coroutineContext.ensureActive() }
                            } catch (_: CancellationException) {
                                this@coroutineScope.ensureActive()
                                emptyList()
                            } catch (failure: Throwable) {
                                reportSourceFailure(sourceIndex, entry, failure)
                                if (failure !is Exception) throw failure
                                emptyList()
                            }
                        // One slot per child permits a cancelled source to report without suspending.
                        completions.trySend(CompletionSourceCandidates(sourceIndex, entry.priority, candidates = candidates)).getOrThrow()
                    }
                }
                repeat(activeSources.size) { onCompleted(completions.receive()) }
            }
        } finally {
            completions.cancel()
        }
    }

    private suspend fun completeShell(
        request: TerminalCompletionRequest,
        context: TerminalCompletionContext,
    ): TerminalCompletionShellResult? =
        callShellProvider {
            val result = shellProvider?.complete(request, context, SOURCE_CANDIDATE_LIMIT) ?: return@callShellProvider null
            TerminalCompletionShellResult(
                result.candidates
                    .filter { it.hasValidReplacementRangeFor(request) }
                    .filter { it.kind != TerminalCompletionCandidateKind.PATH || !context.hasNonPathValueDomain() }
                    .boundedTo(SOURCE_CANDIDATE_LIMIT),
                result.externalCommandName,
            )
        }

    /** Both shell entry points share diagnostics and preserve request cancellation. */
    private suspend fun <T> callShellProvider(action: suspend () -> T): T? =
        try {
            currentCoroutineContext().ensureActive()
            val result = action()
            currentCoroutineContext().ensureActive()
            result
        } catch (_: CancellationException) {
            currentCoroutineContext().ensureActive()
            null
        } catch (failure: Throwable) {
            reportSourceFailure(shellSourceIndex, checkNotNull(shellSource), failure)
            if (failure !is Exception) throw failure
            null
        }

    private suspend fun completeSource(
        source: TerminalCompletionSource,
        request: TerminalCompletionRequest,
        context: TerminalCompletionContext,
    ): List<TerminalCompletionCandidate> {
        val candidates = source.complete(request, context, SOURCE_CANDIDATE_LIMIT)
        return pathCommandSpecCandidateProjector
            .project(request, context, candidates)
            .filter { it.hasValidReplacementRangeFor(request) }
            .filter { changesText(request, it) }
            .filter { it.kind != TerminalCompletionCandidateKind.PATH || !context.hasNonPathValueDomain() }
            .boundedTo(SOURCE_CANDIDATE_LIMIT)
    }

    private fun commandName(
        request: TerminalCompletionRequest,
        candidate: TerminalCompletionCandidate,
    ): String? {
        val line = request.commandLineAfterCandidate(candidate) ?: return null
        val parsed =
            TerminalCommandLineTokenizer.parse(
                line,
                candidate.replacementStartOffset + candidate.replacementText.length,
                request.shellCapabilities.syntax,
            )
        val name = parsed.tokens.getOrNull(parsed.tokens.firstCommandTokenIndex())?.text ?: return null
        return if (request.shellCapabilities.syntax == TerminalShellSyntax.POWERSHELL && '/' !in name && '\\' !in name) {
            name.lowercase()
        } else {
            name
        }
    }

    private fun changesText(
        request: TerminalCompletionRequest,
        candidate: TerminalCompletionCandidate,
    ): Boolean =
        candidate.replacementEndOffset - candidate.replacementStartOffset != candidate.replacementText.length ||
            !request.commandLine.regionMatches(
                candidate.replacementStartOffset,
                candidate.replacementText,
                0,
                candidate.replacementText.length,
            )

    private fun rankShellCommand(
        request: TerminalCompletionRequest,
        context: TerminalCompletionContext,
        candidate: TerminalCompletionCandidate,
    ): TerminalCompletionCandidate {
        if (candidate.kind != TerminalCompletionCandidateKind.COMMAND) return candidate
        val spec = commandSpec(request, candidate)
        val display =
            if (spec != null &&
                candidate.replacementText == spec.name
            ) {
                spec.name
            } else {
                candidate.displayText
            }
        val match = CompletionMatcher.match(display, context.activePrefix)
        return candidate.copy(
            displayText = display,
            matchedRanges = match?.matchedRanges ?: candidate.matchedRanges,
            score = match?.score ?: candidate.score,
        )
    }

    private fun describeCommand(
        request: TerminalCompletionRequest,
        candidate: TerminalCompletionCandidate,
    ): TerminalCompletionCandidate {
        if (candidate.kind != TerminalCompletionCandidateKind.COMMAND) return candidate
        val description = commandSpec(request, candidate)?.description?.takeIf { it.isNotBlank() } ?: return candidate
        if (candidate.detail == description) return candidate
        return candidate.copy(detail = if (candidate.detail.isBlank()) description else description + "\n" + candidate.detail)
    }

    private fun commandSpec(
        request: TerminalCompletionRequest,
        candidate: TerminalCompletionCandidate,
    ): TerminalCommandSpec? {
        val name = commandName(request, candidate) ?: return null
        val ignoreCase = request.shellCapabilities.syntax == TerminalShellSyntax.POWERSHELL && '/' !in name && '\\' !in name
        return commandSpecs.firstOrNull { spec ->
            spec.name.equals(name, ignoreCase) || spec.aliases.any { it.equals(name, ignoreCase) }
        }
    }

    private fun reportSourceFailure(
        sourceIndex: Int,
        source: TerminalCompletionSourceEntry,
        failure: Throwable,
    ) {
        try {
            sourceFailureHandler.sourceFailed(sourceIndex, source, failure)
        } catch (diagnosticFailure: RuntimeException) {
            diagnosticFailure.addSuppressed(failure)
            TerminalCompletionSourceFailureHandler.SYSTEM_LOGGER.sourceFailed(sourceIndex, source, diagnosticFailure)
        }
    }

    private companion object {
        private const val STATIC_SPECIFICATION_PRIORITY = 0
        private const val LEARNED_PRIORITY = 8
        private const val SOURCE_CANDIDATE_LIMIT = 256
        private const val REQUEST_CANDIDATE_LIMIT = 256
    }
}

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
package io.github.ketraterm.completion.api

import io.github.ketraterm.completion.model.TerminalCommandSpec
import io.github.ketraterm.completion.model.TerminalCommandSpecs
import io.github.ketraterm.completion.model.TerminalCompletionDomainValue
import io.github.ketraterm.completion.model.TerminalCompletionValueDomain
import io.github.ketraterm.completion.source.*
import io.github.ketraterm.completion.spec.SpecCompletionSource

/** Factories for dependency-free, host-composable completion sources. */
object TerminalCompletionSources {
    /**
     * Creates a deterministic source backed by static command specs.
     *
     * @param specs top-level command specs.
     * @return completion source that evaluates [specs] without shell I/O.
     */
    @JvmStatic
    fun fromSpecs(specs: List<TerminalCommandSpec>): TerminalCompletionSource = SpecCompletionSource(specs)

    /**
     * Creates a bounded in-memory source for commands observed in the current
     * terminal session.
     *
     * @param capacity maximum number of distinct normalized commands and
     * session-local observed-token transitions retained.
     * @param commandSpecs static command specs whose known command families are
     * excluded from observed-token learning because specs are authoritative for
     * those commands.
     * @param learnedStatsProvider immutable learned-statistics snapshot used to
     * recover positive commands across sessions. These rows contribute through
     * this single learned source and are not a separate completion provider.
     * @return mutable session MRU completion source.
     * @throws IllegalArgumentException if [capacity] is not positive.
     */
    @JvmStatic
    @JvmOverloads
    fun sessionMru(
        capacity: Int = 128,
        commandSpecs: List<TerminalCommandSpec> = TerminalCommandSpecs.defaults(),
        learnedStatsProvider: () -> io.github.ketraterm.completion.model.TerminalCommandCompletionStatsSnapshot = {
            io.github.ketraterm.completion.model.TerminalCommandCompletionStatsSnapshot.EMPTY
        },
    ): TerminalSessionMruCompletionSource =
        SessionMruCompletionSourceImpl(
            capacity = capacity,
            commandSpecs = commandSpecs,
            learnedStatsProvider = learnedStatsProvider,
        )

    /**
     * Creates a bounded in-memory store for aggregated command statistics.
     *
     * Hosts should feed this store from compact persisted snapshots and live
     * suggestion feedback. The store performs no persistence or I/O and is not
     * itself a [TerminalCompletionSource].
     *
     * @param capacity maximum distinct command/profile/directory rows retained.
     * @param commandSpecs command specifications used to classify
     * privacy-preserving command-family shapes.
     * @return mutable command-statistics store for ranking and learned fallback evidence.
     * @throws IllegalArgumentException if [capacity] is not positive.
     */
    @JvmStatic
    @JvmOverloads
    fun learningStore(
        capacity: Int = 2048,
        commandSpecs: List<TerminalCommandSpec> = TerminalCommandSpecs.defaults(),
    ): TerminalCompletionLearningStore =
        CompletionLearningStoreImpl(
            capacity = capacity,
            commandSpecs = commandSpecs,
        )

    /**
     * Creates a path autocomplete source backed by a host-provided file system lister.
     *
     * @param fileSystemProvider host-implemented directory lister.
     * @return path completion source.
     */
    @JvmStatic
    fun path(fileSystemProvider: TerminalFileSystemProvider): TerminalCompletionSource = PathCompletionSource(fileSystemProvider)

    /**
     * Creates a source that fuzzy-matches a bounded host path result.
     *
     * [entriesProvider] is called only for an eligible completion context and
     * must return paths relative to the request's current directory. It may
     * perform bounded suspending host work and must cooperate with cancellation.
     * The result is matched once by the shared dependency-free matcher before
     * terminal path rules are applied.
     *
     * @param sourceId stable candidate-source id used by ranking feedback.
     * @param entriesProvider suspending loader for bounded indexed paths.
     * @param requiresNonEmptyPrefix whether this source waits for explicit path
     * text before matching. Use `false` only for small, context-specific
     * result sets such as changed Git paths.
     * @param allowedCommandNames optional canonical command/subcommand names to
     * which this source is restricted. An empty set permits every valid path
     * position.
     * @return context-aware fuzzy path completion source.
     * @throws IllegalArgumentException if [sourceId] is blank.
     */
    @JvmStatic
    @JvmOverloads
    fun fuzzyPath(
        sourceId: String,
        entriesProvider: suspend () -> List<TerminalFuzzyPathEntry>,
        requiresNonEmptyPrefix: Boolean = true,
        allowedCommandNames: Set<String> = emptySet(),
    ): TerminalCompletionSource {
        require(sourceId.isNotBlank()) { "sourceId must not be blank" }
        require(allowedCommandNames.none(String::isBlank)) { "allowedCommandNames must not contain blank values" }
        return FuzzyPathCompletionSource(
            sourceId = sourceId,
            entriesProvider = BoundedFuzzyPathProvider(entriesProvider),
            requiresNonEmptyPrefix = requiresNonEmptyPrefix,
            allowedCommandNames = allowedCommandNames.toSet(),
        )
    }

    /**
     * Creates a source backed by a query-aware host fuzzy-path provider.
     *
     * Unlike the list-loader overload, this overload passes the decoded
     * active path prefix to [entriesProvider]. This lets IDE hosts query their
     * indexes asynchronously and apply bounds after matching instead of
     * truncating an unrelated whole-project traversal. The provider owns the
     * only fuzzy match and must return ready results in relevance order without
     * blocking the completion thread.
     *
     * @param sourceId stable candidate-source id used by ranking feedback.
     * @param entriesProvider ready query-aware path provider.
     * @param requiresNonEmptyPrefix whether this source waits for explicit path text.
     * @param allowedCommandNames optional canonical command/subcommand restriction.
     * @return context-aware fuzzy path completion source.
     * @throws IllegalArgumentException if [sourceId] is blank.
     */
    @JvmStatic
    @JvmOverloads
    fun fuzzyPath(
        sourceId: String,
        entriesProvider: TerminalFuzzyPathProvider,
        requiresNonEmptyPrefix: Boolean = true,
        allowedCommandNames: Set<String> = emptySet(),
    ): TerminalCompletionSource {
        require(sourceId.isNotBlank()) { "sourceId must not be blank" }
        require(allowedCommandNames.none(String::isBlank)) { "allowedCommandNames must not contain blank values" }
        return FuzzyPathCompletionSource(
            sourceId = sourceId,
            entriesProvider = entriesProvider,
            requiresNonEmptyPrefix = requiresNonEmptyPrefix,
            allowedCommandNames = allowedCommandNames.toSet(),
        )
    }

    /**
     * Creates a source for host-indexed Gradle tasks.
     *
     * The source understands Gradle's canonical `:project:task` notation and
     * scopes short task names after `-p` or `--project-dir`. [tasksProvider]
     * must return a bounded result and cooperate with cancellation. The loader
     * may read a host model but must never start Gradle from a completion request.
     *
     * @param sourceId stable candidate-source id used by ranking feedback.
     * @param tasksProvider suspending bounded Gradle-task loader.
     * @return context-aware Gradle task completion source.
     * @throws IllegalArgumentException if [sourceId] is blank.
     */
    @JvmStatic
    fun gradleTask(
        sourceId: String,
        tasksProvider: suspend () -> List<TerminalGradleTask>,
    ): TerminalCompletionSource =
        GradleTaskCompletionSource(
            sourceId = sourceId,
            tasksProvider = tasksProvider,
        )

    /**
     * Creates a source for one host-owned dynamic value domain.
     *
     * [valuesProvider] is called only when the resolved context expects [domain].
     * It may perform bounded suspending host work and must cooperate with
     * cancellation. The source retains no returned values.
     *
     * @param domain command-spec value domain served by this source.
     * @param sourceId stable candidate-source id used by ranking feedback.
     * @param valuesProvider suspending bounded value loader.
     * @param allowedCommandNames optional canonical command/subcommand names to
     * which this source is restricted. An empty set permits every matching
     * value-domain position.
     * @return context-aware dynamic value completion source.
     * @throws IllegalArgumentException if [domain] is
     * [TerminalCompletionValueDomain.NONE] or [sourceId] is blank.
     */
    @JvmStatic
    @JvmOverloads
    fun valueDomain(
        domain: TerminalCompletionValueDomain,
        sourceId: String,
        valuesProvider: suspend () -> List<TerminalCompletionDomainValue>,
        allowedCommandNames: Set<String> = emptySet(),
    ): TerminalCompletionSource =
        ValueDomainCompletionSource(
            domain = domain,
            sourceId = sourceId,
            valuesProvider = valuesProvider,
            allowedCommandNames = allowedCommandNames.toSet(),
        )
}

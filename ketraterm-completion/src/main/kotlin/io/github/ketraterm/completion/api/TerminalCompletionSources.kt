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

import io.github.ketraterm.completion.model.*
import io.github.ketraterm.completion.ranking.FeedbackAwareCompletionSource
import io.github.ketraterm.completion.ranking.ShapeAwareCompletionSource
import io.github.ketraterm.completion.source.CommandStatsCompletionSourceImpl
import io.github.ketraterm.completion.source.PathCompletionSource
import io.github.ketraterm.completion.source.SessionMruCompletionSourceImpl
import io.github.ketraterm.completion.source.ValueDomainCompletionSource
import io.github.ketraterm.completion.spec.SpecCompletionSource

/** Factories for dependency-free, host-composable completion sources. */
object TerminalCompletionSources {
    /**
     * Creates a deterministic source backed by static command specs.
     *
     * When [shapeStatsProvider] is present, spec candidates are adjusted by
     * privacy-preserving command-shape learning before they are returned. Hosts
     * should not compose that ranking decorator directly; command-shape learning
     * is part of the static-spec source behavior.
     *
     * @param specs top-level command specs.
     * @param shapeStatsProvider optional supplier for the latest command-shape
     * stats snapshot used to rank static candidates.
     * @return completion source that evaluates [specs] without shell I/O.
     */
    @JvmStatic
    @JvmOverloads
    fun fromSpecs(
        specs: List<TerminalCommandSpec>,
        shapeStatsProvider: (() -> List<TerminalCommandShapeStats>)? = null,
    ): TerminalCompletionSource {
        val source = SpecCompletionSource(specs)
        return if (shapeStatsProvider == null) {
            source
        } else {
            ShapeAwareCompletionSource(
                delegate = source,
                shapeStatsProvider = shapeStatsProvider,
                commandSpecs = specs,
            )
        }
    }

    /**
     * Creates a bounded in-memory source for commands observed in the current
     * terminal session.
     *
     * @param capacity maximum number of distinct normalized commands and
     * session-local observed-token transitions retained.
     * @param commandSpecs static command specs whose known command families are
     * excluded from observed-token learning because specs are authoritative for
     * those commands.
     * @return mutable session MRU completion source.
     * @throws IllegalArgumentException if [capacity] is not positive.
     */
    @JvmStatic
    @JvmOverloads
    fun sessionMru(
        capacity: Int = 128,
        commandSpecs: List<TerminalCommandSpec> = TerminalCommandSpecs.defaults(),
    ): TerminalSessionMruCompletionSource =
        SessionMruCompletionSourceImpl(
            capacity = capacity,
            commandSpecs = commandSpecs,
        )

    /**
     * Creates a bounded in-memory source for aggregated command statistics.
     *
     * Hosts should feed this source from compact stats indexes and live
     * suggestion feedback. The source itself performs no persistence or I/O.
     *
     * @param capacity maximum distinct command/profile/directory rows retained.
     * @param commandSpecs command specifications used to classify
     * privacy-preserving command-family shapes.
     * @return mutable command stats completion source.
     * @throws IllegalArgumentException if [capacity] is not positive.
     */
    @JvmStatic
    @JvmOverloads
    fun commandStats(
        capacity: Int = 2048,
        commandSpecs: List<TerminalCommandSpec> = TerminalCommandSpecs.defaults(),
    ): TerminalCommandStatsCompletionSource =
        CommandStatsCompletionSourceImpl(
            capacity = capacity,
            commandSpecs = commandSpecs,
        )

    /**
     * Wraps [source] with source-specific feedback score adjustment.
     *
     * @param source source whose candidates should be feedback-ranked.
     * @param feedbackStatsProvider supplier for the latest source-specific
     * feedback stats snapshot.
     * @return source that returns the same candidates with adjusted scores.
     */
    @JvmStatic
    fun feedbackAware(
        source: TerminalCompletionSource,
        feedbackStatsProvider: () -> List<TerminalCompletionFeedbackStats>,
    ): TerminalCompletionSource =
        FeedbackAwareCompletionSource(
            delegate = source,
            feedbackStatsProvider = feedbackStatsProvider,
        )

    /**
     * Creates a path autocomplete source backed by a host-provided file system lister.
     *
     * @param fileSystemProvider host-implemented directory lister.
     * @param commandSpecs command specs whose path argument metadata controls
     * bare argument path completion.
     * @return path completion source.
     */
    @JvmStatic
    @JvmOverloads
    fun path(
        fileSystemProvider: TerminalFileSystemProvider,
        commandSpecs: List<TerminalCommandSpec> = TerminalCommandSpecs.defaults(),
    ): TerminalCompletionSource =
        PathCompletionSource(
            fileSystemProvider = fileSystemProvider,
            commandSpecs = commandSpecs,
        )

    /**
     * Creates a pure source for one host-owned dynamic value domain.
     *
     * [valuesProvider] must return a bounded, ready in-memory snapshot and must
     * never perform disk, network, shell, index, or UI work. Hosts should refresh
     * snapshots asynchronously and notify their presentation layer separately.
     *
     * @param domain command-spec value domain served by this source.
     * @param sourceId stable candidate-source id used by ranking feedback.
     * @param valuesProvider supplier for the latest immutable value snapshot.
     * @param commandSpecs command specs used to resolve the active value domain.
     * @return context-aware dynamic value completion source.
     * @throws IllegalArgumentException if [domain] is
     * [TerminalCompletionValueDomain.NONE] or [sourceId] is blank.
     */
    @JvmStatic
    @JvmOverloads
    fun valueDomain(
        domain: TerminalCompletionValueDomain,
        sourceId: String,
        valuesProvider: () -> List<TerminalCompletionDomainValue>,
        commandSpecs: List<TerminalCommandSpec> = TerminalCommandSpecs.defaults(),
    ): TerminalCompletionSource =
        ValueDomainCompletionSource(
            domain = domain,
            sourceId = sourceId,
            valuesProvider = valuesProvider,
            commandSpecs = commandSpecs,
        )
}

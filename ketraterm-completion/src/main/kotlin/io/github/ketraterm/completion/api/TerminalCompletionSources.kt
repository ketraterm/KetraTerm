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

import io.github.ketraterm.completion.model.TerminalCommandShapeStats
import io.github.ketraterm.completion.model.TerminalCommandSpec
import io.github.ketraterm.completion.model.TerminalCommandSpecs
import io.github.ketraterm.completion.model.TerminalCompletionFeedbackStats
import io.github.ketraterm.completion.ranking.FeedbackAwareCompletionSource
import io.github.ketraterm.completion.ranking.ShapeAwareCompletionSource
import io.github.ketraterm.completion.source.CommandStatsCompletionSourceImpl
import io.github.ketraterm.completion.source.PathCompletionSource
import io.github.ketraterm.completion.source.SessionMruCompletionSourceImpl
import io.github.ketraterm.completion.spec.SpecCompletionSource

/**
 * Factories for dependency-free completion sources.
 */
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
     * @param capacity maximum number of distinct normalized commands retained.
     * @return mutable session MRU completion source.
     */
    @JvmStatic
    @JvmOverloads
    fun sessionMru(capacity: Int = 128): TerminalSessionMruCompletionSource = SessionMruCompletionSourceImpl(capacity)

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
     * @return path completion source.
     */
    @JvmStatic
    fun path(fileSystemProvider: TerminalFileSystemProvider): TerminalCompletionSource = PathCompletionSource(fileSystemProvider)
}

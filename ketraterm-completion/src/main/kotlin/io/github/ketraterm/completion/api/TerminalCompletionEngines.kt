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

import io.github.ketraterm.completion.engine.MergedCompletionEngine
import io.github.ketraterm.completion.model.TerminalCommandSpec
import io.github.ketraterm.completion.model.TerminalCommandSpecs

/**
 * Factories for production completion engines.
 */
object TerminalCompletionEngines {
    /**
     * Creates a deterministic merged engine from prioritized completion sources.
     *
     * Candidates are deduplicated by replacement range and replacement text.
     * Ranking combines source priority with command-line context derived from
     * [commandSpecs], then applies candidate score and stable text tie-breakers
     * before [TerminalCompletionRequest.maxCandidates] is applied.
     *
     * @param sources prioritized source registrations.
     * @param commandSpecs command specs used to classify the active command-line
     * position for ranking.
     * @return merged completion engine.
     */
    @JvmStatic
    @JvmOverloads
    fun fromSources(
        sources: List<TerminalCompletionSourceEntry>,
        commandSpecs: List<TerminalCommandSpec> = TerminalCommandSpecs.defaults(),
    ): TerminalCompletionEngine =
        if (sources.isEmpty()) {
            TerminalCompletionEngine.NONE
        } else {
            MergedCompletionEngine(
                sources = sources,
                commandSpecs = commandSpecs,
            )
        }

    /**
     * Creates a deterministic merged engine from equal-priority sources.
     *
     * @param sources completion sources queried in declaration order.
     * @return merged completion engine.
     */
    @JvmStatic
    fun fromSources(vararg sources: TerminalCompletionSource): TerminalCompletionEngine =
        fromSources(sources.map { TerminalCompletionSourceEntry(it) })
}

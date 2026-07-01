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

/**
 * Factories for production completion engines.
 */
object TerminalCompletionEngines {
    /**
     * Creates a deterministic in-process engine backed by command specs.
     *
     * @param specs top-level command specs.
     * @return completion engine that evaluates [specs] without shell I/O.
     */
    @JvmStatic
    fun fromSpecs(specs: List<TerminalCommandSpec>): TerminalCompletionEngine =
        fromSources(
            listOf(
                TerminalCompletionSourceEntry(
                    source = TerminalCompletionSources.fromSpecs(specs),
                    priority = SPEC_SOURCE_PRIORITY,
                ),
            ),
        )

    /**
     * Creates a deterministic merged engine from prioritized completion sources.
     *
     * Candidates are deduplicated by replacement range and replacement text,
     * then ranked by source priority, candidate score, and stable text
     * tie-breakers before [TerminalCompletionRequest.maxCandidates] is applied.
     *
     * @param sources prioritized source registrations.
     * @return merged completion engine.
     */
    @JvmStatic
    fun fromSources(sources: List<TerminalCompletionSourceEntry>): TerminalCompletionEngine =
        if (sources.isEmpty()) {
            TerminalCompletionEngine.NONE
        } else {
            MergedCompletionEngine(sources)
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

    private const val SPEC_SOURCE_PRIORITY = 0
}

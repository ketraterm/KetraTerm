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

/**
 * Receives non-cancellation failures isolated from one merged completion source.
 *
 * Implementations must be thread-safe, return promptly, and must not throw.
 * Different source child coroutines may report concurrently while the merged
 * engine continues collecting the remaining sources.
 */
fun interface TerminalCompletionSourceFailureHandler {
    /**
     * Reports one failed source evaluation.
     *
     * @param sourceIndex zero-based declaration index within the merged engine.
     * @param source failed source registration.
     * @param failure non-cancellation failure thrown by the source.
     */
    fun sourceFailed(
        sourceIndex: Int,
        source: TerminalCompletionSourceEntry,
        failure: Throwable,
    )

    companion object {
        /** Default handler that writes failures through the JDK system logger. */
        @JvmField
        val SYSTEM_LOGGER: TerminalCompletionSourceFailureHandler =
            TerminalCompletionSourceFailureHandler { sourceIndex, source, failure ->
                System
                    .getLogger("io.github.ketraterm.completion")
                    .log(
                        System.Logger.Level.WARNING,
                        "Completion source #$sourceIndex (${source.source.javaClass.name}) failed",
                        failure,
                    )
            }
    }
}

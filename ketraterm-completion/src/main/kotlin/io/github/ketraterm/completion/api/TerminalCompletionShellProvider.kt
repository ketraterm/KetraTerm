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
 * Live shell completion authority, separate from catalog and filesystem sources.
 *
 * **Experimental:** follows the stability contract of [TerminalCompletionEngine].
 *
 * At command positions a non-null result defines the verified executable candidates;
 * specifications enrich those candidates instead of advertising unverified commands.
 * Modeled argument positions use catalog/host sources first and query the shell
 * only if they produce no candidates. Unmodeled arguments use the shell first.
 */
@ExperimentalTerminalCompletionApi
public fun interface TerminalCompletionShellProvider {
    /**
     * Returns shell-owned, escaped edits for this exact request. Null permits
     * ordinary catalog/source fallback. An empty list offers no safe candidates
     * and suppresses unverified fallback at positions owned by the shell.
     * The engine owns invocation, cancellation, and source concurrency. Implementations
     * own bounded host I/O and must preserve caller cancellation.
     */
    public suspend fun complete(
        request: TerminalCompletionRequest,
        context: TerminalCompletionContext,
        limit: Int,
    ): List<TerminalCompletionCandidate>?
}

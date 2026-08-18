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
 * Mutable learned-history source for commands observed in one live terminal
 * session and optional positive persisted command evidence.
 *
 * Hosts own the command lifecycle signal and feed this source only with trusted
 * successful commands. A host may supply immutable persisted statistics at
 * construction time to recover unsupported commands across sessions without
 * composing statistics as another provider. Implementations are in-memory,
 * bounded, thread-safe, and perform no persistence or shell I/O.
 */
interface TerminalSessionMruCompletionSource : TerminalCompletionSource {
    /** Session and persisted history yield to primary sources that supply the same edit. */
    override val presentationRole: TerminalCompletionSourcePresentationRole
        get() = TerminalCompletionSourcePresentationRole.FALLBACK

    /**
     * Records one successful command for future session MRU suggestions.
     *
     * @param commandLine command line as executed by the shell.
     * @param profileId optional host profile id associated with the command.
     * @param workingDirectoryUri optional current-working-directory URI at command start.
     */
    fun recordSuccessfulCommand(
        commandLine: String,
        profileId: String? = null,
        workingDirectoryUri: String? = null,
    )

    /**
     * Removes all retained session MRU commands.
     */
    fun clear()
}

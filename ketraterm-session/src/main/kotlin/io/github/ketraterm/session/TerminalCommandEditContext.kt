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
package io.github.ketraterm.session

import io.github.ketraterm.input.event.TerminalKeyEvent
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Session-issued editing context captured with a selected producer's revision.
 * Retain it from suggestion request through final admission; never recapture it
 * merely to make an old edit pass. It is specific to its issuing session and
 * becomes stale after input, output, resize or a producer update. A session-validated,
 * non-editing native completion exchange can preserve the original context when
 * the command, cursor, producer revision, and render generation remain unchanged.
 * No writer or frame lease is retained. Acceptance still does not confirm remote execution.
 *
 * @property commandLine snapshot used to calculate editor actions. A snapshot
 * requiring shell confirmation cannot admit edits until the native editor has
 * confirmed its exact text and cursor through [TerminalSession.requestShellCompletions].
 * @property prefixResetKeys detached key sequence that clears only the editor's text before its cursor,
 * captured with [commandLine]; empty when that capability is unavailable.
 */
public class TerminalCommandEditContext internal constructor(
    public val commandLine: TerminalShellCommandLineSnapshot,
    prefixResetKeys: List<TerminalKeyEvent>,
    internal val owner: Any,
    internal val inputRevision: Long,
    internal val outputRevision: Long,
    internal val shellRevision: Long,
) {
    public val prefixResetKeys: List<TerminalKeyEvent> = copyCommandLinePrefixResetKeys(prefixResetKeys)

    private val cancelled = AtomicBoolean()

    /** Whether the owner has cancelled this request. Cancellation is permanent. */
    public val isCancelled: Boolean get() = cancelled.get()

    /**
     * Prevents subsequent admission using this context. A cancellation racing the
     * final admission check may lose; it never retracts already accepted bytes.
     */
    public fun cancel() {
        cancelled.set(true)
    }
}

/** Detaches the bounded editing sequence at host-factory and request-capture boundaries. */
internal fun copyCommandLinePrefixResetKeys(keys: List<TerminalKeyEvent>): List<TerminalKeyEvent> {
    require(keys.size <= 4) { "Command-line prefix reset must contain at most four keys" }
    return java.util.List.copyOf(keys)
}

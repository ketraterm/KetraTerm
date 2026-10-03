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

/**
 * Observable lifecycle state of a [TerminalSession].
 *
 * A session transitions monotonically from [Created] to [Running] and then to
 * [Closed]. Closing before or during startup transitions directly from [Created] to
 * [Closed].
 */
public sealed interface TerminalSessionState {
    /** Connector startup has not completed; input is ignored, including during startup. */
    public data object Created : TerminalSessionState

    /** Connector startup returned successfully; input is accepted until shutdown begins. */
    public data object Running : TerminalSessionState

    /**
     * Shutdown cleanup and final render publication have been attempted. With
     * the standard core reader, the final frame includes parser EOF output even
     * when synchronized output was enabled. No further input is accepted.
     *
     * @property event immutable metadata describing why the session closed.
     */
    public data class Closed(
        val event: TerminalSessionCloseEvent,
    ) : TerminalSessionState
}

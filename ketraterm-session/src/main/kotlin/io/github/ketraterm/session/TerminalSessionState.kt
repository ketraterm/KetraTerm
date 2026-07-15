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
 * [Closed]. Closing before startup transitions directly from [Created] to
 * [Closed].
 */
sealed interface TerminalSessionState {
    /** The session has been created but its connector has not started. */
    data object Created : TerminalSessionState

    /** The connector has started and the session accepts terminal input. */
    data object Running : TerminalSessionState

    /**
     * The session no longer accepts input.
     *
     * @property event immutable metadata describing why the session closed.
     */
    data class Closed(
        val event: TerminalSessionCloseEvent,
    ) : TerminalSessionState
}

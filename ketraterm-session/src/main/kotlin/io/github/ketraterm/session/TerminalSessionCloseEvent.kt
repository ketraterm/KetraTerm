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
 * Immutable lifecycle event emitted when a [TerminalSession] stops accepting
 * host input.
 *
 * @property exitCode process exit code reported by the transport, or `null`
 *   when the process did not provide one or closure was local.
 * @property failure transport failure that closed the session, or `null` for a
 *   normal remote exit or local close.
 * @property locallyRequested true when application code initiated closure via
 *   [TerminalSession.close].
 */
data class TerminalSessionCloseEvent(
    val exitCode: Int?,
    val failure: Throwable?,
    val locallyRequested: Boolean,
)

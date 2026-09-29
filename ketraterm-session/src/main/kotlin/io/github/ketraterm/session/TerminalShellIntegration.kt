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

import io.github.ketraterm.protocol.ShellIntegrationEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Selected producer of a terminal's shell metadata and active editing context.
 *
 * A session owns observation, not the producer's lifetime. Implementations must
 * not start background jobs during construction. [commandLineChanges] is cold;
 * the session shares its collection and cancels it when no consumer needs it.
 * [state] is the bounded terminal-facing projection of the producer's model.
 * Only that producer may write it; OSC reports never supplement a host model.
 *
 * Host adapters normally use [TerminalShellIntegrationFactory.host] rather than
 * implement this runtime contract. The optional OSC implementation uses the
 * synchronous protocol callbacks below; they run under session serialization.
 */
interface TerminalShellIntegration {
    /** Thread-safe command metadata and primitive viewport projection. */
    val state: TerminalShellIntegrationState

    /** True only while the shell can accept a startup command at its live prompt. */
    val promptReady: StateFlow<Boolean>

    /** Opaque nonnegative edit revisions; unavailable/equal intermediate states may conflate. */
    val commandLineChanges: Flow<Long>

    /** Current immutable editing context, or null when unavailable; must be safe from any thread. */
    fun activeCommandLine(): TerminalShellCommandLineSnapshot?

    /** Optional OSC 133 interpretation, called at the marker's exact output position. */
    fun observeShellMarker(event: ShellIntegrationEvent) = Unit

    /** Optional accepted OSC 7 interpretation. Hosts keep their own directory authority. */
    fun observeWorkingDirectory(uri: String) = Unit

    /** Optional synchronous finalization after a parser batch, before startup submission. */
    fun outputProcessed() = Unit
}

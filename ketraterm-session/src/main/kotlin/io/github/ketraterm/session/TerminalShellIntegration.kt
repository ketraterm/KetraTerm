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
import io.github.ketraterm.render.api.TerminalRenderBufferKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Selected producer of a terminal's shell metadata and active editing context.
 *
 * A session owns observation, not the producer's lifetime. Implementations must
 * not start background jobs during construction. [commandLineChanges] supports
 * independent subscriptions; the session shares its collection and cancels it
 * when no consumer needs it.
 * [state] is the bounded terminal-facing projection of the producer's model.
 * Only that producer may write it; OSC reports never supplement a host model.
 *
 * Host adapters normally use [TerminalShellIntegrationFactory.host] rather than
 * implement this runtime contract. The optional OSC implementation uses the
 * synchronous protocol callbacks below; they run under session serialization.
 */
public interface TerminalShellIntegration {
    /**
     * Whether the host configured this producer to supply prompt markers from startup.
     *
     * Immutable for the producer's lifetime. Consumers may reserve presentation space
     * before the first marker. This is a launch expectation, not live prompt readiness
     * or a guarantee that shell hooks execute. False leaves support unconfirmed;
     * later metadata remains authoritative.
     */
    public val promptMarkersExpected: Boolean get() = false

    /** Thread-safe command metadata and primitive viewport projection. */
    public val state: TerminalShellIntegrationView

    /** True only while the shell can accept a startup command at its live prompt. */
    public val promptReady: StateFlow<Boolean>

    /** Opaque nonnegative edit revisions; unavailable/equal intermediate states may conflate. */
    public val commandLineChanges: Flow<Long>

    /** Current immutable editing context, or null when unavailable; must be safe from any thread. */
    public fun activeCommandLine(): TerminalShellCommandLineSnapshot?

    /**
     * Runs a short conditional-edit action under the producer's revision guard.
     * The revision must change for every authoritative editing update, including
     * changes away from and back to an equal snapshot. The callback must finish
     * before another producer update can become visible.
     *
     * Session calls this with terminal mutation serialized, before acquiring its
     * input admission monitor. Implementations must not acquire terminal state
     * while holding an independent producer guard. Do not invoke external listeners
     * under that guard. Return null without invoking [action] when this atomic
     * capability is unsupported; ordinary [activeCommandLine] reads still work.
     */
    public fun <T> withCommandLine(action: (revision: Long, snapshot: TerminalShellCommandLineSnapshot?) -> T): T? = null

    /** Optional OSC 133 interpretation, called at the marker's exact output position. */
    public fun observeShellMarker(event: ShellIntegrationEvent): Unit = Unit

    /** Optional accepted OSC 7 interpretation. Hosts keep their own directory authority. */
    public fun observeWorkingDirectory(uri: String): Unit = Unit

    /**
     * Reports a local clear after the active screen and history have been erased.
     * Called synchronously under session mutation serialization, before later output.
     * Invalidate producer-owned anchors for [buffer]; preserve metadata for the other buffer.
     * Old line identities cannot resolve after this callback.
     * Host models keep ownership of their text, metadata, and notifications.
     * Do not block on UI work, mutate the session, or close it.
     * Exceptions propagate to the clear caller after the grid has changed.
     */
    public fun bufferCleared(buffer: TerminalRenderBufferKind): Unit = Unit

    /** Optional synchronous finalization after a parser batch, before startup submission. */
    public fun outputProcessed(): Unit = Unit
}

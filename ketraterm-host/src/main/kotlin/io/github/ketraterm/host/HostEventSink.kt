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
package io.github.ketraterm.host

import io.github.ketraterm.protocol.NotificationLevel
import io.github.ketraterm.protocol.ShellIntegrationEvent
import io.github.ketraterm.render.api.TerminalColorPalette

/**
 * Host-facing events emitted while parser commands are mapped to core state.
 *
 * Hosts receive metadata and decide whether host-side actions can be honored.
 * Grid mutation and terminal modes remain owned by core; session coordinates
 * transport changes and terminal-to-host byte responses.
 *
 * Callbacks are synchronous and ordered on the mutation caller's thread. A
 * TerminalSession holds its mutation lock during delivery. Return promptly;
 * do not mutate the session or wait for a UI thread. Schedule UI work using
 * the product's lifecycle. Direct sinks propagate exceptions to the caller;
 * the PTY convenience bridge isolates and reports listener failures.
 * No initial state replay or per-frame delivery is performed.
 */
interface HostEventSink {
    /**
     * Called after the effective palette changes through OSC, host theme update,
     * or hard reset. [palette] is the immutable core-owned value and may be retained.
     * Queries, denied/invalid operations, and equal values do not emit an event.
     */
    fun paletteChanged(palette: TerminalColorPalette) = Unit

    /**
     * Called after a new OSC 8 registry entry is accepted. [hyperlinkId] is a
     * positive session-local numeric identity; [id] is the optional application ID.
     * Reusing an existing entry emits nothing. No browser action is implied.
     */
    fun hyperlinkRegistered(
        hyperlinkId: Int,
        uri: String,
        id: String?,
    ) = Unit

    /**
     * Called after LRU eviction makes [hyperlinkId] unresolvable. Registration of
     * its replacement follows this event. Numeric IDs are never reassigned.
     * Closing OSC 8 or soft reset does not remove existing registry entries.
     */
    fun hyperlinkRemoved(hyperlinkId: Int) = Unit

    /** Called after hard reset clears a nonempty registry, instead of per-ID removals. */
    fun hyperlinksCleared() = Unit

    /**
     * Called when the parser emits BEL.
     */
    fun bell()

    /**
     * Called after the OSC icon title metadata changes.
     *
     * @param title new icon title.
     */
    fun iconTitleChanged(title: String)

    /**
     * Called after the OSC window title metadata changes.
     *
     * @param title new window title.
     */
    fun windowTitleChanged(title: String)

    /**
     * Called after a valid OSC 7 current-working-directory URI is accepted.
     *
     * @param uri absolute `file://` URI exactly as emitted by the shell.
     */
    fun currentWorkingDirectoryChanged(uri: String) = Unit

    /**
     * Called when the shell requests a window resize.
     *
     * @param rows target row count.
     * @param columns target column count.
     */
    fun resizeWindow(
        rows: Int,
        columns: Int,
    )

    /**
     * Accepts an application-requested 80/132-column switch before core changes.
     *
     * Return `true` only when the host can display this grid. TerminalSession
     * synchronizes the connector before the adapter applies DECCOLM's destructive
     * reset. Returning `false` leaves the terminal untouched. A direct adapter
     * embedder must also arrange transport synchronization before returning true.
     *
     * Called synchronously during parsing: do not mutate core or session state,
     * or wait for a UI thread. UI hosts should use published geometry
     * and schedule their window update. The default rejects the request.
     *
     * @param rows unchanged terminal row count.
     * @param columns requested width, either 80 or 132.
     */
    fun requestColumnMode(
        rows: Int,
        columns: Int,
    ): Boolean = false

    /**
     * Called when the shell requests moving the window.
     *
     * @param x target x position in pixels.
     * @param y target y position in pixels.
     */
    fun moveWindow(
        x: Int,
        y: Int,
    ) = Unit

    /**
     * Called when the shell requests minimizing the window.
     */
    fun minimizeWindow() = Unit

    /**
     * Called when the shell requests deminimizing (restoring) the window.
     */
    fun deminimizeWindow() = Unit

    /**
     * Called when the shell requests raising the window.
     */
    fun raiseWindow() = Unit

    /**
     * Called when the shell requests lowering the window.
     */
    fun lowerWindow() = Unit

    /**
     * Called when the shell requests maximizing or restoring the window.
     *
     * @param maximize true to maximize, false to restore.
     */
    fun setMaximized(maximize: Boolean) = Unit

    /**
     * Called when an OSC 133 shell integration marker is received.
     *
     * @param event typed marker event.
     */
    fun shellIntegrationMarker(event: ShellIntegrationEvent) = Unit

    /**
     * Called when the shell requests a desktop notification.
     *
     * @param title notification title (can be empty if not provided).
     * @param body notification body text.
     * @param level notification severity level.
     */
    fun showNotification(
        title: String,
        body: String,
        level: NotificationLevel,
    ) = Unit

    /**
     * Called after an OSC 52 terminal clipboard request is evaluated by host
     * policy.
     *
     * The audit event never contains clipboard payload contents. Allowed write
     * payloads are delivered separately through [terminalClipboardWrite] or
     * [terminalClipboardPrompt] so logs, telemetry, and generic event queues can
     * remain content-free.
     *
     * @param event clipboard request audit record.
     */
    fun terminalClipboardRequest(event: TerminalClipboardAuditEvent) = Unit

    /**
     * Called when an OSC 52 clipboard write request has been allowed by host
     * policy and decoded to text.
     *
     * The adapter never writes to a platform clipboard directly. Product hosts
     * that support OSC 52 writes should perform clipboard I/O from this callback
     * using their own platform or embedding clipboard service. This callback is
     * not invoked for denied requests, prompt-required requests, malformed
     * payloads, oversized payloads, or read/query requests.
     *
     * @param event decoded clipboard write request.
     */
    fun terminalClipboardWrite(event: TerminalClipboardWriteEvent) = Unit

    /**
     * Called when an OSC 52 clipboard write request requires product-host user
     * approval and has been decoded to text.
     *
     * The adapter never prompts or writes to a platform clipboard directly.
     * Product hosts that support prompt-mode OSC 52 writes should ask the user
     * from this callback, then perform clipboard I/O only if the user approves.
     * This callback is not invoked for denied requests, allowed requests,
     * malformed payloads, oversized payloads, or read/query requests.
     *
     * @param event decoded clipboard write prompt request.
     */
    fun terminalClipboardPrompt(event: TerminalClipboardPromptEvent) = Unit

    companion object {
        /**
         * Event sink used when the host does not need metadata callbacks.
         */
        @JvmField
        val NONE: HostEventSink =
            object : HostEventSink {
                override fun bell() = Unit

                override fun iconTitleChanged(title: String) = Unit

                override fun windowTitleChanged(title: String) = Unit

                override fun resizeWindow(
                    rows: Int,
                    columns: Int,
                ) = Unit

                override fun showNotification(
                    title: String,
                    body: String,
                    level: NotificationLevel,
                ) = Unit
            }
    }
}

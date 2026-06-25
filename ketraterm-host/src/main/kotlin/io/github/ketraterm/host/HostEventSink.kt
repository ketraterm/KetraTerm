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

/**
 * Host-facing events emitted while parser commands are mapped to core state.
 *
 * This sink is intentionally metadata-only. Grid mutation, terminal modes, and
 * terminal-to-host byte responses remain owned by the public core APIs.
 */
interface HostEventSink {
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
     * payloads are delivered separately through [terminalClipboardWrite] so
     * logs, telemetry, and generic event queues can remain content-free.
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

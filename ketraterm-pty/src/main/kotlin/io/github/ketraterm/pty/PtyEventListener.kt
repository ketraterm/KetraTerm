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
package io.github.ketraterm.pty

import io.github.ketraterm.host.TerminalClipboardPromptEvent
import io.github.ketraterm.host.TerminalClipboardReadRequest
import io.github.ketraterm.host.TerminalClipboardWriteEvent
import io.github.ketraterm.protocol.NotificationLevel
import io.github.ketraterm.protocol.ShellIntegrationEvent
import io.github.ketraterm.render.api.TerminalColorPalette
import io.github.ketraterm.session.TerminalClipboardReadResult
import io.github.ketraterm.session.TerminalClipboardReader
import io.github.ketraterm.session.TerminalSession

/**
 * Host callbacks for a running PTY-backed terminal session.
 *
 * Implementations should return quickly. Metadata callbacks are delivered from
 * the connector reader thread as parser output is handled, or the caller of a
 * host theme update. Delivery holds the session mutation lock; do not reenter
 * mutation or wait for a UI thread. There is no initial replay. Listener failures
 * are reported through [listenerFailed]. [readClipboard] is an asynchronous
 * operation with the separate threading and failure contract documented below.
 */
@Suppress("UNUSED_PARAMETER")
interface PtyEventListener {
    /** Effective palette change; [palette] is immutable and may be retained. */
    fun paletteChanged(
        session: TerminalSession,
        palette: TerminalColorPalette,
    ) = Unit

    /** New accepted OSC 8 registry entry; see [io.github.ketraterm.host.HostEventSink.hyperlinkRegistered]. */
    fun hyperlinkRegistered(
        session: TerminalSession,
        hyperlinkId: Int,
        uri: String,
        id: String?,
    ) = Unit

    /** An evicted OSC 8 identity is no longer resolvable. */
    fun hyperlinkRemoved(
        session: TerminalSession,
        hyperlinkId: Int,
    ) = Unit

    /** Hard reset cleared a nonempty OSC 8 registry. */
    fun hyperlinksCleared(session: TerminalSession) = Unit

    /**
     * Called when BEL is received from the terminal process.
     *
     * @param session session that received the event.
     */
    fun bell(session: TerminalSession)

    /**
     * Called after the OSC icon title changes.
     *
     * @param session session that received the event.
     * @param title new icon title.
     */
    fun iconTitleChanged(
        session: TerminalSession,
        title: String,
    )

    /**
     * Called after the OSC window title changes.
     *
     * @param session session that received the event.
     * @param title new window title.
     */
    fun windowTitleChanged(
        session: TerminalSession,
        title: String,
    )

    /**
     * Called after a valid OSC 7 current-working-directory URI is accepted.
     *
     * @param session session that received the event.
     * @param uri absolute `file://` URI reported by the shell.
     */
    fun currentWorkingDirectoryChanged(
        session: TerminalSession,
        uri: String,
    ) = Unit

    /**
     * Called when another listener callback throws.
     *
     * This callback is also failure-isolated; exceptions thrown from it are
     * ignored.
     *
     * @param session session whose listener callback failed.
     * @param exception exception thrown by another listener callback.
     */
    fun listenerFailed(
        session: TerminalSession,
        exception: Exception,
    )

    /**
     * Called when the shell requests a window resize.
     *
     * @param session session that received the event.
     * @param rows target row count.
     * @param columns target column count.
     */
    fun resizeWindow(
        session: TerminalSession,
        rows: Int,
        columns: Int,
    )

    /**
     * Accepts a DECCOLM grid change before terminal/transport mutation. Defaults
     * to rejection. See [io.github.ketraterm.host.HostEventSink.requestColumnMode]
     * for the synchronous, nonblocking host acceptance contract.
     */
    fun requestColumnMode(
        session: TerminalSession,
        rows: Int,
        columns: Int,
    ): Boolean = false

    /**
     * Called when the shell requests moving the window.
     *
     * @param session session that received the event.
     * @param x target x position in pixels.
     * @param y target y position in pixels.
     */
    fun moveWindow(
        session: TerminalSession,
        x: Int,
        y: Int,
    ) = Unit

    /**
     * Called when the shell requests minimizing the window.
     *
     * @param session session that received the event.
     */
    fun minimizeWindow(session: TerminalSession) = Unit

    /**
     * Called when the shell requests deminimizing (restoring) the window.
     *
     * @param session session that received the event.
     */
    fun deminimizeWindow(session: TerminalSession) = Unit

    /**
     * Called when the shell requests raising the window.
     *
     * @param session session that received the event.
     */
    fun raiseWindow(session: TerminalSession) = Unit

    /**
     * Called when the shell requests lowering the window.
     *
     * @param session session that received the event.
     */
    fun lowerWindow(session: TerminalSession) = Unit

    /**
     * Called when the shell requests maximizing or restoring the window.
     *
     * @param session session that received the event.
     * @param maximize true to maximize, false to restore.
     */
    fun setMaximized(
        session: TerminalSession,
        maximize: Boolean,
    ) = Unit

    /**
     * Called when an OSC 133 shell integration marker is received.
     *
     * @param session session that received the event.
     * @param event typed marker event.
     */
    fun shellIntegrationMarker(
        session: TerminalSession,
        event: ShellIntegrationEvent,
    ) = Unit

    /**
     * Called when the shell requests a desktop notification.
     *
     * @param session session that received the event.
     * @param title notification title.
     * @param body notification body.
     * @param level notification severity level.
     */
    fun showNotification(
        session: TerminalSession,
        title: String,
        body: String,
        level: NotificationLevel,
    ) = Unit

    /**
     * Called when OSC 52 clipboard write policy permits a decoded text payload.
     *
     * Implementations own platform clipboard access and should avoid retaining
     * [event.text] longer than needed to complete the write.
     *
     * @param session session that received the request.
     * @param event decoded clipboard write request.
     */
    fun terminalClipboardWrite(
        session: TerminalSession,
        event: TerminalClipboardWriteEvent,
    ) = Unit

    /**
     * Called when OSC 52 clipboard write policy requires a product-host prompt.
     *
     * Implementations own user prompting and platform clipboard access. They
     * should avoid retaining [event.text] longer than needed to complete the
     * prompt and possible write.
     *
     * @param session session that received the request.
     * @param event decoded clipboard prompt request.
     */
    fun terminalClipboardPrompt(
        session: TerminalSession,
        event: TerminalClipboardPromptEvent,
    ) = Unit

    /**
     * Resolves a clipboard read for the requesting [session].
     *
     * Runs on the session I/O dispatcher without parser/input locks. Follow
     * [TerminalClipboardReader]'s consent, selection, and cancellation contract;
     * await host readiness and previously posted writes before native access.
     * Cancellation must propagate. Provider exceptions reach the session's
     * content-free read audit, never [listenerFailed], which may log details.
     * Hosts without a read provider explicitly report unavailable data.
     */
    suspend fun readClipboard(
        session: TerminalSession,
        request: TerminalClipboardReadRequest,
    ): TerminalClipboardReadResult = TerminalClipboardReadResult.Unavailable

    companion object {
        /**
         * Listener used when the host does not need PTY callbacks.
         */
        @JvmField
        val NONE: PtyEventListener =
            object : PtyEventListener {
                override fun bell(session: TerminalSession) = Unit

                override fun iconTitleChanged(
                    session: TerminalSession,
                    title: String,
                ) = Unit

                override fun windowTitleChanged(
                    session: TerminalSession,
                    title: String,
                ) = Unit

                override fun resizeWindow(
                    session: TerminalSession,
                    rows: Int,
                    columns: Int,
                ) = Unit

                override fun showNotification(
                    session: TerminalSession,
                    title: String,
                    body: String,
                    level: NotificationLevel,
                ) = Unit

                override fun listenerFailed(
                    session: TerminalSession,
                    exception: Exception,
                ) = Unit
            }
    }
}

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

import io.github.ketraterm.host.HostEventSink
import io.github.ketraterm.host.TerminalClipboardPromptEvent
import io.github.ketraterm.host.TerminalClipboardWriteEvent
import io.github.ketraterm.protocol.NotificationLevel
import io.github.ketraterm.protocol.ShellIntegrationEvent
import io.github.ketraterm.session.TerminalSession

internal class SessionHostEventBridge(
    private val listener: PtyEventListener,
) : HostEventSink {
    private var attachedSession: TerminalSession? = null

    fun attach(session: TerminalSession) {
        check(attachedSession == null) {
            "SessionHostEventBridge already attached"
        }
        attachedSession = session
    }

    override fun bell() {
        safeDispatch { session -> listener.bell(session) }
    }

    override fun iconTitleChanged(title: String) {
        safeDispatch { session -> listener.iconTitleChanged(session, title) }
    }

    override fun windowTitleChanged(title: String) {
        safeDispatch { session -> listener.windowTitleChanged(session, title) }
    }

    override fun currentWorkingDirectoryChanged(uri: String) {
        safeDispatch { session -> listener.currentWorkingDirectoryChanged(session, uri) }
    }

    override fun resizeWindow(
        rows: Int,
        columns: Int,
    ) {
        safeDispatch { session -> listener.resizeWindow(session, rows, columns) }
    }

    override fun moveWindow(
        x: Int,
        y: Int,
    ) {
        safeDispatch { session -> listener.moveWindow(session, x, y) }
    }

    override fun minimizeWindow() {
        safeDispatch { session -> listener.minimizeWindow(session) }
    }

    override fun deminimizeWindow() {
        safeDispatch { session -> listener.deminimizeWindow(session) }
    }

    override fun raiseWindow() {
        safeDispatch { session -> listener.raiseWindow(session) }
    }

    override fun lowerWindow() {
        safeDispatch { session -> listener.lowerWindow(session) }
    }

    override fun setMaximized(maximize: Boolean) {
        safeDispatch { session -> listener.setMaximized(session, maximize) }
    }

    override fun shellIntegrationMarker(event: ShellIntegrationEvent) {
        safeDispatch { session -> listener.shellIntegrationMarker(session, event) }
    }

    override fun showNotification(
        title: String,
        body: String,
        level: NotificationLevel,
    ) {
        safeDispatch { session -> listener.showNotification(session, title, body, level) }
    }

    override fun terminalClipboardWrite(event: TerminalClipboardWriteEvent) {
        safeDispatch { session -> listener.terminalClipboardWrite(session, event) }
    }

    override fun terminalClipboardPrompt(event: TerminalClipboardPromptEvent) {
        safeDispatch { session -> listener.terminalClipboardPrompt(session, event) }
    }

    private inline fun safeDispatch(block: (TerminalSession) -> Unit) {
        val session =
            checkNotNull(attachedSession) {
                "SessionHostEventBridge not attached"
            }

        try {
            block(session)
        } catch (exception: Exception) {
            try {
                listener.listenerFailed(session, exception)
            } catch (_: Exception) {
                // Ignore secondary listener failure.
            }
        }
    }
}

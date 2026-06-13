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
package io.github.jvterm.pty

import io.github.jvterm.host.HostEventSink
import io.github.jvterm.session.TerminalSession

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

    override fun resizeWindow(
        rows: Int,
        columns: Int,
    ) {
        safeDispatch { session -> listener.resizeWindow(session, rows, columns) }
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

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

import io.github.jvterm.session.TerminalSession

/**
 * Host callbacks for a running PTY-backed terminal session.
 *
 * Implementations should return quickly. Metadata callbacks are delivered from
 * the connector reader thread as parser output is handled.
 */
@Suppress("UNUSED_PARAMETER")
interface PtyEventListener {
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

                override fun listenerFailed(
                    session: TerminalSession,
                    exception: Exception,
                ) = Unit
            }
    }
}

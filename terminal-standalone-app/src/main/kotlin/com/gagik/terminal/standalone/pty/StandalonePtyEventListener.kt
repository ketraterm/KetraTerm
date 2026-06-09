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
package com.gagik.terminal.standalone.pty

import com.gagik.terminal.pty.TerminalPtyEventListener
import com.gagik.terminal.session.TerminalSession
import com.gagik.terminal.standalone.ui.LatticeChrome
import javax.swing.JFrame
import javax.swing.SwingUtilities

/**
 * Routes PTY metadata events into standalone Swing chrome.
 */
internal class StandalonePtyEventListener(
    private val frame: JFrame,
) : TerminalPtyEventListener {
    override fun bell(session: TerminalSession) {
        SwingUtilities.invokeLater {
            frame.toolkit.beep()
        }
    }

    override fun iconTitleChanged(
        session: TerminalSession,
        title: String,
    ) = Unit

    override fun windowTitleChanged(
        session: TerminalSession,
        title: String,
    ) {
        SwingUtilities.invokeLater {
            frame.title = title.ifBlank { LatticeChrome.APP_TITLE }
        }
    }

    override fun listenerFailed(
        session: TerminalSession,
        exception: Exception,
    ) = Unit
}

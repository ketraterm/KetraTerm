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
package io.github.jvterm.ssh

import io.github.jvterm.core.TerminalBuffers
import io.github.jvterm.session.TerminalSession

/**
 * Session factories for SSH terminal hosts.
 */
object SshSessions {
    /**
     * Starts an SSH-backed terminal session.
     *
     * The call opens the SSH connection and shell channel before returning.
     * Product UI layers should call it off the UI event thread.
     *
     * @param options SSH connection and terminal options.
     * @return running terminal session.
     */
    @JvmStatic
    fun ssh(options: SshOptions): TerminalSession {
        val connector = SshConnectors.create(options)
        val terminal =
            TerminalBuffers.create(
                width = options.columns,
                height = options.rows,
                maxHistory = options.maxHistory,
            )
        terminal.setTreatAmbiguousAsWide(options.treatAmbiguousAsWide)
        val session =
            TerminalSession.create(
                terminal = terminal,
                connector = connector,
                inputPolicy = options.inputPolicy,
            )
        session.start(options.columns, options.rows)
        return session
    }
}

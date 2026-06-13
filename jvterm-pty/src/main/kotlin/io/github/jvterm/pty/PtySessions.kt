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

import io.github.jvterm.core.TerminalBuffers
import io.github.jvterm.session.TerminalSession
import java.io.IOException

/**
 * Factory for local PTY-backed terminal sessions.
 */
internal object PtySessions {
    /**
     * Starts a PTY process and connects it to parser, core, host, and
     * input encoding components.
     *
     * PTY stdout is consumed on a daemon reader thread and fed to the parser.
     * Parser/core response bytes and UI input events are serialized onto PTY
     * stdin by the returned [TerminalSession].
     *
     * @param options PTY process and terminal dimensions.
     * @return running terminal session.
     * @throws IOException when PTY4J cannot start the process.
     */
    @Throws(IOException::class)
    fun start(options: PtyOptions = PtyOptions()): TerminalSession = start(options, Pty4jTerminalProcessFactory)

    internal fun start(
        options: PtyOptions,
        processFactory: TerminalProcessFactory,
    ): TerminalSession {
        val connector = PtyConnectors.create(options, processFactory)
        val terminal =
            TerminalBuffers.create(
                width = options.columns,
                height = options.rows,
                maxHistory = options.maxHistory,
            )
        terminal.setTreatAmbiguousAsWide(options.treatAmbiguousAsWide)
        val hostEventBridge = SessionHostEventBridge(options.eventListener)
        val session =
            TerminalSession.create(
                terminal = terminal,
                connector = connector,
                hostEvents = hostEventBridge,
                inputPolicy = options.inputPolicy,
            )
        hostEventBridge.attach(session)
        session.start(options.columns, options.rows)
        return session
    }
}

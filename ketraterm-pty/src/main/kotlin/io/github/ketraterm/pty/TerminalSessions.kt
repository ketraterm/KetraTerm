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

import io.github.ketraterm.session.TerminalSession
import java.io.IOException

/**
 * Session factories for local terminal hosts.
 */
object TerminalSessions {
    /**
     * Creates a local PTY process and an attached session without consuming output.
     *
     * Publish the session to its host before calling [TerminalSession.start]
     * with the initial dimensions from [options]. This ensures startup output
     * cannot overtake host registration. The caller owns the returned session
     * and must close it even if publication fails or it is never started.
     * Use [localPty] when no host registration is needed before output delivery.
     *
     * @throws IOException if the process cannot be started.
     */
    @JvmStatic
    @JvmOverloads
    @Throws(IOException::class)
    fun createLocalPty(options: PtyOptions = PtyOptions()): TerminalSession = PtySessions.create(options)

    /**
     * Starts a local PTY and returns the shared transport-neutral session type.
     *
     * @param options options configuration for the PTY session.
     * @return running terminal session.
     * @throws IOException if the process cannot be started.
     */
    @JvmStatic
    @JvmOverloads
    @Throws(IOException::class)
    fun localPty(options: PtyOptions = PtyOptions()): TerminalSession = PtySessions.start(options)
}

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

import io.github.ketraterm.host.HostPolicy
import io.github.ketraterm.input.policy.EnterNewLineModePolicy
import io.github.ketraterm.input.policy.PasteLineEndingPolicy
import io.github.ketraterm.input.policy.TerminalInputPolicy
import io.github.ketraterm.protocol.TerminalCapabilityIdentity
import java.nio.file.Path

/**
 * Configuration for starting a local PTY-backed terminal session.
 *
 * @param command command and arguments passed to the PTY child process.
 * @param environment environment variables for the child process. `TERM` and
 * `COLORTERM` default to the shared terminal capability identity when the
 * caller does not provide them.
 * @param workingDirectory initial process working directory, or `null` to let
 * PTY4J use its platform default.
 * @param columns initial terminal width in cells.
 * @param rows initial terminal height in rows.
 * @param treatAmbiguousAsWide whether East Asian Ambiguous codepoints occupy
 * two cells in the core width policy for future writes.
 * @param inputPolicy host-bound input encoding policy. Local PTY sessions
 * default Return/Enter to CR even when LNM is active, because contemporary PTY
 * line disciplines can otherwise turn DEC CR LF into an extra newline.
 * Unbracketed paste line endings are likewise canonicalized to CR so every
 * pasted boundary has the same input semantics as Enter.
 * @param maxHistory maximum scrollback lines retained by the core buffer.
 * @param readBufferSize buffer size used by the PTY stdout reader thread.
 * @param readerThreadName name for the daemon PTY stdout reader thread.
 * @param watcherThreadName name for the daemon process exit watcher thread.
 * @param eventListener host callbacks for parser-discovered PTY metadata
 * events such as BEL and title changes.
 * @param hostPolicy safety policy for terminal-triggered host actions.
 */
data class PtyOptions
    @JvmOverloads
    constructor(
        val command: List<String> = defaultCommand(),
        val environment: Map<String, String> = defaultEnvironment(),
        val workingDirectory: Path? = Path.of(System.getProperty("user.home")),
        val columns: Int = 80,
        val rows: Int = 24,
        val treatAmbiguousAsWide: Boolean = false,
        val inputPolicy: TerminalInputPolicy = defaultInputPolicy(),
        val maxHistory: Int = 1000,
        val readBufferSize: Int = 8192,
        val readerThreadName: String = "terminal-pty-reader",
        val watcherThreadName: String = "terminal-pty-watcher",
        val eventListener: PtyEventListener = PtyEventListener.NONE,
        val hostPolicy: HostPolicy = HostPolicy(),
    ) {
        init {
            require(command.isNotEmpty()) { "PTY command must not be empty" }
            require(command.none { it.isEmpty() }) { "PTY command elements must not be empty" }
            require(columns > 0) { "PTY columns must be positive, got $columns" }
            require(rows > 0) { "PTY rows must be positive, got $rows" }
            require(maxHistory >= 0) { "PTY maxHistory must be >= 0, got $maxHistory" }
            require(readBufferSize > 0) { "PTY readBufferSize must be positive, got $readBufferSize" }
            require(readerThreadName.isNotBlank()) { "PTY readerThreadName must not be blank" }
            require(watcherThreadName.isNotBlank()) { "PTY watcherThreadName must not be blank" }
        }

        companion object {
            /**
             * Returns the platform default interactive shell command.
             *
             * @return default interactive shell command.
             */
            @JvmStatic
            fun defaultCommand(): List<String> {
                val osName = System.getProperty("os.name").lowercase()
                if (osName.contains("windows")) {
                    val comspec = System.getenv("COMSPEC")
                    return listOf(if (comspec.isNullOrBlank()) "cmd.exe" else comspec)
                }
                val shell = System.getenv("SHELL")
                return listOf(if (shell.isNullOrBlank()) "/bin/sh" else shell, "-l")
            }

            /**
             * Returns a process environment suitable for contemporary shells and TUIs.
             *
             * @return default process environment variables.
             */
            @JvmStatic
            fun defaultEnvironment(): Map<String, String> {
                val env = LinkedHashMap(System.getenv())
                env["TERM"] = TerminalCapabilityIdentity.TERM_NAME
                env["COLORTERM"] = TerminalCapabilityIdentity.COLOR_TERM_TRUECOLOR
                return env
            }

            /**
             * Returns the default input policy for local PTY-backed sessions.
             *
             * @return default terminal input policy.
             */
            @JvmStatic
            fun defaultInputPolicy(): TerminalInputPolicy =
                TerminalInputPolicy(
                    enterNewLineModePolicy = EnterNewLineModePolicy.SEND_CR,
                    pasteLineEndingPolicy = PasteLineEndingPolicy.CARRIAGE_RETURN,
                )
        }
    }

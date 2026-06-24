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

import io.github.jvterm.input.policy.TerminalInputPolicy

/**
 * Configuration for an SSH-backed terminal session.
 *
 * @property host remote SSH server host name or address.
 * @property username SSH username.
 * @property port remote SSH port.
 * @property authentication ordered authentication credentials to offer.
 * @property hostKeyPolicy server host-key verification policy.
 * @property terminalType PTY terminal type sent to the server.
 * @property environment environment variables requested for the shell channel.
 * @property columns initial terminal columns.
 * @property rows initial terminal rows.
 * @property maxHistory retained scrollback line count in core.
 * @property connectTimeoutMillis TCP/SSH connect timeout in milliseconds.
 * @property authTimeoutMillis authentication timeout in milliseconds.
 * @property openTimeoutMillis shell channel open timeout in milliseconds.
 * @property inputPolicy terminal input encoding policy.
 * @property treatAmbiguousAsWide core width policy for East Asian Ambiguous codepoints.
 */
data class SshOptions(
    val host: String,
    val username: String,
    val port: Int = DEFAULT_PORT,
    val authentication: List<SshAuthentication> = emptyList(),
    val hostKeyPolicy: SshHostKeyPolicy = SshHostKeyPolicy.rejectAll(),
    val terminalType: String = DEFAULT_TERMINAL_TYPE,
    val environment: Map<String, String> = emptyMap(),
    val columns: Int = DEFAULT_COLUMNS,
    val rows: Int = DEFAULT_ROWS,
    val maxHistory: Int = DEFAULT_MAX_HISTORY,
    val connectTimeoutMillis: Long = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    val authTimeoutMillis: Long = DEFAULT_AUTH_TIMEOUT_MILLIS,
    val openTimeoutMillis: Long = DEFAULT_OPEN_TIMEOUT_MILLIS,
    val inputPolicy: TerminalInputPolicy = TerminalInputPolicy(),
    val treatAmbiguousAsWide: Boolean = false,
) {
    init {
        require(host.isNotBlank()) { "host must not be blank" }
        require(username.isNotBlank()) { "username must not be blank" }
        require(port in 1..65535) { "port must be in 1..65535, got $port" }
        require(terminalType.isNotBlank()) { "terminalType must not be blank" }
        require(environment.keys.none { it.isBlank() }) { "environment keys must not be blank" }
        require(columns > 0) { "columns must be positive, got $columns" }
        require(rows > 0) { "rows must be positive, got $rows" }
        require(maxHistory >= 0) { "maxHistory must be non-negative, got $maxHistory" }
        require(connectTimeoutMillis > 0) { "connectTimeoutMillis must be positive, got $connectTimeoutMillis" }
        require(authTimeoutMillis > 0) { "authTimeoutMillis must be positive, got $authTimeoutMillis" }
        require(openTimeoutMillis > 0) { "openTimeoutMillis must be positive, got $openTimeoutMillis" }
    }

    companion object {
        /** Standard SSH port. */
        const val DEFAULT_PORT: Int = 22

        /** Default terminal type requested from remote shells. */
        const val DEFAULT_TERMINAL_TYPE: String = "xterm-256color"

        /** Default terminal width. */
        const val DEFAULT_COLUMNS: Int = 80

        /** Default terminal height. */
        const val DEFAULT_ROWS: Int = 24

        /** Default retained scrollback line count. */
        const val DEFAULT_MAX_HISTORY: Int = 1000

        /** Default connect timeout. */
        const val DEFAULT_CONNECT_TIMEOUT_MILLIS: Long = 15_000L

        /** Default authentication timeout. */
        const val DEFAULT_AUTH_TIMEOUT_MILLIS: Long = 15_000L

        /** Default shell channel open timeout. */
        const val DEFAULT_OPEN_TIMEOUT_MILLIS: Long = 10_000L
    }
}

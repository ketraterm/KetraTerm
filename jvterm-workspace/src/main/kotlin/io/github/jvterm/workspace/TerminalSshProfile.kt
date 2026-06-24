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
package io.github.jvterm.workspace

import java.nio.file.Path

/**
 * Host-neutral SSH terminal launch profile.
 *
 * This model intentionally stores endpoint and trust-source metadata only.
 * Passwords, private-key passphrases, and ephemeral keyboard-interactive
 * answers must be collected by product UI at connection time and never written
 * into workspace configuration.
 *
 * @property id stable profile identifier.
 * @property displayName user-facing profile name.
 * @property host remote SSH server host name or address.
 * @property username SSH username.
 * @property port remote SSH port.
 * @property terminalType PTY terminal type requested from the server.
 * @property knownHostsPath OpenSSH `known_hosts` file used for strict host-key
 * verification, or `null` to use the product default trust store.
 */
data class TerminalSshProfile(
    val id: String,
    val displayName: String,
    val host: String,
    val username: String,
    val port: Int = DEFAULT_PORT,
    val terminalType: String = DEFAULT_TERMINAL_TYPE,
    val knownHostsPath: Path? = null,
) {
    init {
        require(id.isNotBlank()) { "ssh profile id must not be blank" }
        require(displayName.isNotBlank()) { "ssh profile displayName must not be blank" }
        require(host.isNotBlank()) { "ssh profile host must not be blank" }
        require(username.isNotBlank()) { "ssh profile username must not be blank" }
        require(port in 1..65535) { "ssh profile port must be in 1..65535, was $port" }
        require(terminalType.isNotBlank()) { "ssh profile terminalType must not be blank" }
    }

    companion object {
        /** Standard SSH port. */
        const val DEFAULT_PORT: Int = 22

        /** Default terminal type requested from remote shells. */
        const val DEFAULT_TERMINAL_TYPE: String = "xterm-256color"
    }
}

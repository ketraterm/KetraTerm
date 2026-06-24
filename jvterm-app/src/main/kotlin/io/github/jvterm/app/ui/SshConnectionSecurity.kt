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
package io.github.jvterm.app.ui

import io.github.jvterm.ssh.SshAuthentication
import io.github.jvterm.ssh.SshHostKeyPolicy
import io.github.jvterm.workspace.TerminalSshProfile
import java.nio.file.Files
import java.nio.file.Path
import java.util.Arrays

internal object SshConnectionSecurity {
    fun passwordAuthentication(password: CharArray): List<SshAuthentication>? {
        try {
            if (password.isEmpty()) return null
            return listOf(SshAuthentication.Password(String(password)))
        } finally {
            Arrays.fill(password, '\u0000')
        }
    }

    fun defaultKnownHostsPath(): Path = Path.of(System.getProperty("user.home"), ".ssh", "known_hosts")

    fun hostKeyPolicyFor(profile: TerminalSshProfile): SshHostKeyPolicy? {
        if (profile.knownHostsPath != null) return null
        val defaultPath = defaultKnownHostsPath()
        return if (Files.isRegularFile(defaultPath)) SshHostKeyPolicy.knownHosts(defaultPath) else null
    }

    fun trustSourceText(profile: TerminalSshProfile): String =
        when {
            profile.knownHostsPath != null -> "Host key verification: ${profile.knownHostsPath}"
            Files.isRegularFile(defaultKnownHostsPath()) -> "Host key verification: ${defaultKnownHostsPath()}"
            else -> "Host key verification: no known_hosts file found; connection will be rejected."
        }
}

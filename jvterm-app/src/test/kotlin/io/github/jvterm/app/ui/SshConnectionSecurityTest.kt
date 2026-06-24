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
import io.github.jvterm.workspace.TerminalSshProfile
import java.nio.file.Files
import java.util.Comparator
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SshConnectionSecurityTest {
    private val originalUserHome = System.getProperty("user.home")

    @AfterTest
    fun restoreUserHome() {
        System.setProperty("user.home", originalUserHome)
    }

    @Test
    fun passwordAuthenticationClearsCallerBuffer() {
        val password = charArrayOf('s', 'e', 'c', 'r', 'e', 't')

        val authentication = SshConnectionSecurity.passwordAuthentication(password)

        val credential = authentication?.single() as SshAuthentication.Password
        assertEquals("secret", credential.password)
        assertTrue(password.all { it == '\u0000' })
    }

    @Test
    fun passwordAuthenticationRejectsEmptyPassword() {
        val password = CharArray(0)

        assertNull(SshConnectionSecurity.passwordAuthentication(password))
    }

    @Test
    fun hostKeyPolicyUsesDefaultKnownHostsOnlyWhenProfileHasNoTrustSource() {
        val tempHome = Files.createTempDirectory("jvterm-ssh-home")
        try {
            System.setProperty("user.home", tempHome.toString())
            Files.createDirectories(tempHome.resolve(".ssh"))
            Files.writeString(tempHome.resolve(".ssh").resolve("known_hosts"), "")

            val profile =
                TerminalSshProfile(
                    id = "prod",
                    displayName = "Production",
                    host = "prod.example.com",
                    username = "deploy",
                )
            val profileWithKnownHosts = profile.copy(knownHostsPath = tempHome.resolve("profile_known_hosts"))

            assertNotNull(SshConnectionSecurity.hostKeyPolicyFor(profile))
            assertNull(SshConnectionSecurity.hostKeyPolicyFor(profileWithKnownHosts))
        } finally {
            Files.walk(tempHome).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }
}

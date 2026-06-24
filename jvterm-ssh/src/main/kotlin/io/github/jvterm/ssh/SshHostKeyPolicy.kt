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

import org.apache.sshd.client.keyverifier.DefaultKnownHostsServerKeyVerifier
import org.apache.sshd.client.keyverifier.RejectAllServerKeyVerifier
import org.apache.sshd.client.keyverifier.RequiredServerKeyVerifier
import org.apache.sshd.client.keyverifier.ServerKeyVerifier
import java.nio.file.Path
import java.security.PublicKey

/**
 * SSH server host-key verification policy.
 *
 * The default policy is [rejectAll], so callers must explicitly opt into a
 * known-hosts file or pinned key before a connection can authenticate.
 */
sealed class SshHostKeyPolicy private constructor() {
    internal abstract fun createVerifier(): ServerKeyVerifier

    private data object RejectAll : SshHostKeyPolicy() {
        override fun createVerifier(): ServerKeyVerifier = RejectAllServerKeyVerifier.INSTANCE
    }

    private class KnownHosts(
        private val path: Path,
    ) : SshHostKeyPolicy() {
        override fun createVerifier(): ServerKeyVerifier =
            DefaultKnownHostsServerKeyVerifier(RejectAllServerKeyVerifier.INSTANCE, true, path)
    }

    private class PinnedKey(
        private val key: PublicKey,
    ) : SshHostKeyPolicy() {
        override fun createVerifier(): ServerKeyVerifier = RequiredServerKeyVerifier(key)
    }

    companion object {
        /**
         * Rejects every server key.
         *
         * This is the secure default for APIs that have not been given a trust
         * source yet.
         */
        @JvmStatic
        fun rejectAll(): SshHostKeyPolicy = RejectAll

        /**
         * Verifies server keys against an OpenSSH `known_hosts` file.
         *
         * Unknown and changed host keys are rejected.
         *
         * @param path path to a known-hosts file.
         * @return strict known-hosts verification policy.
         */
        @JvmStatic
        fun knownHosts(path: Path): SshHostKeyPolicy = KnownHosts(path)

        /**
         * Accepts exactly one pinned public host key.
         *
         * @param key required server public key.
         * @return pinned-key verification policy.
         */
        @JvmStatic
        fun pinnedKey(key: PublicKey): SshHostKeyPolicy = PinnedKey(key)
    }
}

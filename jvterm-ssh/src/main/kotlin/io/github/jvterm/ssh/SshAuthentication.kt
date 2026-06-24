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

import java.security.KeyPair

/**
 * Authentication material offered to an SSH server.
 *
 * Implementations are immutable wrappers around caller-provided credentials.
 * Callers remain responsible for credential lifetime and storage policy.
 */
sealed interface SshAuthentication {
    /**
     * Password authentication credential.
     *
     * @property password password text. The SSH library requires a `String`,
     * so callers should avoid retaining additional copies outside this object.
     */
    data class Password(
        val password: String,
    ) : SshAuthentication {
        init {
            require(password.isNotEmpty()) { "password must not be empty" }
        }
    }

    /**
     * Public-key authentication credential.
     *
     * @property keyPair private/public key pair used for SSH public-key auth.
     */
    data class PublicKey(
        val keyPair: KeyPair,
    ) : SshAuthentication
}

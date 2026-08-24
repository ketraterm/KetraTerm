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
package io.github.ketraterm.completion.api

import io.github.ketraterm.completion.model.TerminalCommandCompletionStatsSnapshot

/**
 * Host-facing privacy policy for persisted completion-learning data.
 *
 * The completion engine records only data supplied by its host and performs no
 * disk I/O. Hosts must apply this policy before persistent learning or storage
 * of exact command text and must sanitize snapshots again at the storage boundary.
 */
object TerminalCompletionPersistencePolicy {
    /**
     * Returns whether [command] may be learned persistently or written to disk.
     *
     * @param command full command line captured by an authoritative host integration.
     * @return `true` when [command] is safe enough for local persisted learning.
     */
    fun allowsCommand(command: String): Boolean {
        if (command.isBlank() || command.indexOf('\n') >= 0 || command.indexOf('\r') >= 0) return false
        if (command.startsWith(' ') || command.startsWith('\t')) return false
        return SENSITIVE_KEYWORDS.none { command.contains(it, ignoreCase = true) }
    }

    /**
     * Returns a snapshot safe enough for local persisted completion learning.
     *
     * Every exact-command row is checked because it contains raw command text.
     *
     * @param snapshot completion-learning snapshot at a persistence boundary.
     * @return sanitized immutable snapshot.
     */
    fun sanitizeSnapshot(snapshot: TerminalCommandCompletionStatsSnapshot): TerminalCommandCompletionStatsSnapshot {
        val retained = snapshot.commandStats.filter { allowsCommand(it.commandLine) }
        return if (retained.size == snapshot.commandStats.size) {
            snapshot
        } else {
            TerminalCommandCompletionStatsSnapshot(retained)
        }
    }

    private val SENSITIVE_KEYWORDS =
        listOf(
            "password",
            "passwd",
            "secret",
            "token",
            "apikey",
            "api_key",
            "private_key",
            "access_key",
            "secret_key",
            "bearer",
            "authorization",
            "credential",
            "credentials",
            "passphrase",
            "passcode",
            "jwt",
            "key=",
            "_key",
            "key_",
            "-key",
            "--key",
            "key ",
            "auth ",
            "auth=",
        )
}

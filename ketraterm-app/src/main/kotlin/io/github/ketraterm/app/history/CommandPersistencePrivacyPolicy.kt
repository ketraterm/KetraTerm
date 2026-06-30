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
package io.github.ketraterm.app.history

import io.github.ketraterm.app.history.CommandPersistencePrivacyPolicy.allowsCommand
import io.github.ketraterm.completion.TerminalCommandCompletionStats
import io.github.ketraterm.completion.TerminalCommandShapeStats

/**
 * Host-owned privacy gate for persisted command learning data.
 *
 * The completion module intentionally stays pure and records whatever a host
 * feeds it. The standalone app applies this policy before exact command text or
 * structural command shapes are persisted, preserving shell ignorespace
 * behavior and avoiding common credential-bearing commands.
 */
internal object CommandPersistencePrivacyPolicy {
    /**
     * Returns whether [command] may be learned or written to disk.
     *
     * Commands starting with spaces or tabs are treated as private, matching the
     * common shell `HISTCONTROL=ignorespace` convention. Credential-looking
     * commands are also rejected because Base64 storage is not encryption.
     *
     * @param command full command line captured from shell integration or popup feedback.
     * @return `true` when the command is safe enough for local persisted learning.
     */
    fun allowsCommand(command: String): Boolean {
        if (command.isBlank() || command.indexOf('\n') >= 0 || command.indexOf('\r') >= 0) return false
        if (command.startsWith(" ") || command.startsWith("\t")) return false
        return !containsSensitiveKeyword(command)
    }

    /**
     * Returns whether an exact command stats row may be persisted.
     *
     * @param record aggregate exact command stats row.
     * @return `true` when the exact command line passes [allowsCommand].
     */
    fun allowsCommandStats(record: TerminalCommandCompletionStats): Boolean = allowsCommand(record.commandLine)

    /**
     * Returns whether a structural shape stats row may be persisted.
     *
     * Shape rows should not contain raw positional argument values, but known
     * executables, subcommands, and option names can still reveal credential
     * intent. This defensive check rejects rows whose public shape vocabulary
     * trips the same sensitive-keyword policy.
     *
     * @param record aggregate command-shape stats row.
     * @return `true` when the shape metadata is safe enough to persist.
     */
    fun allowsShapeStats(record: TerminalCommandShapeStats): Boolean =
        !containsSensitiveKeyword(record.shape.executable) &&
            record.shape.subcommands.none(::containsSensitiveKeyword) &&
            record.shape.optionNames.none(::containsSensitiveKeyword) &&
            !containsSensitiveKeyword(record.shape.normalizedShapeKey)

    private fun containsSensitiveKeyword(text: String): Boolean {
        for (keyword in SENSITIVE_KEYWORDS) {
            if (text.contains(keyword, ignoreCase = true)) return true
        }
        return false
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

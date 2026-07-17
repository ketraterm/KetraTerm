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
package io.github.ketraterm.completion.history

import io.github.ketraterm.completion.model.TerminalCommandCompletionStats
import io.github.ketraterm.completion.model.TerminalCommandShapeStats
import io.github.ketraterm.completion.model.TerminalCompletionPersistenceDecision
import io.github.ketraterm.completion.model.TerminalCompletionPersistenceDecisionLocation.*

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
    fun allowsCommand(command: String): Boolean = evaluateCommand(command).isAllowed

    /**
     * Evaluates whether [command] may be learned or written to disk.
     *
     * @param command full command line captured from shell integration or popup feedback.
     * @return auditable privacy decision explaining allow/reject outcome.
     */
    fun evaluateCommand(command: String): TerminalCompletionPersistenceDecision {
        if (command.isBlank() || command.indexOf('\n') >= 0 || command.indexOf('\r') >= 0) {
            return TerminalCompletionPersistenceDecision.BLANK_OR_MULTILINE
        }
        if (command.startsWith(" ") || command.startsWith("\t")) {
            return TerminalCompletionPersistenceDecision.IGNORES_SPACE
        }
        val keyword = findSensitiveKeyword(command)
        return if (keyword == null) {
            TerminalCompletionPersistenceDecision.ALLOWED
        } else {
            TerminalCompletionPersistenceDecision.sensitiveKeyword(keyword, COMMAND_TEXT)
        }
    }

    /**
     * Returns whether an exact command stats row may be persisted.
     *
     * @param record aggregate exact command stats row.
     * @return `true` when the exact command line passes [allowsCommand].
     */
    fun allowsCommandStats(record: TerminalCommandCompletionStats): Boolean = evaluateCommandStats(record).isAllowed

    /**
     * Evaluates whether an exact command stats row may be persisted.
     *
     * @param record aggregate exact command stats row.
     * @return auditable privacy decision for [record].
     */
    fun evaluateCommandStats(record: TerminalCommandCompletionStats): TerminalCompletionPersistenceDecision =
        evaluateCommand(record.commandLine)

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
    fun allowsShapeStats(record: TerminalCommandShapeStats): Boolean = evaluateShapeStats(record).isAllowed

    /**
     * Evaluates whether a structural shape stats row may be persisted.
     *
     * @param record aggregate command-shape stats row.
     * @return auditable privacy decision for [record].
     */
    fun evaluateShapeStats(record: TerminalCommandShapeStats): TerminalCompletionPersistenceDecision {
        findSensitiveKeyword(record.shape.executable)?.let {
            return TerminalCompletionPersistenceDecision.sensitiveKeyword(it, SHAPE_EXECUTABLE)
        }
        for (subcommand in record.shape.subcommands) {
            findSensitiveKeyword(subcommand)?.let {
                return TerminalCompletionPersistenceDecision.sensitiveKeyword(it, SHAPE_SUBCOMMAND)
            }
        }
        for (optionName in record.shape.optionNames) {
            findSensitiveKeyword(optionName)?.let {
                return TerminalCompletionPersistenceDecision.sensitiveKeyword(it, SHAPE_OPTION_NAME)
            }
        }
        return TerminalCompletionPersistenceDecision.ALLOWED
    }

    private fun findSensitiveKeyword(text: String): String? {
        for (keyword in SENSITIVE_KEYWORDS) {
            if (text.contains(keyword, ignoreCase = true)) return keyword
        }
        return null
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

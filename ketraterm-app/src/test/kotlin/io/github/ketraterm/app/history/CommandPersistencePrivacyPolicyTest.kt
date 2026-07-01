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

import io.github.ketraterm.completion.TerminalCommandCompletionStats
import io.github.ketraterm.completion.TerminalCommandLineShape
import io.github.ketraterm.completion.TerminalCommandShapeStats
import kotlin.test.*

class CommandPersistencePrivacyPolicyTest {
    @Test
    fun `allows safe command text`() {
        val decision = CommandPersistencePrivacyPolicy.evaluateCommand("git status")

        assertEquals(CommandPersistencePrivacyDecision.ALLOWED, decision)
        assertTrue(decision.isAllowed)
        assertTrue(CommandPersistencePrivacyPolicy.allowsCommand("git status"))
    }

    @Test
    fun `rejects blank and multiline command text`() {
        assertEquals(
            CommandPersistencePrivacyDecision.BLANK_OR_MULTILINE,
            CommandPersistencePrivacyPolicy.evaluateCommand("   "),
        )
        assertEquals(
            CommandPersistencePrivacyDecision.BLANK_OR_MULTILINE,
            CommandPersistencePrivacyPolicy.evaluateCommand("git status\ngit log"),
        )
        assertFalse(CommandPersistencePrivacyPolicy.allowsCommand("git status\rgit log"))
    }

    @Test
    fun `rejects leading space and tab with ignorespace reason`() {
        assertEquals(CommandPersistencePrivacyDecision.IGNORES_SPACE, CommandPersistencePrivacyPolicy.evaluateCommand(" git status"))
        assertEquals(CommandPersistencePrivacyDecision.IGNORES_SPACE, CommandPersistencePrivacyPolicy.evaluateCommand("\tgit status"))
    }

    @Test
    fun `rejects sensitive command keyword with matched text`() {
        val decision = CommandPersistencePrivacyPolicy.evaluateCommand("docker login --password hunter2")

        assertEquals(CommandPersistencePrivacyDecisionKind.SENSITIVE_KEYWORD, decision.kind)
        assertEquals("password", decision.matchedText)
        assertFalse(decision.isAllowed)
    }

    @Test
    fun `rejects blank sensitive decision matched text`() {
        assertFailsWith<IllegalArgumentException> {
            CommandPersistencePrivacyDecision.sensitiveKeyword(" ")
        }
    }

    @Test
    fun `evaluates exact command stats with command text decision`() {
        val decision = CommandPersistencePrivacyPolicy.evaluateCommandStats(commandStats(" export TOKEN=123"))

        assertEquals(CommandPersistencePrivacyDecision.IGNORES_SPACE, decision)
        assertFalse(CommandPersistencePrivacyPolicy.allowsCommandStats(commandStats("docker login --password hunter2")))
    }

    @Test
    fun `allows safe shape stats`() {
        val decision = CommandPersistencePrivacyPolicy.evaluateShapeStats(shapeStats("git status"))

        assertEquals(CommandPersistencePrivacyDecision.ALLOWED, decision)
        assertTrue(CommandPersistencePrivacyPolicy.allowsShapeStats(shapeStats("git status")))
    }

    @Test
    fun `rejects sensitive shape executable`() {
        val decision = CommandPersistencePrivacyPolicy.evaluateShapeStats(shapeStats("secret-tool list"))

        assertEquals(CommandPersistencePrivacyDecisionKind.SENSITIVE_KEYWORD, decision.kind)
        assertEquals("secret", decision.matchedText)
    }

    @Test
    fun `rejects sensitive shape subcommand`() {
        val decision = CommandPersistencePrivacyPolicy.evaluateShapeStats(shapeStats("git secret list"))

        assertEquals(CommandPersistencePrivacyDecisionKind.SENSITIVE_KEYWORD, decision.kind)
        assertEquals("secret", decision.matchedText)
    }

    @Test
    fun `rejects sensitive shape option`() {
        val decision = CommandPersistencePrivacyPolicy.evaluateShapeStats(shapeStats("curl --authorization bearer"))

        assertEquals(CommandPersistencePrivacyDecisionKind.SENSITIVE_KEYWORD, decision.kind)
        assertEquals("authorization", decision.matchedText)
        assertFalse(CommandPersistencePrivacyPolicy.allowsShapeStats(shapeStats("curl --authorization bearer")))
    }

    @Test
    fun `rejects sensitive normalized shape key`() {
        val shape =
            TerminalCommandLineShape(
                executable = "git",
                subcommands = emptyList(),
                optionNames = emptyList(),
                positionalArgumentCount = 0,
                optionValueCount = 0,
                normalizedShapeKey = "git|secret",
            )

        val decision = CommandPersistencePrivacyPolicy.evaluateShapeStats(TerminalCommandShapeStats(shape = shape))

        assertEquals(CommandPersistencePrivacyDecisionKind.SENSITIVE_KEYWORD, decision.kind)
        assertEquals("secret", decision.matchedText)
    }

    private fun commandStats(commandLine: String): TerminalCommandCompletionStats =
        TerminalCommandCompletionStats(
            commandLine = commandLine,
            normalizedCommandLine = TerminalCommandCompletionStats.normalizeCommandLine(commandLine),
            useCount = 1,
            successCount = 1,
            lastUsedEpochMillis = 100,
        )

    private fun shapeStats(commandLine: String): TerminalCommandShapeStats =
        TerminalCommandShapeStats(
            shape = TerminalCommandLineShape.fromCommandLine(commandLine)!!,
            useCount = 1,
            successCount = 1,
            lastUsedEpochMillis = 100,
        )
}

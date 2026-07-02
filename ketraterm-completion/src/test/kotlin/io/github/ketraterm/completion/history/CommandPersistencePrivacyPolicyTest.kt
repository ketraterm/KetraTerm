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
import io.github.ketraterm.completion.model.TerminalCommandLineShape
import io.github.ketraterm.completion.model.TerminalCommandShapeStats
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
        val blankDecision = CommandPersistencePrivacyPolicy.evaluateCommand("   ")
        val multilineDecision = CommandPersistencePrivacyPolicy.evaluateCommand("git status\ngit log")

        assertEquals(CommandPersistencePrivacyDecision.BLANK_OR_MULTILINE, blankDecision)
        assertEquals(CommandPersistencePrivacyDecisionLocation.COMMAND_TEXT, blankDecision.location)
        assertEquals(CommandPersistencePrivacyDecision.BLANK_OR_MULTILINE, multilineDecision)
        assertEquals(CommandPersistencePrivacyDecisionLocation.COMMAND_TEXT, multilineDecision.location)
        assertFalse(CommandPersistencePrivacyPolicy.allowsCommand("git status\rgit log"))
    }

    @Test
    fun `rejects leading space and tab with ignorespace reason`() {
        val leadingSpaceDecision = CommandPersistencePrivacyPolicy.evaluateCommand(" git status")
        val leadingTabDecision = CommandPersistencePrivacyPolicy.evaluateCommand("\tgit status")

        assertEquals(CommandPersistencePrivacyDecision.IGNORES_SPACE, leadingSpaceDecision)
        assertEquals(CommandPersistencePrivacyDecisionLocation.COMMAND_TEXT, leadingSpaceDecision.location)
        assertEquals(CommandPersistencePrivacyDecision.IGNORES_SPACE, leadingTabDecision)
        assertEquals(CommandPersistencePrivacyDecisionLocation.COMMAND_TEXT, leadingTabDecision.location)
    }

    @Test
    fun `rejects sensitive command keyword with matched text`() {
        val decision = CommandPersistencePrivacyPolicy.evaluateCommand("docker login --password hunter2")

        assertEquals(CommandPersistencePrivacyDecisionKind.SENSITIVE_KEYWORD, decision.kind)
        assertEquals("password", decision.matchedText)
        assertEquals(CommandPersistencePrivacyDecisionLocation.COMMAND_TEXT, decision.location)
        assertFalse(decision.isAllowed)
    }

    @Test
    fun `rejects blank sensitive decision matched text`() {
        assertFailsWith<IllegalArgumentException> {
            CommandPersistencePrivacyDecision.sensitiveKeyword(" ")
        }
    }

    @Test
    fun `rejects sensitive decision without location`() {
        assertFailsWith<IllegalArgumentException> {
            CommandPersistencePrivacyDecision(
                kind = CommandPersistencePrivacyDecisionKind.SENSITIVE_KEYWORD,
                matchedText = "token",
            )
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
        assertEquals(CommandPersistencePrivacyDecisionLocation.SHAPE_EXECUTABLE, decision.location)
    }

    @Test
    fun `rejects sensitive shape subcommand`() {
        val decision = CommandPersistencePrivacyPolicy.evaluateShapeStats(shapeStats("git secret list"))

        assertEquals(CommandPersistencePrivacyDecisionKind.SENSITIVE_KEYWORD, decision.kind)
        assertEquals("secret", decision.matchedText)
        assertEquals(CommandPersistencePrivacyDecisionLocation.SHAPE_SUBCOMMAND, decision.location)
    }

    @Test
    fun `rejects sensitive shape option`() {
        val decision = CommandPersistencePrivacyPolicy.evaluateShapeStats(shapeStats("curl --authorization bearer"))

        assertEquals(CommandPersistencePrivacyDecisionKind.SENSITIVE_KEYWORD, decision.kind)
        assertEquals("authorization", decision.matchedText)
        assertEquals(CommandPersistencePrivacyDecisionLocation.SHAPE_OPTION_NAME, decision.location)
        assertFalse(CommandPersistencePrivacyPolicy.allowsShapeStats(shapeStats("curl --authorization bearer")))
    }

    private fun commandStats(commandLine: String): TerminalCommandCompletionStats =
        TerminalCommandCompletionStats(
            commandLine = commandLine,
            useCount = 1,
            successCount = 1,
            lastUsedEpochMillis = 100,
        )

    private fun shapeStats(commandLine: String): TerminalCommandShapeStats =
        TerminalCommandShapeStats(
            shape =
                when (commandLine) {
                    "git status" ->
                        TerminalCommandLineShape(
                            executable = "git",
                            subcommands = listOf("status"),
                        )
                    "secret-tool list" ->
                        TerminalCommandLineShape(
                            executable = "secret-tool",
                            subcommands = listOf("list"),
                        )
                    "git secret list" ->
                        TerminalCommandLineShape(
                            executable = "git",
                            subcommands = listOf("secret", "list"),
                        )
                    "curl --authorization bearer" ->
                        TerminalCommandLineShape(
                            executable = "curl",
                            optionNames = listOf("--authorization"),
                            optionValueCount = 1,
                        )
                    else -> error("Unsupported test command: $commandLine")
                },
            useCount = 1,
            successCount = 1,
            lastUsedEpochMillis = 100,
        )
}

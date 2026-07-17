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

import io.github.ketraterm.completion.api.TerminalCompletionPersistencePolicy
import io.github.ketraterm.completion.model.*
import kotlin.test.*

class CommandPersistencePrivacyPolicyTest {
    @Test
    fun `allows safe command text`() {
        val decision = TerminalCompletionPersistencePolicy.evaluateCommand("git status")

        assertEquals(TerminalCompletionPersistenceDecision.ALLOWED, decision)
        assertTrue(decision.isAllowed)
        assertTrue(TerminalCompletionPersistencePolicy.allowsCommand("git status"))
    }

    @Test
    fun `rejects blank and multiline command text`() {
        val blankDecision = TerminalCompletionPersistencePolicy.evaluateCommand("   ")
        val multilineDecision = TerminalCompletionPersistencePolicy.evaluateCommand("git status\ngit log")

        assertEquals(TerminalCompletionPersistenceDecision.BLANK_OR_MULTILINE, blankDecision)
        assertEquals(TerminalCompletionPersistenceDecisionLocation.COMMAND_TEXT, blankDecision.location)
        assertEquals(TerminalCompletionPersistenceDecision.BLANK_OR_MULTILINE, multilineDecision)
        assertEquals(TerminalCompletionPersistenceDecisionLocation.COMMAND_TEXT, multilineDecision.location)
        assertFalse(TerminalCompletionPersistencePolicy.allowsCommand("git status\rgit log"))
    }

    @Test
    fun `rejects leading space and tab with ignorespace reason`() {
        val leadingSpaceDecision = TerminalCompletionPersistencePolicy.evaluateCommand(" git status")
        val leadingTabDecision = TerminalCompletionPersistencePolicy.evaluateCommand("\tgit status")

        assertEquals(TerminalCompletionPersistenceDecision.IGNORES_SPACE, leadingSpaceDecision)
        assertEquals(TerminalCompletionPersistenceDecisionLocation.COMMAND_TEXT, leadingSpaceDecision.location)
        assertEquals(TerminalCompletionPersistenceDecision.IGNORES_SPACE, leadingTabDecision)
        assertEquals(TerminalCompletionPersistenceDecisionLocation.COMMAND_TEXT, leadingTabDecision.location)
    }

    @Test
    fun `rejects sensitive command keyword with matched text`() {
        val decision = TerminalCompletionPersistencePolicy.evaluateCommand("docker login --password hunter2")

        assertEquals(TerminalCompletionPersistenceDecisionKind.SENSITIVE_KEYWORD, decision.kind)
        assertEquals("password", decision.matchedText)
        assertEquals(TerminalCompletionPersistenceDecisionLocation.COMMAND_TEXT, decision.location)
        assertFalse(decision.isAllowed)
    }

    @Test
    fun `rejects blank sensitive decision matched text`() {
        assertFailsWith<IllegalArgumentException> {
            TerminalCompletionPersistenceDecision.sensitiveKeyword(" ")
        }
    }

    @Test
    fun `rejects sensitive decision without location`() {
        assertFailsWith<IllegalArgumentException> {
            TerminalCompletionPersistenceDecision(
                kind = TerminalCompletionPersistenceDecisionKind.SENSITIVE_KEYWORD,
                matchedText = "token",
            )
        }
    }

    @Test
    fun `evaluates exact command stats with command text decision`() {
        val decision = TerminalCompletionPersistencePolicy.evaluateCommandStats(commandStats(" export TOKEN=123"))

        assertEquals(TerminalCompletionPersistenceDecision.IGNORES_SPACE, decision)
        assertFalse(TerminalCompletionPersistencePolicy.allowsCommandStats(commandStats("docker login --password hunter2")))
    }

    @Test
    fun `allows safe shape stats`() {
        val decision = TerminalCompletionPersistencePolicy.evaluateShapeStats(shapeStats("git status"))

        assertEquals(TerminalCompletionPersistenceDecision.ALLOWED, decision)
        assertTrue(TerminalCompletionPersistencePolicy.allowsShapeStats(shapeStats("git status")))
    }

    @Test
    fun `rejects sensitive shape executable`() {
        val decision = TerminalCompletionPersistencePolicy.evaluateShapeStats(shapeStats("secret-tool list"))

        assertEquals(TerminalCompletionPersistenceDecisionKind.SENSITIVE_KEYWORD, decision.kind)
        assertEquals("secret", decision.matchedText)
        assertEquals(TerminalCompletionPersistenceDecisionLocation.SHAPE_EXECUTABLE, decision.location)
    }

    @Test
    fun `rejects sensitive shape subcommand`() {
        val decision = TerminalCompletionPersistencePolicy.evaluateShapeStats(shapeStats("git secret list"))

        assertEquals(TerminalCompletionPersistenceDecisionKind.SENSITIVE_KEYWORD, decision.kind)
        assertEquals("secret", decision.matchedText)
        assertEquals(TerminalCompletionPersistenceDecisionLocation.SHAPE_SUBCOMMAND, decision.location)
    }

    @Test
    fun `rejects sensitive shape option`() {
        val decision = TerminalCompletionPersistencePolicy.evaluateShapeStats(shapeStats("curl --authorization bearer"))

        assertEquals(TerminalCompletionPersistenceDecisionKind.SENSITIVE_KEYWORD, decision.kind)
        assertEquals("authorization", decision.matchedText)
        assertEquals(TerminalCompletionPersistenceDecisionLocation.SHAPE_OPTION_NAME, decision.location)
        assertFalse(TerminalCompletionPersistencePolicy.allowsShapeStats(shapeStats("curl --authorization bearer")))
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

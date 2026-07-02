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
package io.github.ketraterm.completion.commandline

import io.github.ketraterm.completion.model.TerminalCommandSpecs
import kotlin.test.*

class TerminalCommandLineClassifierTest {
    @Test
    fun `classifies git checkout branch as known command family with private positional argument`() {
        val classification = classify("git checkout main")

        assertEquals("git", classification.shape.executable)
        assertEquals(listOf("checkout"), classification.shape.subcommands)
        assertEquals(1, classification.shape.positionalArgumentCount)
        assertEquals(listOf(TerminalCommandArgumentShape(TerminalCommandArgumentKind.POSITIONAL)), classification.arguments)
        assertFalse(classification.shape.normalizedShapeKey.contains("main", ignoreCase = true))
    }

    @Test
    fun `classifies known subcommand options without creating arguments`() {
        val classification = classify("git status --short")

        assertEquals("git", classification.shape.executable)
        assertEquals(listOf("status"), classification.shape.subcommands)
        assertEquals(listOf("--short"), classification.shape.optionNames)
        assertEquals(0, classification.shape.positionalArgumentCount)
        assertEquals(0, classification.shape.optionValueCount)
        assertEquals(emptyList(), classification.arguments)
    }

    @Test
    fun `classifies npm run script and option terminator values as private arguments`() {
        val classification = classify("NODE_ENV=test npm run build -- --watch")

        assertEquals("npm", classification.shape.executable)
        assertEquals(listOf("run"), classification.shape.subcommands)
        assertEquals(emptyList(), classification.shape.optionNames)
        assertEquals(2, classification.shape.positionalArgumentCount)
        assertEquals(
            listOf(
                TerminalCommandArgumentShape(TerminalCommandArgumentKind.POSITIONAL),
                TerminalCommandArgumentShape(TerminalCommandArgumentKind.OPTION_TERMINATED_POSITIONAL),
            ),
            classification.arguments,
        )
        assertFalse(classification.shape.normalizedShapeKey.contains("build", ignoreCase = true))
        assertFalse(classification.shape.normalizedShapeKey.contains("watch", ignoreCase = true))
    }

    @Test
    fun `classifies nested docker compose subcommands from specs`() {
        val classification = classify("docker compose up")

        assertEquals("docker", classification.shape.executable)
        assertEquals(listOf("compose", "up"), classification.shape.subcommands)
        assertEquals(0, classification.shape.positionalArgumentCount)
        assertEquals(emptyList(), classification.arguments)
    }

    @Test
    fun `classifies option terminator after known command as private positional`() {
        val classification = classify("git status -- --short")

        assertEquals(listOf("status"), classification.shape.subcommands)
        assertEquals(emptyList(), classification.shape.optionNames)
        assertEquals(1, classification.shape.positionalArgumentCount)
        assertEquals(
            listOf(TerminalCommandArgumentShape(TerminalCommandArgumentKind.OPTION_TERMINATED_POSITIONAL)),
            classification.arguments,
        )
        assertFalse(classification.shape.normalizedShapeKey.contains("--short", ignoreCase = true))
    }

    @Test
    fun `classifies quoted arguments without retaining their text`() {
        val classification = classify("git checkout \"feature branch\"")

        assertEquals(listOf("checkout"), classification.shape.subcommands)
        assertEquals(1, classification.shape.positionalArgumentCount)
        assertEquals(listOf(TerminalCommandArgumentShape(TerminalCommandArgumentKind.POSITIONAL)), classification.arguments)
        assertFalse(classification.shape.normalizedShapeKey.contains("feature", ignoreCase = true))
        assertFalse(classification.shape.normalizedShapeKey.contains("branch", ignoreCase = true))
    }

    @Test
    fun `keeps accepting subcommands after root option values`() {
        val classification = classify("git -C repo status")

        assertEquals(listOf("status"), classification.shape.subcommands)
        assertEquals(listOf("-c"), classification.shape.optionNames)
        assertEquals(1, classification.shape.optionValueCount)
        assertEquals(0, classification.shape.positionalArgumentCount)
        assertEquals(
            listOf(TerminalCommandArgumentShape(TerminalCommandArgumentKind.OPTION_VALUE, "-c")),
            classification.arguments,
        )
        assertFalse(classification.shape.normalizedShapeKey.contains("repo", ignoreCase = true))
    }

    @Test
    fun `falls back to generic private shape for unknown executable`() {
        val classification = classify("custom-tool deploy prod")

        assertEquals(false, classification.matchedSpec)
        assertEquals("custom-tool", classification.shape.executable)
        assertEquals(listOf("deploy"), classification.shape.subcommands)
        assertEquals(1, classification.shape.positionalArgumentCount)
        assertFalse(classification.shape.normalizedShapeKey.contains("prod", ignoreCase = true))
    }

    @Test
    fun `rejects blank multiline and assignment only commands`() {
        assertNull(TerminalCommandLineClassifier.classify("   ", TerminalCommandSpecs.defaults()))
        assertNull(TerminalCommandLineClassifier.classify("git status\ngit log", TerminalCommandSpecs.defaults()))
        assertNull(TerminalCommandLineClassifier.classify("FOO=bar", TerminalCommandSpecs.defaults()))
    }

    private fun classify(commandLine: String): TerminalCommandLineClassification =
        assertNotNull(TerminalCommandLineClassifier.classify(commandLine, TerminalCommandSpecs.defaults()))
}

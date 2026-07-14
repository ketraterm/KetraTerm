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
package io.github.ketraterm.testkit

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/** Curated cross-implementation checks against the pinned headless xterm.js oracle. */
class XtermDifferentialOracleTest {
    @Test
    fun `agrees on cursor positioning wrapping and wide cluster cells`() {
        assertAgreement(
            columns = 6,
            rows = 3,
            bytes = "A中B\u001B[2;2He\u0301XYZW".encodeToByteArray(),
        )
    }

    @Test
    fun `agrees on the public mode title and response intersection`() {
        assertAgreement(
            columns = 8,
            rows = 3,
            bytes =
                (
                    "\u001B[?1;6;7;66;1000;1004;2004h" +
                        "\u001B[4h" +
                        "\u001B]2;differential-title\u0007" +
                        "\u001B[2;3H\u001B[6n"
                ).encodeToByteArray(),
        )
    }

    @Test
    fun `agrees when entering and leaving the alternate buffer`() {
        assertAgreement(
            columns = 7,
            rows = 3,
            bytes = "primary\u001B[?1049hALT\u001B[2;2HX\u001B[?1049l".encodeToByteArray(),
        )
    }

    @Test
    fun `oracle result is invariant across every bounded parser split`() {
        val bytes = "e\u0301中\u001B[31mX\u001B[2;1HY".encodeToByteArray()
        val variants = TerminalReplayChunkings.exhaustive(bytes)
        val reference = oracle.replay(COLUMNS, ROWS, MAX_HISTORY, variants.first().transcript)
        for (variant in variants.drop(1)) {
            val actual = oracle.replay(COLUMNS, ROWS, MAX_HISTORY, variant.transcript)
            assertTrue(actual == reference, "xterm.js chunking=${variant.name} produced a different snapshot")
        }
    }

    private fun assertAgreement(
        columns: Int,
        rows: Int,
        bytes: ByteArray,
    ) {
        val transcript =
            TerminalReplayTranscript.of(
                TerminalReplayEvent.Input(bytes),
                TerminalReplayEvent.EndOfInput,
            )
        val ketraTerm = TerminalConformanceHarness(columns, rows, MAX_HISTORY).replay(transcript)
        val independent = oracle.replay(columns, rows, MAX_HISTORY, transcript)
        val result = TerminalDifferentialComparator.compare(ketraTerm, independent)
        assertTrue(result.isEmpty, result.format())
    }

    companion object {
        private const val COLUMNS = 8
        private const val ROWS = 3
        private const val MAX_HISTORY = 8
        private lateinit var oracle: TerminalDifferentialOracle

        @JvmStatic
        @BeforeAll
        fun configureOracle() {
            val required = System.getProperty("ketraterm.xtermOracle.required") == "true"
            val script = System.getProperty("ketraterm.xtermOracle.script")?.let(Path::of)
            assumeTrue(required && script != null && Files.isRegularFile(script)) {
                "run :ketraterm-testkit:xtermDifferentialTest to install and execute the pinned xterm.js oracle"
            }
            oracle =
                TerminalProcessOracle(
                    command = listOf(System.getProperty("ketraterm.xtermOracle.node", "node"), script.toString()),
                    workingDirectory = Path.of(System.getProperty("ketraterm.xtermOracle.workingDirectory")),
                )
        }
    }
}

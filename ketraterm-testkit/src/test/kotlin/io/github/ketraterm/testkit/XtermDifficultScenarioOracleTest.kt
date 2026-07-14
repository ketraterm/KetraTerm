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
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Stream

/** Broad difficult-scenario corpus checked against the pinned xterm.js implementation. */
class XtermDifficultScenarioOracleTest {
    @TestFactory
    fun `curated difficult scenarios agree or match an exact declared policy difference`(): Stream<DynamicTest> =
        cases()
            .map { case ->
                DynamicTest.dynamicTest("${case.category} - ${case.name}") {
                    val transcript = TerminalReplayTranscript(case.events + TerminalReplayEvent.EndOfInput)
                    val ketraTerm = TerminalConformanceHarness(case.columns, case.rows, case.maxHistory).replay(transcript)
                    val independent = oracle.replay(case.columns, case.rows, case.maxHistory, transcript)
                    val result = TerminalDifferentialComparator.compare(ketraTerm, independent, MAX_DIFFERENCES)
                    val expectedDifference = case.expectedDifference
                    if (expectedDifference == null) {
                        assertTrue(result.isEmpty, "case=${case.category}/${case.name}\n${result.format()}")
                    } else {
                        val actualPaths = result.differences.mapTo(linkedSetOf()) { it.path }
                        assertTrue(
                            !result.truncated && actualPaths == expectedDifference.paths,
                            "case=${case.category}/${case.name}\npolicy=${expectedDifference.reason}\n${result.format()}",
                        )
                    }
                }
            }.stream()

    private fun cases(): List<DifferentialCase> =
        listOf(
            textCase("controls", "carriage return overwrites from column zero", "abc\rX"),
            textCase("controls", "backspace clamps and overwrites", "A\b\bZ"),
            textCase("controls", "line feed preserves column", "AB\nC"),
            textCase("controls", "CR LF starts at next row", "AB\r\nC"),
            textCase("controls", "default tab stops advance every eight columns", "A\tB", columns = 18),
            textCase("controls", "tab clear removes the current stop", "\u001B[1;9H\u001B[g\r\tX", columns = 18),
            textCase("cursor", "CUP defaults omitted coordinates to one", "abc\u001B[;HXY"),
            textCase("cursor", "relative movement clamps on every edge", "\u001B[99B\u001B[99C\u001B[99A\u001B[99DZ"),
            textCase("cursor", "CHA and VPA address independently", "\u001B[4G\u001B[3dX"),
            textCase("cursor", "DEC save and restore preserves position", "A\u001B7\u001B[3;4HX\u001B8B"),
            textCase(
                "cursor",
                "ANSI save and restore is intentionally not aliased over DECSLRM",
                "A\u001B[s\u001B[3;4HX\u001B[uB",
                expectedDifference =
                    ExpectedDifference(
                        reason = "KetraTerm reserves CSI s for DECSLRM; conditional ANSI SCP compatibility is not implemented.",
                        paths =
                            setOf(
                                "cursor.column",
                                "cursor.row",
                                "retainedRows[0].cells[1].text",
                                "retainedRows[2].cells[4].text",
                            ),
                    ),
            ),
            textCase("editing", "ICH shifts cells right", "ABCDE\u001B[1;3H\u001B[2@X"),
            textCase("editing", "DCH shifts cells left", "ABCDE\u001B[1;2H\u001B[2P"),
            textCase("editing", "ECH erases without moving the cursor", "ABCDE\u001B[1;2H\u001B[2X"),
            textCase("editing", "EL zero erases through the right edge", "ABCDE\u001B[1;3H\u001B[K"),
            textCase("editing", "EL one erases through the cursor", "ABCDE\u001B[1;3H\u001B[1K"),
            textCase("editing", "EL two erases the complete row", "ABCDE\u001B[2K"),
            textCase("editing", "ED zero erases below the cursor", "111\r\n222\r\n333\u001B[2;2H\u001B[J"),
            textCase("editing", "ED one erases above the cursor", "111\r\n222\r\n333\u001B[2;2H\u001B[1J"),
            textCase("editing", "ED two clears the viewport", "111\r\n222\u001B[2J"),
            textCase(
                "scrolling",
                "insert lines stays inside the scroll region",
                "111\r\n222\r\n333\r\n444\u001B[2;4r\u001B[2;1H\u001B[LX",
                rows = 4,
            ),
            textCase(
                "scrolling",
                "delete lines stays inside the scroll region",
                "111\r\n222\r\n333\r\n444\u001B[2;4r\u001B[2;1H\u001B[M",
                rows = 4,
            ),
            textCase("scrolling", "SU scrolls only the active region", "111\r\n222\r\n333\r\n444\u001B[2;4r\u001B[S", rows = 4),
            textCase("scrolling", "SD scrolls only the active region", "111\r\n222\r\n333\r\n444\u001B[2;4r\u001B[T", rows = 4),
            textCase("scrolling", "reverse index scrolls down at top margin", "111\r\n222\r\n333\u001B[2;3r\u001B[2;1H\u001BMZ", rows = 3),
            textCase("margins", "origin mode makes CUP margin relative", "top\r\nmid\r\nbot\u001B[2;3r\u001B[?6h\u001B[1;1HX", rows = 4),
            textCase("wrapping", "pending wrap advances before the next printable", "12345X", columns = 5),
            textCase("wrapping", "carriage return cancels pending wrap", "12345\rX", columns = 5),
            textCase("wrapping", "disabled autowrap overwrites the right edge", "\u001B[?7l123456X", columns = 5),
            textCase("unicode", "combining sequence occupies one cell", "Ae\u0301B"),
            textCase("unicode", "wide CJK scalar preserves its trailing cell", "A中B"),
            textCase("unicode", "wide scalar wraps intact at the right edge", "1234中X", columns = 5),
            byteCase(
                "unicode",
                "malformed UTF-8 emits replacement before CSI",
                byteArrayOf(
                    'A'.code.toByte(),
                    0xE2.toByte(),
                    0x1B,
                    '['.code.toByte(),
                    '2'.code.toByte(),
                    'B'.code.toByte(),
                    'Z'.code.toByte(),
                ),
                expectedDifference =
                    ExpectedDifference(
                        reason = "KetraTerm deliberately emits U+FFFD for the incomplete sequence; xterm.js drops it.",
                        paths =
                            setOf(
                                "cursor.column",
                                "retainedRows[0].cells[1].text",
                                "retainedRows[2].cells[1].text",
                                "retainedRows[2].cells[2].text",
                            ),
                    ),
            ),
            textCase("SGR", "basic styles and resets are cell durable", "\u001B[1;2;3;4;5;7;8;9;53mA\u001B[0mB"),
            textCase("SGR", "ANSI palette colors normalize identically", "\u001B[31;44mA\u001B[91;104mB"),
            textCase("SGR", "indexed colors preserve exact palette indexes", "\u001B[38;5;196;48;5;17mX"),
            textCase("SGR", "RGB colors preserve exact channels", "\u001B[38;2;1;127;255;48;2;254;3;128mX"),
            textCase("SGR", "selective style resets preserve unrelated styles", "\u001B[1;2;3;4;5;7;8;9mA\u001B[22;23;24;25;27;28;29mB"),
            textCase(
                "modes",
                "public mode intersection resets independently",
                "\u001B[?1;6;7;1000;1004;2004h\u001B[4hX\u001B[?1;6;7;1000;1004;2004l\u001B[4l",
            ),
            textCase("alternate", "1049 restores primary content and cursor", "main\u001B[?1049hALT\u001B[3;3HX\u001B[?1049lZ"),
            textCase("reset", "RIS clears grid modes and cursor", "abc\u001B[?1;6;7;1004;2004h\u001BcZ"),
            textCase("responses", "primary device status report uses one-based coordinates", "\u001B[2;4H\u001B[6n"),
            textCase("metadata", "OSC title accepts BEL terminator", "\u001B]2;bel-title\u0007X"),
            textCase("metadata", "OSC title accepts ST terminator", "\u001B]2;st-title\u001B\\X"),
            DifferentialCase(
                category = "resize",
                name = "simple narrowing reflows wrapped content",
                columns = 8,
                rows = 3,
                events =
                    listOf(
                        TerminalReplayEvent.Input.utf8("abcdefghijkl"),
                        TerminalReplayEvent.Resize(5, 3),
                    ),
                expectedDifference =
                    ExpectedDifference(
                        reason = "KetraTerm reflows logical lines; the headless xterm.js resize retains physical rows.",
                        paths =
                            setOf(
                                "cursor.column",
                                "cursor.row",
                                "retainedRows[1].wrapped",
                                "retainedRows[1].cells[0].text",
                                "retainedRows[1].cells[1].text",
                                "retainedRows[1].cells[2].text",
                                "retainedRows[1].cells[3].text",
                                "retainedRows[1].cells[4].text",
                                "retainedRows[2].cells[0].text",
                                "retainedRows[2].cells[1].text",
                            ),
                    ),
            ),
            DifferentialCase(
                category = "resize",
                name = "widening rejoins soft wrapped content",
                columns = 5,
                rows = 3,
                events =
                    listOf(
                        TerminalReplayEvent.Input.utf8("abcdefghijkl"),
                        TerminalReplayEvent.Resize(8, 3),
                    ),
                expectedDifference =
                    ExpectedDifference(
                        reason = "KetraTerm rejoins soft-wrapped logical lines; headless xterm.js retains physical rows.",
                        paths =
                            setOf(
                                "cursor.column",
                                "cursor.row",
                                "retainedRows[0].cells[5].text",
                                "retainedRows[0].cells[6].text",
                                "retainedRows[0].cells[7].text",
                                "retainedRows[1].wrapped",
                                "retainedRows[1].cells[0].text",
                                "retainedRows[1].cells[1].text",
                                "retainedRows[1].cells[2].text",
                                "retainedRows[1].cells[3].text",
                                "retainedRows[1].cells[4].text",
                                "retainedRows[2].cells[0].text",
                                "retainedRows[2].cells[1].text",
                            ),
                    ),
            ),
        )

    private fun textCase(
        category: String,
        name: String,
        text: String,
        columns: Int = 8,
        rows: Int = 4,
        expectedDifference: ExpectedDifference? = null,
    ) = DifferentialCase(
        category,
        name,
        columns,
        rows,
        events = listOf(TerminalReplayEvent.Input.utf8(text)),
        expectedDifference = expectedDifference,
    )

    private fun byteCase(
        category: String,
        name: String,
        bytes: ByteArray,
        expectedDifference: ExpectedDifference? = null,
    ) = DifferentialCase(
        category,
        name,
        events = listOf(TerminalReplayEvent.Input(bytes)),
        expectedDifference = expectedDifference,
    )

    private data class ExpectedDifference(
        val reason: String,
        val paths: Set<String>,
    )

    private data class DifferentialCase(
        val category: String,
        val name: String,
        val columns: Int = 8,
        val rows: Int = 4,
        val maxHistory: Int = 8,
        val events: List<TerminalReplayEvent>,
        val expectedDifference: ExpectedDifference? = null,
    )

    companion object {
        private const val MAX_DIFFERENCES = 64
        private lateinit var oracle: TerminalDifferentialOracle

        @JvmStatic
        @BeforeAll
        fun configureOracle() {
            val required = System.getProperty("ketraterm.xtermOracle.required") == "true"
            val script = System.getProperty("ketraterm.xtermOracle.script")?.let(Path::of)
            assumeTrue(required && script != null && Files.isRegularFile(script)) {
                "run :ketraterm-testkit:xtermDifferentialTest to execute the pinned xterm.js corpus"
            }
            oracle =
                TerminalProcessOracle(
                    command = listOf(System.getProperty("ketraterm.xtermOracle.node", "node"), script.toString()),
                    workingDirectory = Path.of(System.getProperty("ketraterm.xtermOracle.workingDirectory")),
                )
        }
    }
}

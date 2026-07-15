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

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.ketraterm.render.api.TerminalRenderCellFlags
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.SplittableRandom
import kotlin.collections.ArrayDeque
import kotlin.collections.ArrayList
import kotlin.collections.List
import kotlin.collections.filterIndexed
import kotlin.collections.forEach
import kotlin.collections.forEachIndexed
import kotlin.collections.isNotEmpty
import kotlin.collections.map
import kotlin.collections.plus
import kotlin.collections.plusAssign

/** Deterministic model-based campaign for cursor movement, deferred wrap, and scrolling. */
class TerminalCursorWrapModelCampaignTest {
    @Test
    fun `generated operation streams agree with independent cursor wrap model`() {
        assumeTrue(System.getProperty("ketraterm.cursorWrap.required") == "true") {
            "run a generated cursor/wrap Gradle profile"
        }
        val configuration = Configuration.fromSystemProperties()
        for (localIndex in 0 until configuration.caseCount) {
            val globalIndex = Math.addExact(configuration.startIndex, localIndex.toLong())
            val scenario = ScenarioGenerator.generate(Math.addExact(BASE_SEED, globalIndex))
            val failure = runCatching { verify(scenario) }.exceptionOrNull() ?: continue
            val minimized = ScenarioShrinker.shrink(scenario, ::fails)
            val minimizedFailure = runCatching { verify(minimized) }.exceptionOrNull() ?: failure
            val artifact = ArtifactWriter.writeFailure(configuration, globalIndex, scenario, minimized, minimizedFailure)
            fail<Unit>(
                "cursor/wrap model failure seed=${scenario.seed} globalCase=$globalIndex " +
                    "localCase=$localIndex/${configuration.caseCount}\nartifact=$artifact",
                failure,
            )
        }
        ArtifactWriter.writeCampaignReport(configuration)
    }

    private fun fails(scenario: Scenario): Boolean = runCatching { verify(scenario) }.isFailure

    private fun verify(scenario: Scenario) {
        val model = CursorWrapModel(scenario.columns, scenario.rows, MAX_HISTORY)
        scenario.operations.forEach(model::apply)
        val snapshot =
            TerminalConformanceHarness(scenario.columns, scenario.rows, MAX_HISTORY).replay(
                TerminalReplayTranscript(
                    scenario.operations.map { TerminalReplayEvent.Input.utf8(it.bytes) } + TerminalReplayEvent.EndOfInput,
                ),
            )
        check(snapshot.cursor.column == model.column && snapshot.cursor.row == model.row) {
            "cursor differs: expected=${model.column},${model.row}, actual=${snapshot.cursor.column},${snapshot.cursor.row}"
        }
        check(snapshot.modes.autoWrap == model.autoWrap) {
            "autowrap differs: expected=${model.autoWrap}, actual=${snapshot.modes.autoWrap}"
        }
        check(snapshot.retainedRows.size == model.retainedRows.size) {
            "retained row count differs: expected=${model.retainedRows.size}, actual=${snapshot.retainedRows.size}"
        }
        model.retainedRows.forEachIndexed { rowIndex, expected ->
            val actual = snapshot.retainedRows[rowIndex]
            check(actual.wrapped == expected.wrapped) {
                "wrapped differs at row $rowIndex: expected=${expected.wrapped}, actual=${actual.wrapped}"
            }
            actual.cells.forEachIndexed { column, cell ->
                val actualCodepoint =
                    if (cell.flags and TerminalRenderCellFlags.CODEPOINT != 0) cell.codepoint else 0
                check(actualCodepoint == expected.cells[column]) {
                    "cell differs at $rowIndex,$column: expected=${expected.cells[column]}, actual=$actualCodepoint"
                }
            }
        }
    }

    private data class Scenario(
        val seed: Long,
        val columns: Int,
        val rows: Int,
        val operations: List<Operation>,
    )

    private sealed interface Operation {
        val bytes: String

        data class Print(
            val character: Char,
        ) : Operation {
            override val bytes: String = character.toString()
        }

        data object CarriageReturn : Operation {
            override val bytes = "\r"
        }

        data object LineFeed : Operation {
            override val bytes = "\n"
        }

        data object Backspace : Operation {
            override val bytes = "\b"
        }

        data class CursorPosition(
            val row: Int,
            val column: Int,
        ) : Operation {
            override val bytes = "\u001B[$row;${column}H"
        }

        data class CursorForward(
            val count: Int,
        ) : Operation {
            override val bytes = "\u001B[${count}C"
        }

        data class CursorBackward(
            val count: Int,
        ) : Operation {
            override val bytes = "\u001B[${count}D"
        }

        data class SetAutoWrap(
            val enabled: Boolean,
        ) : Operation {
            override val bytes = if (enabled) "\u001B[?7h" else "\u001B[?7l"
        }
    }

    private object ScenarioGenerator {
        fun generate(seed: Long): Scenario {
            val random = SplittableRandom(seed)
            val columns = random.nextInt(3, 11)
            val rows = random.nextInt(2, 7)
            val operations = ArrayList<Operation>()
            repeat(random.nextInt(30, 121)) {
                operations +=
                    when (random.nextInt(100)) {
                        in 0..54 -> Operation.Print(('A'.code + random.nextInt(26)).toChar())
                        in 55..62 -> Operation.CarriageReturn
                        in 63..70 -> Operation.LineFeed
                        in 71..76 -> Operation.Backspace
                        in 77..84 -> Operation.CursorPosition(random.nextInt(1, rows + 1), random.nextInt(1, columns + 1))
                        in 85..89 -> Operation.CursorForward(random.nextInt(1, columns + 2))
                        in 90..94 -> Operation.CursorBackward(random.nextInt(1, columns + 2))
                        else -> Operation.SetAutoWrap(random.nextBoolean())
                    }
            }
            return Scenario(seed, columns, rows, operations)
        }
    }

    private class CursorWrapModel(
        private val columns: Int,
        private val rows: Int,
        private val maxHistory: Int,
    ) {
        data class Row(
            val cells: IntArray,
            var wrapped: Boolean = false,
        )

        private val history = ArrayDeque<Row>()
        private val screen = ArrayList<Row>(rows).apply { repeat(rows) { add(blankRow()) } }
        var column = 0
            private set
        var row = 0
            private set
        var autoWrap = true
            private set
        private var pendingWrap = false

        val retainedRows: List<Row> get() = history + screen

        fun apply(operation: Operation) {
            when (operation) {
                is Operation.Print -> print(operation.character.code)
                Operation.CarriageReturn -> {
                    column = 0
                    pendingWrap = false
                }
                Operation.LineFeed -> {
                    pendingWrap = false
                    screen[row].wrapped = false
                    lineFeed()
                }
                Operation.Backspace -> {
                    column = (column - 1).coerceAtLeast(0)
                    pendingWrap = false
                }
                is Operation.CursorPosition -> {
                    row = (operation.row - 1).coerceIn(0, rows - 1)
                    column = (operation.column - 1).coerceIn(0, columns - 1)
                    pendingWrap = false
                }
                is Operation.CursorForward -> {
                    column = (column + operation.count).coerceAtMost(columns - 1)
                    pendingWrap = false
                }
                is Operation.CursorBackward -> {
                    column = (column - operation.count).coerceAtLeast(0)
                    pendingWrap = false
                }
                is Operation.SetAutoWrap -> {
                    autoWrap = operation.enabled
                    pendingWrap = false
                }
            }
        }

        private fun print(codepoint: Int) {
            if (pendingWrap) {
                screen[row].wrapped = true
                column = 0
                lineFeed()
                pendingWrap = false
            }
            screen[row].cells[column] = codepoint
            if (column == columns - 1) {
                pendingWrap = autoWrap
            } else {
                column++
            }
        }

        private fun lineFeed() {
            if (row < rows - 1) {
                row++
                return
            }
            history.addLast(screen.removeAt(0))
            if (history.size > maxHistory) history.removeFirst()
            screen.add(blankRow())
        }

        private fun blankRow() = Row(IntArray(columns))
    }

    private object ScenarioShrinker {
        fun shrink(
            original: Scenario,
            fails: (Scenario) -> Boolean,
        ): Scenario {
            var operations = original.operations
            var granularity = 2
            while (operations.size >= 2) {
                val chunkSize = (operations.size + granularity - 1) / granularity
                var reduced = false
                var start = 0
                while (start < operations.size) {
                    val end = minOf(operations.size, start + chunkSize)
                    val candidate = operations.filterIndexed { index, _ -> index !in start until end }
                    if (candidate.isNotEmpty() && fails(original.copy(operations = candidate))) {
                        operations = candidate
                        granularity = (granularity - 1).coerceAtLeast(2)
                        reduced = true
                        break
                    }
                    start = end
                }
                if (!reduced) {
                    if (granularity >= operations.size) break
                    granularity = minOf(operations.size, granularity * 2)
                }
            }
            return original.copy(operations = operations)
        }
    }

    private object ArtifactWriter {
        fun writeCampaignReport(configuration: Configuration): Path {
            Files.createDirectories(configuration.artifactDirectory)
            val end = configuration.startIndex + configuration.caseCount - 1
            val root = MAPPER.createObjectNode()
            root.put("schemaVersion", 1)
            root.put("campaign", "cursor-wrap-model-v1")
            root.put("baseSeed", BASE_SEED)
            root.put("startIndex", configuration.startIndex)
            root.put("endIndex", end)
            root.put("caseCount", configuration.caseCount)
            root.put("commitSha", configuration.commitSha)
            root.put("comparisonScope", "ascii-cells,cursor,autowrap,soft-wrap,scrollback")
            root.put("status", "passed")
            return configuration.artifactDirectory.resolve("campaign-${configuration.startIndex}-$end.json").also {
                MAPPER.writerWithDefaultPrettyPrinter().writeValue(it.toFile(), root)
            }
        }

        fun writeFailure(
            configuration: Configuration,
            globalIndex: Long,
            original: Scenario,
            minimized: Scenario,
            failure: Throwable,
        ): Path {
            val directory = configuration.artifactDirectory.resolve("failures")
            Files.createDirectories(directory)
            val root = MAPPER.createObjectNode()
            root.put("schemaVersion", 1)
            root.put("campaign", "cursor-wrap-model-v1")
            root.put("baseSeed", BASE_SEED)
            root.put("globalCaseIndex", globalIndex)
            root.put("commitSha", configuration.commitSha)
            root.put("seed", original.seed)
            root.put("columns", original.columns)
            root.put("rows", original.rows)
            root.put("failure", failure.message ?: failure::class.java.name)
            root.set<com.fasterxml.jackson.databind.node.ArrayNode>(
                "originalOperations",
                MAPPER.createArrayNode().also { array -> original.operations.forEach { array.add(it.toString()) } },
            )
            root.set<com.fasterxml.jackson.databind.node.ArrayNode>(
                "minimizedOperations",
                MAPPER.createArrayNode().also { array -> minimized.operations.forEach { array.add(it.toString()) } },
            )
            return directory.resolve("seed-${original.seed}.json").also {
                MAPPER.writerWithDefaultPrettyPrinter().writeValue(it.toFile(), root)
            }
        }
    }

    private data class Configuration(
        val startIndex: Long,
        val caseCount: Int,
        val commitSha: String,
        val artifactDirectory: Path,
    ) {
        companion object {
            fun fromSystemProperties(): Configuration {
                val start = System.getProperty("ketraterm.cursorWrap.startIndex", "0").toLong()
                val cases = System.getProperty("ketraterm.cursorWrap.cases", "100").toInt()
                require(start >= 0 && cases > 0)
                Math.addExact(BASE_SEED, Math.addExact(start, cases.toLong() - 1))
                return Configuration(
                    start,
                    cases,
                    System.getProperty("ketraterm.cursorWrap.commitSha", "unknown"),
                    Path.of(System.getProperty("ketraterm.cursorWrap.artifacts")),
                )
            }
        }
    }

    companion object {
        private const val BASE_SEED = 0x43575250_00000000L
        private const val MAX_HISTORY = 32
        private val MAPPER = ObjectMapper()
    }
}

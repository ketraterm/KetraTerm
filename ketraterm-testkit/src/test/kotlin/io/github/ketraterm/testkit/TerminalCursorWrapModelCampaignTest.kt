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
        check(snapshot.modes.originMode == model.originMode) {
            "origin mode differs: expected=${model.originMode}, actual=${snapshot.modes.originMode}"
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
                val actualGlyph =
                    when {
                        cell.flags and TerminalRenderCellFlags.WIDE_TRAILING != 0 -> WIDE_TRAILING
                        cell.flags and TerminalRenderCellFlags.CLUSTER != 0 -> cell.cluster
                        cell.flags and TerminalRenderCellFlags.CODEPOINT != 0 -> String(Character.toChars(cell.codepoint))
                        else -> null
                    }
                check(actualGlyph == expected.cells[column]) {
                    "cell differs at $rowIndex,$column: expected=${expected.cells[column]}, actual=$actualGlyph"
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
            val text: String,
            val width: Int,
        ) : Operation {
            override val bytes: String = text
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

        data class SetVerticalMargins(
            val top: Int,
            val bottom: Int,
        ) : Operation {
            override val bytes = "\u001B[$top;${bottom}r"
        }

        data class SetOriginMode(
            val enabled: Boolean,
        ) : Operation {
            override val bytes = if (enabled) "\u001B[?6h" else "\u001B[?6l"
        }

        data object ReverseIndex : Operation {
            override val bytes = "\u001BM"
        }

        data class InsertLines(
            val count: Int,
        ) : Operation {
            override val bytes = "\u001B[${count}L"
        }

        data class DeleteLines(
            val count: Int,
        ) : Operation {
            override val bytes = "\u001B[${count}M"
        }

        data class ScrollUp(
            val count: Int,
        ) : Operation {
            override val bytes = "\u001B[${count}S"
        }

        data class ScrollDown(
            val count: Int,
        ) : Operation {
            override val bytes = "\u001B[${count}T"
        }

        data class SetHorizontalMargins(
            val left: Int,
            val right: Int,
        ) : Operation {
            override val bytes = "\u001B[?69h\u001B[$left;${right}s"
        }

        data object ResetHorizontalMargins : Operation {
            override val bytes = "\u001B[?69l"
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
                    when (random.nextInt(152)) {
                        in 0..43 -> Operation.Print(('A'.code + random.nextInt(26)).toChar().toString(), 1)
                        in 44..48 -> Operation.Print("e\u0301", 1)
                        in 49..54 -> if (random.nextBoolean()) Operation.Print("中", 2) else Operation.Print("😀", 2)
                        in 55..62 -> Operation.CarriageReturn
                        in 63..70 -> Operation.LineFeed
                        in 71..76 -> Operation.Backspace
                        in 77..84 -> Operation.CursorPosition(random.nextInt(1, rows + 1), random.nextInt(1, columns + 1))
                        in 85..89 -> Operation.CursorForward(random.nextInt(1, columns + 2))
                        in 90..94 -> Operation.CursorBackward(random.nextInt(1, columns + 2))
                        in 95..99 -> Operation.SetAutoWrap(random.nextBoolean())
                        in 100..106 -> {
                            val top = random.nextInt(1, rows)
                            Operation.SetVerticalMargins(top, random.nextInt(top + 1, rows + 1))
                        }
                        in 107..112 -> Operation.SetOriginMode(random.nextBoolean())
                        in 113..118 -> Operation.ReverseIndex
                        in 119..124 -> Operation.InsertLines(random.nextInt(1, rows + 2))
                        in 125..130 -> Operation.DeleteLines(random.nextInt(1, rows + 2))
                        in 131..135 -> Operation.ScrollUp(random.nextInt(1, rows + 2))
                        in 136..139 -> Operation.ScrollDown(random.nextInt(1, rows + 2))
                        in 140..147 -> {
                            val left = random.nextInt(1, columns)
                            Operation.SetHorizontalMargins(left, random.nextInt(left + 1, columns + 1))
                        }
                        else -> Operation.ResetHorizontalMargins
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
            val cells: Array<String?>,
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
        var originMode = false
            private set
        private var scrollTop = 0
        private var scrollBottom = rows - 1
        private var horizontalMargins = false
        private var leftMargin = 0
        private var rightMargin = columns - 1

        val retainedRows: List<Row> get() = history + screen

        fun apply(operation: Operation) {
            when (operation) {
                is Operation.Print -> print(operation.text, operation.width)
                Operation.CarriageReturn -> {
                    column = if (horizontalMargins) leftMargin else 0
                    pendingWrap = false
                }
                Operation.LineFeed -> {
                    pendingWrap = false
                    screen[row].wrapped = false
                    lineFeed()
                }
                Operation.Backspace -> {
                    val minimum = if (horizontalMargins && column in leftMargin..rightMargin) leftMargin else 0
                    column = (column - 1).coerceAtLeast(minimum)
                    pendingWrap = false
                }
                is Operation.CursorPosition -> {
                    row =
                        if (originMode) {
                            (scrollTop + operation.row - 1).coerceIn(scrollTop, scrollBottom)
                        } else {
                            (operation.row - 1).coerceIn(0, rows - 1)
                        }
                    val minimum = if (horizontalMargins) leftMargin else 0
                    val maximum = if (horizontalMargins) rightMargin else columns - 1
                    val addressed = (if (originMode && horizontalMargins) leftMargin else 0) + operation.column - 1
                    column = addressed.coerceIn(minimum, maximum)
                    pendingWrap = false
                }
                is Operation.CursorForward -> {
                    val maximum = if (horizontalMargins && column in leftMargin..rightMargin) rightMargin else columns - 1
                    column = (column + operation.count).coerceAtMost(maximum)
                    pendingWrap = false
                }
                is Operation.CursorBackward -> {
                    val minimum = if (horizontalMargins && column in leftMargin..rightMargin) leftMargin else 0
                    column = (column - operation.count).coerceAtLeast(minimum)
                    pendingWrap = false
                }
                is Operation.SetAutoWrap -> {
                    autoWrap = operation.enabled
                    pendingWrap = false
                }
                is Operation.SetVerticalMargins -> {
                    scrollTop = operation.top - 1
                    scrollBottom = operation.bottom - 1
                    home()
                }
                is Operation.SetOriginMode -> {
                    originMode = operation.enabled
                    home()
                }
                Operation.ReverseIndex -> {
                    pendingWrap = false
                    if (row == scrollTop) scrollDownRegion(1) else row = (row - 1).coerceAtLeast(0)
                }
                is Operation.InsertLines -> {
                    pendingWrap = false
                    insertLines(operation.count)
                }
                is Operation.DeleteLines -> {
                    pendingWrap = false
                    deleteLines(operation.count)
                }
                is Operation.ScrollUp -> {
                    pendingWrap = false
                    scrollUpRegion(operation.count)
                }
                is Operation.ScrollDown -> {
                    pendingWrap = false
                    scrollDownRegion(operation.count)
                }
                is Operation.SetHorizontalMargins -> {
                    horizontalMargins = true
                    leftMargin = operation.left - 1
                    rightMargin = operation.right - 1
                    home()
                }
                Operation.ResetHorizontalMargins -> {
                    pendingWrap = false
                    if (!horizontalMargins) return
                    horizontalMargins = false
                    leftMargin = 0
                    rightMargin = columns - 1
                    home()
                }
            }
        }

        private fun home() {
            row = if (originMode) scrollTop else 0
            column = if (horizontalMargins) leftMargin else 0
            pendingWrap = false
        }

        private fun print(
            text: String,
            cellWidth: Int,
        ) {
            if (horizontalMargins && column !in leftMargin..rightMargin) return
            if (pendingWrap) {
                screen[row].wrapped = true
                column = leftMargin
                lineFeed()
                pendingWrap = false
            }
            if (cellWidth == 2 && column >= rightMargin) {
                if (!autoWrap) return
                clearOccupant(screen[row], column)
                screen[row].wrapped = true
                column = leftMargin
                lineFeed()
            }
            clearOccupant(screen[row], column)
            if (cellWidth == 2) clearOccupant(screen[row], column + 1)
            screen[row].cells[column] = text
            if (cellWidth == 2) screen[row].cells[column + 1] = WIDE_TRAILING
            column += cellWidth
            if (column > rightMargin) {
                column = rightMargin
                pendingWrap = autoWrap
            }
        }

        private fun clearOccupant(
            line: Row,
            target: Int,
        ) {
            if (target !in line.cells.indices) return
            when {
                line.cells[target] == WIDE_TRAILING -> {
                    line.cells[target] = null
                    if (target > 0) line.cells[target - 1] = null
                }
                target + 1 < columns && line.cells[target + 1] == WIDE_TRAILING -> {
                    line.cells[target] = null
                    line.cells[target + 1] = null
                }
                else -> line.cells[target] = null
            }
        }

        private fun lineFeed() {
            if (row == scrollBottom) {
                scrollUpRegion(1)
            } else if (row < rows - 1) {
                row++
            }
        }

        private fun insertLines(count: Int) {
            if (row !in scrollTop..scrollBottom) return
            val amount = count.coerceAtMost(scrollBottom - row + 1)
            if (horizontalMargins) {
                for (target in scrollBottom downTo row + amount) {
                    normalizeSliceBoundaries(screen[target])
                    copyHorizontalSlice(screen[target - amount], screen[target])
                    screen[target].wrapped = false
                }
                for (target in row until row + amount) {
                    normalizeSliceBoundaries(screen[target])
                    if (rightMargin + 1 < columns && screen[target].cells[rightMargin + 1] == WIDE_TRAILING) {
                        clearOccupant(screen[target], rightMargin + 1)
                    }
                    screen[target].cells.fill(null, leftMargin, rightMargin + 1)
                    screen[target].wrapped = false
                }
                if (row > 0) screen[row - 1].wrapped = false
                return
            }
            repeat(amount) {
                screen.removeAt(scrollBottom)
                screen.add(row, blankRow())
            }
            if (row > 0) screen[row - 1].wrapped = false
        }

        private fun deleteLines(count: Int) {
            if (row !in scrollTop..scrollBottom) return
            val amount = count.coerceAtMost(scrollBottom - row + 1)
            if (horizontalMargins) {
                for (target in row..scrollBottom - amount) {
                    normalizeSliceBoundaries(screen[target])
                    copyHorizontalSlice(screen[target + amount], screen[target])
                    screen[target].wrapped = false
                }
                for (target in scrollBottom - amount + 1..scrollBottom) {
                    normalizeSliceBoundaries(screen[target])
                    if (rightMargin + 1 < columns && screen[target].cells[rightMargin + 1] == WIDE_TRAILING) {
                        clearOccupant(screen[target], rightMargin + 1)
                    }
                    screen[target].cells.fill(null, leftMargin, rightMargin + 1)
                    screen[target].wrapped = false
                }
                if (row > 0) screen[row - 1].wrapped = false
                return
            }
            repeat(amount) {
                screen.removeAt(row)
                screen.add(scrollBottom, blankRow())
            }
            if (row > 0) screen[row - 1].wrapped = false
        }

        private fun scrollUpRegion(count: Int) {
            repeat(count) {
                val removed = screen.removeAt(scrollTop)
                screen.add(scrollBottom, blankRow())
                if (scrollTop == 0) {
                    history.addLast(removed)
                    if (history.size > maxHistory) history.removeFirst()
                }
            }
        }

        private fun normalizeSliceBoundaries(line: Row) {
            if (line.cells[leftMargin] == WIDE_TRAILING) {
                clearOccupant(line, leftMargin)
            }
            if (rightMargin + 1 < columns && line.cells[rightMargin + 1] == WIDE_TRAILING) {
                clearOccupant(line, rightMargin + 1)
            }
        }

        private fun copyHorizontalSlice(
            source: Row,
            destination: Row,
        ) {
            for (cell in leftMargin..rightMargin) {
                destination.cells[cell] =
                    when {
                        cell == leftMargin && source.cells[cell] == WIDE_TRAILING -> null
                        cell == rightMargin &&
                            cell + 1 < columns &&
                            source.cells[cell + 1] == WIDE_TRAILING -> null
                        else -> source.cells[cell]
                    }
            }
        }

        private fun scrollDownRegion(count: Int) {
            repeat(count) {
                screen.removeAt(scrollBottom)
                screen.add(scrollTop, blankRow())
            }
        }

        private fun blankRow() = Row(arrayOfNulls(columns))
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
            root.put("campaign", "grid-physics-model-v2")
            root.put("baseSeed", BASE_SEED)
            root.put("startIndex", configuration.startIndex)
            root.put("endIndex", end)
            root.put("caseCount", configuration.caseCount)
            root.put("commitSha", configuration.commitSha)
            root.put(
                "comparisonScope",
                "unicode-cells,wide-spans,clusters,cursor,margins,origin,autowrap,soft-wrap,regional-scroll,scrollback,il,dl,su,sd,ri",
            )
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
            root.put("campaign", "grid-physics-model-v2")
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
        private const val WIDE_TRAILING = "\u0000"
        private val MAPPER = ObjectMapper()
    }
}

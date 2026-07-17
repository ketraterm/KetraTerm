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
import java.util.*

/** Deterministic state-aware campaign for KetraTerm's width-changing reflow contract. */
class TerminalResizeReflowInvariantCampaignTest {
    @Test
    fun `generated resize reflow preserves graphemes and public grid invariants`() {
        assumeTrue(System.getProperty("ketraterm.resizeReflow.required") == "true") {
            "run a generated resize/reflow Gradle profile"
        }
        val configuration = CampaignConfiguration.fromSystemProperties()
        for (localIndex in 0 until configuration.caseCount) {
            val globalIndex = Math.addExact(configuration.startIndex, localIndex.toLong())
            val seed = Math.addExact(BASE_SEED, globalIndex)
            val scenario = ResizeScenarioGenerator.generate(seed)
            try {
                verify(scenario)
            } catch (failure: Throwable) {
                val minimized = ResizeScenarioShrinker.shrinkResizes(scenario, ::fails)
                val minimizedFailure = runCatching { verify(minimized) }.exceptionOrNull() ?: failure
                val artifact =
                    FailureArtifactWriter.write(configuration, globalIndex, scenario, minimized, minimizedFailure)
                fail<Unit>(
                    "resize/reflow invariant failure seed=$seed globalCase=$globalIndex " +
                        "localCase=$localIndex/${configuration.caseCount}\nartifact=$artifact",
                    failure,
                )
            }
        }
        FailureArtifactWriter.writeCampaignReport(configuration)
    }

    private fun fails(scenario: ResizeScenario): Boolean = runCatching { verify(scenario) }.isFailure

    private fun verify(scenario: ResizeScenario) {
        val snapshot =
            TerminalConformanceHarness(
                columns = scenario.initialColumns,
                rows = scenario.initialRows,
                maxHistory = MAX_HISTORY,
            ).replay(TerminalReplayTranscript(scenario.events + TerminalReplayEvent.EndOfInput))
        assertSnapshotInvariants(snapshot, scenario)
        val actualGraphemes = snapshot.glyphsInStorageOrder()
        check(actualGraphemes == scenario.expectedGraphemes) {
            "grapheme order/content changed: expected=${scenario.expectedGraphemes}, actual=$actualGraphemes"
        }
    }

    private fun assertSnapshotInvariants(
        snapshot: TerminalConformanceSnapshot,
        scenario: ResizeScenario,
    ) {
        check(snapshot.columns == scenario.finalColumns)
        check(snapshot.visibleRows == scenario.finalRows)
        check(snapshot.historyRows in 0..MAX_HISTORY)
        check(snapshot.liveRowStart == snapshot.historyRows)
        check(snapshot.retainedRows.size == snapshot.historyRows + snapshot.visibleRows)
        check(snapshot.cursor.column in 0 until snapshot.columns)
        check(snapshot.cursor.row in 0 until snapshot.visibleRows)

        for ((rowIndex, row) in snapshot.retainedRows.withIndex()) {
            check(row.cells.size == snapshot.columns) { "row $rowIndex has ${row.cells.size} cells" }
            for (column in row.cells.indices) {
                val cell = row.cells[column]
                check(TerminalRenderCellFlags.isValidCombination(cell.flags)) {
                    "row $rowIndex column $column has invalid flags 0x${cell.flags.toString(16)}"
                }
                val isLeading = cell.flags and TerminalRenderCellFlags.WIDE_LEADING != 0
                val isTrailing = cell.flags and TerminalRenderCellFlags.WIDE_TRAILING != 0
                if (isLeading) {
                    check(column + 1 < row.cells.size) { "wide leader at right edge" }
                    check(row.cells[column + 1].flags == TerminalRenderCellFlags.WIDE_TRAILING) {
                        "wide leader at $rowIndex,$column lacks adjacent trailing cell"
                    }
                }
                if (isTrailing) {
                    check(column > 0) { "wide trailing cell at left edge" }
                    check(row.cells[column - 1].flags and TerminalRenderCellFlags.WIDE_LEADING != 0) {
                        "orphan wide trailing cell at $rowIndex,$column"
                    }
                    check(cell.codepoint == 0 && cell.cluster == null) {
                        "wide trailing cell carries glyph data at $rowIndex,$column"
                    }
                }
                if (cell.flags and TerminalRenderCellFlags.CLUSTER != 0) {
                    check(!cell.cluster.isNullOrEmpty()) { "cluster cell lacks payload at $rowIndex,$column" }
                }
            }
        }
    }

    private data class ResizeScenario(
        val seed: Long,
        val initialColumns: Int,
        val initialRows: Int,
        val finalColumns: Int,
        val finalRows: Int,
        val expectedGraphemes: List<String>,
        val events: List<TerminalReplayEvent>,
    ) {
        fun withEvents(candidateEvents: List<TerminalReplayEvent>): ResizeScenario {
            val finalResize = candidateEvents.filterIsInstance<TerminalReplayEvent.Resize>().lastOrNull()
            return copy(
                finalColumns = finalResize?.columns ?: initialColumns,
                finalRows = finalResize?.rows ?: initialRows,
                events = candidateEvents,
            )
        }
    }

    private object ResizeScenarioGenerator {
        private val graphemes = arrayOf("A", "z", "\u03A9", "e\u0301", "\u4E2D", "\uD83D\uDE00")

        fun generate(seed: Long): ResizeScenario {
            val random = SplittableRandom(seed)
            val initialColumns = random.nextInt(MIN_COLUMNS, MAX_COLUMNS + 1)
            val initialRows = random.nextInt(MIN_ROWS, MAX_ROWS + 1)
            val expected = ArrayList<String>()
            val events = ArrayList<TerminalReplayEvent>()
            var columns = initialColumns
            var rows = initialRows
            val tokenCount = random.nextInt(MIN_GRAPHEMES, MAX_GRAPHEMES + 1)
            var emitted = 0
            while (emitted < tokenCount) {
                val chunkSize = minOf(tokenCount - emitted, random.nextInt(1, MAX_INPUT_CHUNK_GRAPHEMES + 1))
                val chunk = StringBuilder()
                repeat(chunkSize) {
                    val grapheme = graphemes[random.nextInt(graphemes.size)]
                    expected += grapheme
                    chunk.append(grapheme)
                }
                events += TerminalReplayEvent.Input.utf8(chunk.toString())
                emitted += chunkSize
            }
            repeat(random.nextInt(1, MAX_RESIZES + 1)) {
                columns = random.nextInt(MIN_COLUMNS, MAX_COLUMNS + 1)
                rows = random.nextInt(MIN_ROWS, MAX_ROWS + 1)
                events += TerminalReplayEvent.Resize(columns, rows)
            }
            return ResizeScenario(seed, initialColumns, initialRows, columns, rows, expected, events)
        }
    }

    private object ResizeScenarioShrinker {
        fun shrinkResizes(
            original: ResizeScenario,
            fails: (ResizeScenario) -> Boolean,
        ): ResizeScenario {
            var current = original
            var changed: Boolean
            do {
                changed = false
                for (index in current.events.indices) {
                    if (current.events[index] !is TerminalReplayEvent.Resize) continue
                    val candidate = current.withEvents(current.events.filterIndexed { eventIndex, _ -> eventIndex != index })
                    if (fails(candidate)) {
                        current = candidate
                        changed = true
                        break
                    }
                }
            } while (changed)
            return current
        }
    }

    private object FailureArtifactWriter {
        fun writeCampaignReport(configuration: CampaignConfiguration): Path {
            Files.createDirectories(configuration.artifactDirectory)
            val endIndex = Math.addExact(configuration.startIndex, configuration.caseCount.toLong() - 1L)
            val destination = configuration.artifactDirectory.resolve("campaign-${configuration.startIndex}-$endIndex.json")
            val root = MAPPER.createObjectNode()
            root.put("schemaVersion", 1)
            root.put("campaign", "resize-reflow-invariant-v1")
            root.put("baseSeed", BASE_SEED)
            root.put("startIndex", configuration.startIndex)
            root.put("endIndex", endIndex)
            root.put("caseCount", configuration.caseCount)
            root.put("commitSha", configuration.commitSha)
            root.put("comparisonScope", "grapheme-content,dimensions,cursor,cell-flags,wide-cell-structure")
            root.put("status", "passed")
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(destination.toFile(), root)
            return destination
        }

        fun write(
            configuration: CampaignConfiguration,
            globalIndex: Long,
            original: ResizeScenario,
            minimized: ResizeScenario,
            failure: Throwable,
        ): Path {
            val directory = configuration.artifactDirectory.resolve("failures")
            Files.createDirectories(directory)
            val destination = directory.resolve("seed-${original.seed}.json")
            val root = MAPPER.createObjectNode()
            root.put("schemaVersion", 1)
            root.put("campaign", "resize-reflow-invariant-v1")
            root.put("baseSeed", BASE_SEED)
            root.put("startIndex", configuration.startIndex)
            root.put("caseCount", configuration.caseCount)
            root.put("globalCaseIndex", globalIndex)
            root.put("commitSha", configuration.commitSha)
            root.put("seed", original.seed)
            root.put("initialColumns", original.initialColumns)
            root.put("initialRows", original.initialRows)
            root.put("finalColumns", minimized.finalColumns)
            root.put("finalRows", minimized.finalRows)
            root.put("failure", failure.message ?: failure::class.java.name)
            root.set<com.fasterxml.jackson.databind.node.ArrayNode>(
                "expectedGraphemes",
                MAPPER.createArrayNode().also { array -> original.expectedGraphemes.forEach(array::add) },
            )
            root.set<com.fasterxml.jackson.databind.node.ArrayNode>(
                "originalEvents",
                MAPPER.createArrayNode().also { array ->
                    original.events.forEach { array.add(eventDescription(it)) }
                },
            )
            root.set<com.fasterxml.jackson.databind.node.ArrayNode>(
                "minimizedEvents",
                MAPPER.createArrayNode().also { array ->
                    minimized.events.forEach { array.add(eventDescription(it)) }
                },
            )
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(destination.toFile(), root)
            return destination
        }

        private fun eventDescription(event: TerminalReplayEvent): String =
            when (event) {
                is TerminalReplayEvent.Input -> "input:${TerminalByteSequence.of(event.copyBytes()).toHexString()}"
                is TerminalReplayEvent.Resize -> "resize:${event.columns}x${event.rows}"
                TerminalReplayEvent.EndOfInput -> "end"
            }
    }

    private data class CampaignConfiguration(
        val startIndex: Long,
        val caseCount: Int,
        val commitSha: String,
        val artifactDirectory: Path,
    ) {
        companion object {
            fun fromSystemProperties(): CampaignConfiguration {
                val startIndex = System.getProperty("ketraterm.resizeReflow.startIndex", "0").toLong()
                val caseCount = System.getProperty("ketraterm.resizeReflow.cases", "100").toInt()
                require(startIndex >= 0) { "resize/reflow start index must be non-negative" }
                require(caseCount > 0) { "resize/reflow case count must be positive" }
                Math.addExact(BASE_SEED, Math.addExact(startIndex, caseCount.toLong() - 1L))
                return CampaignConfiguration(
                    startIndex,
                    caseCount,
                    System.getProperty("ketraterm.resizeReflow.commitSha", "unknown"),
                    Path.of(System.getProperty("ketraterm.resizeReflow.artifacts")),
                )
            }
        }
    }

    private fun TerminalConformanceSnapshot.glyphsInStorageOrder(): List<String> =
        buildList {
            for (row in retainedRows) {
                for (cell in row.cells) {
                    when {
                        cell.flags and TerminalRenderCellFlags.WIDE_TRAILING != 0 -> Unit
                        cell.flags and TerminalRenderCellFlags.CLUSTER != 0 -> add(requireNotNull(cell.cluster))
                        cell.flags and TerminalRenderCellFlags.CODEPOINT != 0 ->
                            add(String(Character.toChars(cell.codepoint)))
                    }
                }
            }
        }

    companion object {
        private const val BASE_SEED = 0x5245464C_00000000L
        private const val MAX_HISTORY = 128
        private const val MIN_COLUMNS = 3
        private const val MAX_COLUMNS = 14
        private const val MIN_ROWS = 2
        private const val MAX_ROWS = 8
        private const val MIN_GRAPHEMES = 8
        private const val MAX_GRAPHEMES = 48
        private const val MAX_INPUT_CHUNK_GRAPHEMES = 5
        private const val MAX_RESIZES = 20
        private val MAPPER = ObjectMapper()
    }
}

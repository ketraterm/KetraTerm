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
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.*

/** Deterministic stateful differential campaign with failure shrinking and replay artifacts. */
class XtermGeneratedDifferentialOracleTest {
    @Test
    fun `generated protocol campaigns agree with xterm js`() {
        val caseCount = System.getProperty("ketraterm.generatedDifferential.cases", "2000").toInt()
        require(caseCount > 0) { "generated case count must be positive" }
        for (index in 0 until caseCount) {
            val seed = BASE_SEED + index
            val scenario = ScenarioGenerator.generate(seed)
            val result = compare(scenario)
            if (!result.isEmpty) {
                val minimized = ScenarioShrinker.shrink(scenario, ::hasMismatch)
                val minimizedResult = compare(minimized)
                val artifact = FailureArtifactWriter.write(scenario, result, minimized, minimizedResult)
                fail<Unit>(
                    "generated differential mismatch seed=$seed case=$index/$caseCount\n" +
                        "artifact=$artifact\n${minimizedResult.format()}",
                )
            }
        }
    }

    private fun hasMismatch(scenario: GeneratedScenario): Boolean = !compare(scenario).isEmpty

    private fun compare(scenario: GeneratedScenario): TerminalDifferentialResult {
        val transcript = scenario.transcript()
        val ketraTerm = TerminalConformanceHarness(scenario.columns, scenario.rows, scenario.maxHistory).replay(transcript)
        val independent = oracle.replay(scenario.columns, scenario.rows, scenario.maxHistory, transcript)
        return TerminalDifferentialComparator.compare(
            ketraTerm,
            independent,
            MAX_DIFFERENCES,
            compareWrappedRows = false,
        )
    }

    private data class GeneratedScenario(
        val seed: Long,
        val columns: Int,
        val rows: Int,
        val maxHistory: Int,
        val operations: List<ByteArray>,
        val chunkSeed: Long,
    ) {
        fun transcript(): TerminalReplayTranscript {
            val bytes = concatenate(operations)
            if (bytes.isEmpty()) return TerminalReplayTranscript.of(TerminalReplayEvent.EndOfInput)
            val random = SplittableRandom(chunkSeed)
            val events = ArrayList<TerminalReplayEvent>()
            var offset = 0
            while (offset < bytes.size) {
                val count = minOf(bytes.size - offset, 1 + random.nextInt(MAX_CHUNK_SIZE))
                events += TerminalReplayEvent.Input(bytes.copyOfRange(offset, offset + count))
                offset += count
            }
            events += TerminalReplayEvent.EndOfInput
            return TerminalReplayTranscript(events)
        }
    }

    private object ScenarioGenerator {
        private val printable = arrayOf("a", "Z", "0", "_", " ", "e\u0301", "Ω")
        private val sgr =
            arrayOf(
                "\u001B[1m",
                "\u001B[3m",
                "\u001B[4m",
                "\u001B[7m",
                "\u001B[38;5;196m",
                "\u001B[48;2;1;127;255m",
            )

        fun generate(seed: Long): GeneratedScenario {
            val random = SplittableRandom(seed)
            val columns = 4 + random.nextInt(9)
            val rows = 2 + random.nextInt(5)
            val operationCount = 6 + random.nextInt(25)
            val operations = ArrayList<ByteArray>(operationCount)
            repeat(operationCount) {
                operations += operation(random, columns, rows).encodeToByteArray()
            }
            return GeneratedScenario(seed, columns, rows, 8, operations, random.nextLong())
        }

        private fun operation(
            random: SplittableRandom,
            columns: Int,
            rows: Int,
        ): String =
            when (random.nextInt(24)) {
                0, 1 -> printable[random.nextInt(printable.size)]
                // Keep generated wide text isolated. xterm.js can move a width-0 spacer
                // independently when an edit begins on it; KetraTerm forbids that corrupt grid.
                // Focused core tests cover the invariant and the curated corpus covers wide text.
                2 -> "\u001B[?1049h\u001B[1;1H中\u001B[?1049l\u001B[1;1H"
                3 -> arrayOf("\r", "\n", "\r\n", "\b")[random.nextInt(4)]
                4 -> "\u001B[${1 + random.nextInt(rows)};${1 + random.nextInt(columns)}H"
                5 -> "\u001B[${1 + random.nextInt(5)}${arrayOf('A', 'B', 'C', 'D')[random.nextInt(4)]}"
                // DEC/ECMA do not define persistent logical-line wrap metadata after erase.
                // Focused policy tests own erase/wrap behavior; generated parity uses movement.
                6 -> "\u001B[${1 + random.nextInt(rows)};${1 + random.nextInt(columns)}H"
                7 -> "\u001B[${1 + random.nextInt(5)}${arrayOf('A', 'B', 'C', 'D')[random.nextInt(4)]}"
                8 -> "\u001B[${1 + random.nextInt(4)}@"
                9 -> "\u001B[${1 + random.nextInt(4)}P"
                10 -> "\u001B[${1 + random.nextInt(4)}X"
                11 -> "\r\u001B[${1 + random.nextInt(3)}L"
                12 -> "\r\u001B[${1 + random.nextInt(3)}M"
                13 -> "\u001B[${1 + random.nextInt(columns)}G"
                14 -> "\u001B[${1 + random.nextInt(rows)}d"
                15 -> "${sgr[random.nextInt(sgr.size)]}X\u001B[0m"
                16 -> "\u001B7\u001B[${1 + random.nextInt(rows)};${1 + random.nextInt(columns)}H\u001B8\u001B[1;1H"
                17 -> "\u001B[2;${rows}r\u001B[?6h\u001B[1;1H${printable[random.nextInt(printable.size)]}\u001B[?6l\u001B[r"
                18 -> "\u001B[${1 + random.nextInt(3)}E"
                19 -> "\u001B[?1049h${printable[random.nextInt(printable.size)]}\u001B[?1049l\u001B[1;1H"
                20 -> "\u001B]2;generated-${random.nextInt(100)}\u0007"
                21 -> "\u001B[?1;1000;1004;2004h\u001B[?1;1000;1004;2004l"
                22 -> "\u001B[1;1H\u001B[6n"
                else -> "\u001Bc"
            }
    }

    private object ScenarioShrinker {
        fun shrink(
            original: GeneratedScenario,
            fails: (GeneratedScenario) -> Boolean,
        ): GeneratedScenario {
            var operations = original.operations
            var granularity = 2
            while (operations.size >= 2) {
                val chunkSize = (operations.size + granularity - 1) / granularity
                var reduced = false
                var start = 0
                while (start < operations.size) {
                    val end = minOf(operations.size, start + chunkSize)
                    val candidateOperations = operations.filterIndexed { index, _ -> index !in start until end }
                    if (candidateOperations.isNotEmpty()) {
                        val candidate = original.copy(operations = candidateOperations)
                        if (fails(candidate)) {
                            operations = candidateOperations
                            granularity = maxOf(2, granularity - 1)
                            reduced = true
                            break
                        }
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

    private object FailureArtifactWriter {
        fun write(
            original: GeneratedScenario,
            originalResult: TerminalDifferentialResult,
            minimized: GeneratedScenario,
            minimizedResult: TerminalDifferentialResult,
        ): Path {
            Files.createDirectories(artifactDirectory)
            val destination = artifactDirectory.resolve("seed-${original.seed}.json")
            val root = MAPPER.createObjectNode()
            root.put("schemaVersion", 1)
            root.put("seed", original.seed)
            root.put("columns", original.columns)
            root.put("rows", original.rows)
            root.put("maxHistory", original.maxHistory)
            root.put("chunkSeed", original.chunkSeed)
            root.set<ArrayNode>("operationsHex", operationArray(original.operations))
            root.set<ArrayNode>("minimizedOperationsHex", operationArray(minimized.operations))
            root.set<ObjectNode>("originalDifference", differenceNode(originalResult))
            root.set<ObjectNode>("minimizedDifference", differenceNode(minimizedResult))
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(destination.toFile(), root)
            return destination
        }

        private fun operationArray(operations: List<ByteArray>): ArrayNode =
            MAPPER.createArrayNode().also { array ->
                operations.forEach { array.add(TerminalByteSequence.of(it).toHexString()) }
            }

        private fun differenceNode(result: TerminalDifferentialResult): ObjectNode =
            MAPPER.createObjectNode().also { node ->
                node.put("oracleName", result.oracle.name)
                node.put("oracleVersion", result.oracle.version)
                node.put("truncated", result.truncated)
                node.set<ArrayNode>(
                    "differences",
                    MAPPER.createArrayNode().also { differences ->
                        for (difference in result.differences) {
                            differences.add(
                                MAPPER.createObjectNode().apply {
                                    put("path", difference.path)
                                    put("ketraTerm", difference.expected)
                                    put("oracle", difference.actual)
                                    difference.context?.let { put("context", it) }
                                },
                            )
                        }
                    },
                )
            }
    }

    companion object {
        private const val BASE_SEED = 0x4B455452_00000000L
        private const val MAX_CHUNK_SIZE = 8
        private const val MAX_DIFFERENCES = 64
        private val MAPPER = ObjectMapper()
        private lateinit var oracle: TerminalPersistentProcessOracle
        private lateinit var artifactDirectory: Path

        @JvmStatic
        @BeforeAll
        fun configureOracle() {
            val required = System.getProperty("ketraterm.xtermOracle.required") == "true"
            val script = System.getProperty("ketraterm.xtermOracle.script")?.let(Path::of)
            assumeTrue(required && script != null && Files.isRegularFile(script)) {
                "run a generated xterm differential Gradle profile"
            }
            artifactDirectory = Path.of(System.getProperty("ketraterm.generatedDifferential.artifacts"))
            oracle =
                TerminalPersistentProcessOracle(
                    command = listOf(System.getProperty("ketraterm.xtermOracle.node", "node"), script.toString(), "--server"),
                    workingDirectory = Path.of(System.getProperty("ketraterm.xtermOracle.workingDirectory")),
                )
        }

        @JvmStatic
        @AfterAll
        fun closeOracle() {
            if (::oracle.isInitialized) oracle.close()
        }

        private fun concatenate(parts: List<ByteArray>): ByteArray {
            val size = parts.sumOf(ByteArray::size)
            val result = ByteArray(size)
            var offset = 0
            for (part in parts) {
                part.copyInto(result, offset)
                offset += part.size
            }
            return result
        }
    }
}

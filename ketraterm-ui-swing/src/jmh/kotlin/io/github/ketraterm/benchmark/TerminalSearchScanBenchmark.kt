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
package io.github.ketraterm.benchmark

import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.ui.swing.search.TerminalSearchModel
import io.github.ketraterm.ui.swing.search.TerminalSearchScan
import kotlinx.coroutines.runBlocking
import org.openjdk.jmh.annotations.*
import java.util.concurrent.TimeUnit

/**
 * One complete worker-side retained-history search per operation, including session
 * locking, bounded copying, matching, cancellation checks and coroutine yields.
 * Excludes fixture ingestion, EDT dispatch, result publication and painting.
 * Buffers are reused after a setup scan, so GC profiling measures warmed scanning.
 */
@State(Scope.Thread)
@Threads(1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(3)
open class TerminalSearchScanBenchmark {
    @Param("10000", "50000")
    var retainedRows = 0

    @Param("160")
    var columns = 0

    @Param("sparse", "dense", "clusters", "wrapped", "repetitive")
    lateinit var workload: String

    @Param("false", "true")
    var ignoreCase = false

    @Param("every-row", "rare", "none")
    lateinit var matches: String

    private var query = "needle"
    private lateinit var session: TerminalSession
    private val scanner = TerminalSearchScan()
    private val model = TerminalSearchModel()

    @Setup
    open fun setup() {
        require(columns >= 10)
        val terminal = TerminalBuffers.create(columns, 1, maxHistory = retainedRows - 1)
        val denseTail = "x".repeat(columns - 6)
        val clusterTail = "x".repeat(columns - 10)
        val combining = intArrayOf('e'.code, 0x301)
        val emoji = intArrayOf(0x1F469, 0x200D, 0x1F4BB)
        val repetitive = workload == "repetitive"
        query = if (repetitive) "a".repeat(128) + "b" else "needle"
        repeat(retainedRows) { row ->
            if (row > 0 && workload != "wrapped" && !repetitive) {
                terminal.carriageReturn()
                terminal.newLine()
            }
            if (repetitive) {
                terminal.writeText("a".repeat(columns - if (row == retainedRows - 1) 1 else 0))
                if (row == retainedRows - 1) terminal.writeText("b")
                return@repeat
            }
            val hasMatch = matches == "every-row" || (matches == "rare" && row % 100 == 0)
            terminal.writeText(if (hasMatch) "needle" else "xxxxxx")
            when (workload) {
                "sparse" -> Unit
                "dense", "wrapped" -> terminal.writeText(denseTail)
                "clusters" -> {
                    terminal.writeText(" ")
                    terminal.writeCluster(combining)
                    terminal.writeCluster(emoji)
                    terminal.writeText(clusterTail)
                }
                else -> error("Unknown workload: $workload")
            }
        }
        session = benchmarkSession(terminal)
        val expected =
            if (repetitive) {
                1
            } else {
                when (matches) {
                    "every-row" -> retainedRows
                    "rare" -> (retainedRows + 99) / 100
                    "none" -> 0
                    else -> error("Unknown match distribution: $matches")
                }
            }
        check(scanRetainedHistory() == expected)
    }

    @Benchmark
    open fun scanRetainedHistory(): Int =
        runBlocking {
            requireNotNull(scanner.scan(session, model, query, ignoreCase)).highlights.resultCount
        }

    @TearDown
    open fun tearDown() {
        session.close()
    }
}

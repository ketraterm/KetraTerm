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
 * Reproducible shell, Gradle and service-log shaped histories; not captured user traces.
 * Queries change on each invocation, using the UI's default case-insensitive mode.
 * Includes copying, matching and result geometry; excludes ingestion and painting.
 * Refinement measures complete searches for successive prefixes, without UI debounce.
 */
@State(Scope.Thread)
@Threads(1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 4, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(3)
open class TerminalSearchUsageBenchmark {
    @Param("shell-path", "build-task", "build-failure", "log-request", "log-level", "log-miss", "log-refine")
    lateinit var workload: String

    private lateinit var session: TerminalSession
    private lateinit var queries: Array<String>
    private lateinit var expected: IntArray
    private val scanner = TerminalSearchScan()
    private val model = TerminalSearchModel()
    private var nextQuery = 0

    @Setup
    open fun setup() {
        queries =
            when (workload) {
                "shell-path" -> arrayOf("src", "src/")
                "build-task" -> arrayOf("compile", "compileKotlin")
                "build-failure" -> arrayOf("failed", "failed:")
                "log-request" -> arrayOf("request 9", "request 99")
                "log-level" -> arrayOf("warn", "error")
                "log-miss" -> arrayOf("connection refused", "connection refused!")
                "log-refine" -> arrayOf("r", "re", "req", "requ", "reque", "reques", "request")
                else -> error("Unknown workload: $workload")
            }
        val modules = arrayOf("core", "parser", "session", "ui-swing", "host", "input")
        val lines =
            Array(10_000) { row ->
                val module = modules[row % modules.size]
                when {
                    workload.startsWith("shell-") ->
                        when (row % 8) {
                            0 -> "dev@workstation:~/project$ ls -lah src/"
                            1 -> "total 128K"
                            2 -> "drwxr-xr-x  8 dev dev 4.0K Oct 12 09:31 src"
                            3 -> "-rw-r--r--  1 dev dev 2.4K Oct 12 09:30 README.md"
                            4 -> "-rw-r--r--  1 dev dev 8.2K Oct 12 09:31 Module$row.kt"
                            5 -> "dev@workstation:~/project$ git status --short"
                            6 -> " M src/main/kotlin/io/github/project/$module/Module$row.kt"
                            else -> "?? docs/résumé-$row.txt"
                        }
                    workload.startsWith("build-") ->
                        when {
                            row == 9_992 -> "FAILURE: Build failed with an exception."
                            row == 9_993 -> "Execution failed: task ':ketraterm-core:test'."
                            row == 9_994 -> "java.lang.AssertionError: expected:<42> but was:<43>"
                            row == 9_995 -> "    at io.github.ketraterm.core.CursorTest.movesCursor(CursorTest.kt:127)"
                            else ->
                                when (row % 6) {
                                    0 -> "> Task :ketraterm-$module:compileKotlin"
                                    1 -> "> Task :ketraterm-$module:compileTestKotlin UP-TO-DATE"
                                    2 -> "> Task :ketraterm-$module:processResources NO-SOURCE"
                                    3 -> "> Task :ketraterm-$module:test"
                                    4 -> "> Task :ketraterm-$module:jar"
                                    else -> "BUILD SUCCESSFUL in 19s"
                                }
                        }
                    else -> {
                        val level =
                            if (row % 997 == 0) {
                                "ERROR"
                            } else if (row % 101 == 0) {
                                "WARN"
                            } else {
                                "INFO"
                            }
                        val action = if (level == "ERROR") "timeout waiting for upstream" else "completed status=200"
                        "2026-10-12 09:31:42.$row [$module-worker] $level request $row GET /api/items/${row % 200} $action duration=${row % 83}ms"
                    }
                }
            }
        // Independent string oracle, calculated outside timing before terminal encoding.
        expected =
            IntArray(queries.size) { queryIndex ->
                val query = queries[queryIndex]
                lines.sumOf { line ->
                    var count = 0
                    var offset = 0
                    while (true) {
                        val found = line.indexOf(query, offset, ignoreCase = true)
                        if (found < 0) break
                        count++
                        offset = found + query.length
                    }
                    count
                }
            }
        val terminal = TerminalBuffers.create(100, 24, maxHistory = 100_000)
        for (line in lines) {
            terminal.writeText(line)
            terminal.carriageReturn()
            terminal.newLine()
        }
        session = benchmarkSession(terminal)
        repeat(queries.size) { searchChangedQuery() }
    }

    @Benchmark
    open fun searchChangedQuery(): Int =
        runBlocking {
            val index = nextQuery
            nextQuery = (nextQuery + 1) % queries.size
            val count = requireNotNull(scanner.scan(session, model, queries[index], true)).highlights.resultCount
            check(count == expected[index]) { "${queries[index]}: expected ${expected[index]}, got $count" }
            count
        }

    @TearDown
    open fun close() {
        session.close()
    }
}

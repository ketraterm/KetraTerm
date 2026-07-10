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

import io.github.ketraterm.completion.api.*
import io.github.ketraterm.completion.model.TerminalCommandSpecs
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.TimeUnit

/** Measures pure static completion through the full POSIX segment-aware engine path. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@Fork(1)
open class TerminalCompletionBenchmark {
    private val commandSpecs = TerminalCommandSpecs.defaults()
    private val engine =
        TerminalCompletionEngines.fromSources(
            sources = listOf(TerminalCompletionSourceEntry(TerminalCompletionSources.fromSpecs(commandSpecs))),
            commandSpecs = commandSpecs,
        )

    @Param("64", "512", "4096", "32768")
    var commandLineLength: Int = 0

    private lateinit var chainedRequest: TerminalCompletionRequest
    private lateinit var unclosedQuoteRequest: TerminalCompletionRequest

    @Setup(Level.Trial)
    open fun setUp() {
        chainedRequest = requestFor("git status && ", "git sw")
        unclosedQuoteRequest = requestFor("git status && cd ", "\"Idea Pro")
    }

    @Benchmark
    open fun completeChainedCommand(blackhole: Blackhole) {
        blackhole.consume(engine.complete(chainedRequest))
    }

    @Benchmark
    open fun completeUnclosedQuote(blackhole: Blackhole) {
        blackhole.consume(engine.complete(unclosedQuoteRequest))
    }

    private fun requestFor(
        prefix: String,
        suffix: String,
    ): TerminalCompletionRequest {
        val paddingUnit = "echo x && "
        val paddingLength = (commandLineLength - prefix.length - suffix.length).coerceAtLeast(0)
        val padding =
            buildString(paddingLength) {
                while (length + paddingUnit.length <= paddingLength) append(paddingUnit)
                while (length < paddingLength) append(' ')
            }
        val commandLine = prefix + padding + suffix
        return TerminalCompletionRequest(
            commandLine = commandLine,
            cursorOffset = commandLine.length,
            shellCapabilities = TerminalShellCapabilities.POSIX,
        )
    }
}

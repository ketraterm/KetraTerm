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

import io.github.ketraterm.session.TerminalShellCommandInventory
import io.github.ketraterm.session.TerminalShellCompletionCandidate
import io.github.ketraterm.session.TerminalShellCompletionKind
import org.openjdk.jmh.annotations.*
import java.util.concurrent.TimeUnit

/** Snapshot construction and steady prefix/alias lookup have separate measurement boundaries. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@Fork(1)
open class TerminalShellCommandInventoryBenchmark {
    @Param("1000", "10000", "32768")
    @JvmField
    var count: Int = 0
    private lateinit var candidates: List<TerminalShellCompletionCandidate>
    private lateinit var inventory: TerminalShellCommandInventory

    @Setup
    open fun setUp() {
        candidates =
            List(count) { index ->
                val name = "command-${index.toString().padStart(5, '0')}"
                TerminalShellCompletionCandidate(
                    name,
                    name,
                    "",
                    TerminalShellCompletionKind.COMMAND,
                    if (index % 20 == 0) "git -C 'repository $index' status" else null,
                )
            }.reversed()
        inventory = TerminalShellCommandInventory(candidates)
    }

    /** Input records/strings already exist, as they do after chunk decoding. Includes sorting/index creation. */
    @Benchmark
    open fun buildSnapshot(): TerminalShellCommandInventory = TerminalShellCommandInventory(candidates)

    @Benchmark
    open fun prefixMatches(): List<TerminalShellCompletionCandidate> = inventory.matchingCommands("command-00", 16)

    @Benchmark
    open fun missingPrefix(): List<TerminalShellCompletionCandidate> = inventory.matchingCommands("zz-missing", 16)

    @Benchmark
    open fun aliases(): Map<String, String> = inventory.aliases
}

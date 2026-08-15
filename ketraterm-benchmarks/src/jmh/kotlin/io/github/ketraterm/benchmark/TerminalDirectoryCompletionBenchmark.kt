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

import io.github.ketraterm.completion.host.TerminalBoundedDirectoryScanner
import kotlinx.coroutines.runBlocking
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Measures bounded local directory scans at representative repository-directory sizes.
 *
 * Run this benchmark on local and representative remote-mounted worktrees before changing the completion-host defaults.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@Fork(1)
open class TerminalDirectoryCompletionBenchmark {
    @Param("256", "2048", "8192")
    var entryCount: Int = 0

    private lateinit var directory: Path
    private val scanner = TerminalBoundedDirectoryScanner()

    @Setup(Level.Trial)
    open fun setUp() {
        directory = Files.createTempDirectory("ketraterm-directory-benchmark")
        repeat(entryCount) { index ->
            Files.createFile(directory.resolve("entry-%05d".format(index)))
        }
    }

    @TearDown(Level.Trial)
    open fun tearDown() {
        Files.newDirectoryStream(directory).use { stream ->
            stream.forEach(Files::deleteIfExists)
        }
        Files.deleteIfExists(directory)
    }

    @Benchmark
    open fun scanBoundedDirectory(blackhole: Blackhole) {
        blackhole.consume(runBlocking { scanner.scan(directory, "entry-") })
    }
}

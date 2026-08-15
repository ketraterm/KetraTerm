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
import io.github.ketraterm.input.TerminalInputEncoders
import io.github.ketraterm.input.api.TerminalInputEncoder
import io.github.ketraterm.input.event.TerminalTextReplacementEvent
import io.github.ketraterm.protocol.host.TerminalHostOutput
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestion
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionAcceptance
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionHandler
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionRequest
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.Warmup
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.TimeUnit

/** Measures hostile-length suggestion planning and replacement encoding. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@Fork(1)
open class TerminalSuggestionAcceptanceBenchmark {
    private lateinit var output: CountingHostOutput
    private lateinit var encoder: TerminalInputEncoder
    private lateinit var longReplacement: TerminalTextReplacementEvent
    private lateinit var acceptanceHandler: SwingShellSuggestionHandler
    private lateinit var unicodeAcceptance: SwingShellSuggestionAcceptance

    @Setup
    open fun setUp() {
        output = CountingHostOutput()
        encoder = TerminalInputEncoders.create(TerminalBuffers.create(width = 80, height = 24), output)
        longReplacement =
            TerminalTextReplacementEvent(
                deleteAfterCursorCount = 0,
                deleteBeforeCursorCount = 4_096,
                replacementText = "replacement",
            )
        acceptanceHandler = SwingShellSuggestionHandler.createDefault(encoder)

        val commandText = "\uD83D\uDC69\u200D\uD83D\uDCBB".repeat(512)
        val request =
            SwingShellSuggestionRequest(
                commandText = commandText,
                cursorOffset = commandText.length,
                anchorColumn = 0,
                anchorRow = 0,
            )
        unicodeAcceptance =
            SwingShellSuggestionAcceptance(
                suggestion =
                    SwingShellSuggestion(
                        replacementText = "replacement",
                        replacementStartOffset = 0,
                        replacementEndOffset = commandText.length,
                        source = "benchmark",
                        kind = "ARGUMENT",
                    ),
                index = 0,
                request = request,
            )
    }

    @Benchmark
    open fun encodeLongReplacement(blackhole: Blackhole) {
        output.reset()
        encoder.encodeTextReplacement(longReplacement)
        blackhole.consume(output.byteCount)
    }

    @Benchmark
    open fun acceptLongUnicodeReplacement(blackhole: Blackhole) {
        output.reset()
        acceptanceHandler.onSuggestionAccepted(unicodeAcceptance)
        blackhole.consume(output.byteCount)
    }

    private class CountingHostOutput : TerminalHostOutput {
        var byteCount: Long = 0
            private set

        override fun writeByte(byte: Int) {
            byteCount++
        }

        override fun writeBytes(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) {
            byteCount += length
        }

        override fun writeAscii(text: String) {
            byteCount += text.length
        }

        override fun writeUtf8(text: String) {
            byteCount += text.length
        }

        fun reset() {
            byteCount = 0
        }
    }
}

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
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionRequest
import io.github.ketraterm.ui.swing.suggestion.replacementFor
import org.openjdk.jmh.annotations.*
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
    private lateinit var unicodeSuggestion: SwingShellSuggestion
    private lateinit var unicodeRequest: SwingShellSuggestionRequest

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
        val commandText = "\uD83D\uDC69\u200D\uD83D\uDCBB".repeat(512)
        unicodeRequest =
            SwingShellSuggestionRequest(
                commandText = commandText,
                cursorOffset = commandText.length,
            )
        unicodeSuggestion =
            SwingShellSuggestion(
                replacementText = "replacement",
                replacementStartOffset = 0,
                replacementEndOffset = commandText.length,
                source = "benchmark",
                kind = "ARGUMENT",
            )
    }

    @Benchmark
    open fun encodeLongReplacement(blackhole: Blackhole) {
        output.reset()
        encoder.encodeTextReplacement(longReplacement)
        blackhole.consume(output.byteCount)
    }

    @Benchmark
    open fun planAndEncodeLongUnicodeReplacement(blackhole: Blackhole) {
        output.reset()
        val replacement = checkNotNull(unicodeSuggestion.replacementFor(unicodeRequest))
        encoder.encodeTextReplacement(
            TerminalTextReplacementEvent(
                deleteAfterCursorCount = replacement.deleteAfterCursorCount,
                deleteBeforeCursorCount = replacement.deleteBeforeCursorCount,
                replacementText = replacement.replacementText,
            ),
        )
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

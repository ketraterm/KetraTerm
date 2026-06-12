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
package com.gagik.benchmark

import com.gagik.parser.api.TerminalOutputParser
import com.gagik.parser.api.TerminalParsers
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * Isolated parser throughput benchmark.
 *
 * Feeds pre-built byte arrays into the parser with a [NoOpTerminalCommandSink]
 * to measure pure parser cost (FSM transitions, UTF-8 decode, grapheme
 * assembly, CSI/SGR dispatch) without any core mutation overhead.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
open class TerminalParserBenchmark {
    @Param("ascii", "cjk", "emoji", "sgr_heavy")
    lateinit var workload: String

    private lateinit var bytes: ByteArray
    private lateinit var parser: TerminalOutputParser

    @Setup(Level.Trial)
    open fun setup() {
        parser = TerminalParsers.create(NoOpTerminalCommandSink())
        bytes =
            when (workload) {
                "ascii" -> buildParserAsciiInput()
                "cjk" -> buildParserCjkInput()
                "emoji" -> buildParserEmojiInput()
                "sgr_heavy" -> buildParserSgrHeavyInput()
                else -> error("unknown workload: $workload")
            }
    }

    @Benchmark
    open fun parseIsolated(blackhole: Blackhole) {
        parser.accept(bytes, 0, bytes.size)
        blackhole.consume(bytes.size)
    }
}

private const val PARSER_LINES = 20_000
private const val PARSER_COLUMNS = 160

/** Pure printable ASCII — tests FSM fast path and charset mapper. */
private fun buildParserAsciiInput(): ByteArray {
    val sb = StringBuilder(PARSER_LINES * (PARSER_COLUMNS + 2))
    repeat(PARSER_LINES) { row ->
        var col = 0
        while (col < PARSER_COLUMNS) {
            sb.append(('A'.code + (row + col) % 26).toChar())
            col++
        }
        sb.append('\r').append('\n')
    }
    return sb.toString().toByteArray(StandardCharsets.UTF_8)
}

/** CJK characters (U+4E00..U+9FFF) — 3-byte UTF-8, tests multibyte decode. */
private fun buildParserCjkInput(): ByteArray {
    val sb = StringBuilder(PARSER_LINES * (PARSER_COLUMNS + 2))
    repeat(PARSER_LINES) { row ->
        var col = 0
        while (col < PARSER_COLUMNS) {
            val cp = 0x4E00 + (row + col) % (0x9FFF - 0x4E00)
            sb.appendCodePoint(cp)
            col++
        }
        sb.append('\r').append('\n')
    }
    return sb.toString().toByteArray(StandardCharsets.UTF_8)
}

/**
 * Emoji with ZWJ sequences — tests grapheme segmenter and cluster buffer.
 *
 * Every 8th character is a family emoji (U+1F468 ZWJ U+1F469 ZWJ U+1F467),
 * the rest are printable ASCII.
 */
private fun buildParserEmojiInput(): ByteArray {
    val sb = StringBuilder(PARSER_LINES * (PARSER_COLUMNS * 2 + 2))
    repeat(PARSER_LINES) { row ->
        var col = 0
        while (col < PARSER_COLUMNS) {
            if ((row + col) % 8 == 0) {
                // Family emoji ZWJ sequence
                sb.appendCodePoint(0x1F468)
                sb.appendCodePoint(0x200D)
                sb.appendCodePoint(0x1F469)
                sb.appendCodePoint(0x200D)
                sb.appendCodePoint(0x1F467)
            } else {
                sb.append(('a'.code + (row + col) % 26).toChar())
            }
            col++
        }
        sb.append('\r').append('\n')
    }
    return sb.toString().toByteArray(StandardCharsets.UTF_8)
}

/**
 * SGR-heavy workload — every character is preceded by a full RGB SGR.
 *
 * Tests CSI parameter collection, SgrDispatcher throughput, and the
 * extended-color parsing path.
 */
private fun buildParserSgrHeavyInput(): ByteArray {
    val sb = StringBuilder(PARSER_LINES * (PARSER_COLUMNS * 24 + 8))
    repeat(PARSER_LINES) { row ->
        var col = 0
        while (col < PARSER_COLUMNS) {
            val r = (row + col) % 256
            val g = (row + col * 3) % 256
            val b = (row + col * 7) % 256
            sb.append("\u001b[1;38;2;")
            sb
                .append(r)
                .append(';')
                .append(g)
                .append(';')
                .append(b)
                .append('m')
            sb.append(('A'.code + (row + col) % 26).toChar())
            col++
        }
        sb.append("\u001b[0m\r\n")
    }
    return sb.toString().toByteArray(StandardCharsets.UTF_8)
}

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
import io.github.ketraterm.core.api.TerminalBuffer
import io.github.ketraterm.input.TerminalInputEncoders
import io.github.ketraterm.input.api.TerminalInputEncoder
import io.github.ketraterm.input.event.TerminalKey
import io.github.ketraterm.input.event.TerminalKeyEvent
import io.github.ketraterm.input.event.TerminalModifiers
import io.github.ketraterm.protocol.host.TerminalHostOutput
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.TimeUnit

/** Measures extended resource encoding and query generation through public APIs. */
@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
open class TerminalKeyResourceBenchmark {
    @Param("1", "2", "3", "6", "7")
    var resource: Int = 1

    @Param("0", "1")
    var format: Int = 0

    private lateinit var terminal: TerminalBuffer
    private lateinit var encoder: TerminalInputEncoder
    private lateinit var event: TerminalKeyEvent
    private val responseScratch = ByteArray(64)
    private val output = CountingOutput()

    @Setup
    fun setup() {
        terminal = TerminalBuffers.create(80, 24)
        terminal.setKeyModifierOption(resource, 4)
        terminal.setKeyFormatOption(resource, format)
        encoder = TerminalInputEncoders.create(terminal, output)
        val key =
            when (resource) {
                1 -> TerminalKey.UP
                2 -> TerminalKey.F13
                3 -> TerminalKey.NUMPAD_1
                6 -> TerminalKey.LEFT_SHIFT
                else -> TerminalKey.ENTER
            }
        event = TerminalKeyEvent.key(key, TerminalModifiers.CTRL)
    }

    @Benchmark
    fun encode(bh: Blackhole) {
        encoder.encodeKey(event)
        bh.consume(output.checksum)
    }

    @Benchmark
    fun query(bh: Blackhole) {
        terminal.requestKeyModifierOption(resource)
        terminal.requestKeyFormatOption(resource)
        val count = terminal.readResponseBytes(responseScratch)
        bh.consume(count)
        bh.consume(responseScratch)
    }

    private class CountingOutput : TerminalHostOutput {
        var checksum: Int = 0

        override fun writeByte(byte: Int) {
            checksum += byte
        }

        override fun writeBytes(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) {
            for (index in offset until offset + length) checksum += bytes[index]
        }

        override fun writeAscii(text: String) {
            error("Generated keys must use byte scratch")
        }

        override fun writeUtf8(text: String) {
            error("Generated keys must use byte scratch")
        }
    }
}

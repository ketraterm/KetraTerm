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
import io.github.ketraterm.input.event.TerminalKeyEvent
import io.github.ketraterm.input.event.TerminalPasteEvent
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.transport.TerminalConnector
import io.github.ketraterm.transport.TerminalConnectorListener
import org.openjdk.jmh.annotations.*
import java.util.concurrent.TimeUnit

/** Includes transport completion, so asynchronous output cannot inflate acceptance throughput. */
@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
open class TerminalSessionOutputBenchmark {
    private lateinit var session: TerminalSession
    private lateinit var connector: CountingConnector
    private val key = TerminalKeyEvent.codepoint('a'.code)
    private val paste = TerminalPasteEvent("0123456789abcdef".repeat(4096))

    @Setup
    fun setup() {
        connector = CountingConnector()
        session = TerminalSession.create(TerminalBuffers.create(80, 24), connector)
        session.start(80, 24)
    }

    @TearDown
    fun close() = session.close()

    @Benchmark
    @OperationsPerInvocation(64)
    fun keys(): Long {
        val expected = connector.byteCount + 64
        repeat(64) { session.encodeKey(key) }
        return awaitOutput(expected)
    }

    @Benchmark
    fun paste64KiB(): Long {
        val expected = connector.byteCount + paste.text.length
        session.encodePaste(paste)
        return awaitOutput(expected)
    }

    private fun awaitOutput(expected: Long): Long {
        while (connector.byteCount < expected) {
            check(!session.isClosed) { "Output failed: ${session.failure}" }
            Thread.onSpinWait()
        }
        return connector.byteCount
    }

    private class CountingConnector : TerminalConnector {
        @Volatile var byteCount = 0L
            private set

        override fun start(listener: TerminalConnectorListener) = Unit

        override fun write(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) {
            byteCount += length
        }

        override fun resize(
            columns: Int,
            rows: Int,
        ) = Unit

        override fun close() = Unit
    }
}

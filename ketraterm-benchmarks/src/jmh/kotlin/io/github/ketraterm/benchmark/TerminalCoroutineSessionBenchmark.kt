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
import io.github.ketraterm.render.cache.TerminalRenderCache
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.transport.TerminalConnector
import io.github.ketraterm.transport.TerminalConnectorListener
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

@State(Scope.Benchmark)
open class CoroutineSessionState {
    @Param("1", "8", "32")
    var sessionCount: Int = 0

    lateinit var sessions: Array<TerminalSession>
    lateinit var connectors: Array<BenchmarkConnector>
    lateinit var swingCaches: Array<TerminalRenderCache>
    val hostBytes: ByteArray = "0123456789abcdef\r\n".toByteArray(StandardCharsets.US_ASCII)

    @Setup(Level.Trial)
    open fun setup() {
        connectors = Array(sessionCount) { BenchmarkConnector() }
        sessions =
            Array(sessionCount) { index ->
                val terminal = TerminalBuffers.create(width = COLUMNS, height = ROWS, maxHistory = 1_000)
                TerminalSession.create(terminal, connectors[index]).also { session ->
                    session.start(COLUMNS, ROWS)
                    requestAndAwait(session)
                }
            }
        swingCaches = Array(sessionCount) { TerminalRenderCache(COLUMNS, ROWS, rowCapacityReserve = 2) }
    }

    @TearDown(Level.Trial)
    open fun tearDown() {
        sessions.forEach(TerminalSession::close)
    }

    fun requestAndAwait(session: TerminalSession): Long {
        val previous = session.renderGeneration.value
        session.requestRender(scrollbackOffset = 0)
        while (session.renderGeneration.value <= previous) {
            Thread.onSpinWait()
        }
        return session.renderGeneration.value
    }

    companion object {
        const val COLUMNS = 80
        const val ROWS = 24
    }
}

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
open class TerminalCoroutineSessionBenchmark {
    @Benchmark
    open fun ingestHostBytesAndInvalidate(
        state: CoroutineSessionState,
        blackhole: Blackhole,
    ) {
        var index = 0
        while (index < state.sessionCount) {
            state.connectors[index].feed(state.hostBytes)
            blackhole.consume(state.sessions[index].renderGeneration.value)
            index++
        }
    }

    @Benchmark
    open fun publishRequestedViewport(
        state: CoroutineSessionState,
        blackhole: Blackhole,
    ) {
        var index = 0
        while (index < state.sessionCount) {
            blackhole.consume(state.requestAndAwait(state.sessions[index]))
            index++
        }
    }

    @Benchmark
    open fun consumePublishedCaches(
        state: CoroutineSessionState,
        blackhole: Blackhole,
    ) {
        var index = 0
        while (index < state.sessionCount) {
            state.sessions[index].renderPublisher.readCurrent { published ->
                state.swingCaches[index].updateFrom(published)
            }
            blackhole.consume(state.swingCaches[index].frameGeneration)
            index++
        }
    }
}

class BenchmarkConnector : TerminalConnector {
    private lateinit var listener: TerminalConnectorListener

    override fun start(listener: TerminalConnectorListener) {
        this.listener = listener
    }

    fun feed(bytes: ByteArray) {
        listener.onBytes(bytes, 0, bytes.size)
    }

    override fun write(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ) = Unit

    override fun resize(
        columns: Int,
        rows: Int,
    ) = Unit

    override fun close() = Unit
}

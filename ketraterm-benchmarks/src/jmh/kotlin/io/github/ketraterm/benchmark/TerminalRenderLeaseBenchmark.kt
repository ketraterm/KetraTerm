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
import io.github.ketraterm.render.cache.TerminalRenderPublisher
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.session.TerminalShellIntegrationFactory
import io.github.ketraterm.session.TerminalShellIntegrationState
import io.github.ketraterm.session.TerminalShellIntegrationView
import io.github.ketraterm.transport.TerminalConnector
import io.github.ketraterm.transport.TerminalConnectorListener
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.TimeUnit

/** Measures scoped leases and primitive shell projection, with an allocating control and no Swing painting. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
open class TerminalRenderLeaseBenchmark {
    @Param("empty", "published")
    lateinit var frame: String

    private lateinit var publisher: TerminalRenderPublisher
    private lateinit var session: TerminalSession
    private lateinit var shell: TerminalShellIntegrationView
    private val lineIds = LongArray(24) { it.toLong() + 1 }
    private val promptStarts = BooleanArray(24)
    private val commandStarts = BooleanArray(24)
    private val commandEnds = BooleanArray(24)
    private val recordIds = IntArray(24)
    private val lifecycles = IntArray(24)

    @Setup
    fun setup() {
        publisher = TerminalRenderPublisher(80, 24)
        val shellProducer = TerminalShellIntegrationState()
        shellProducer.recordPromptStart(1)
        shellProducer.recordCommandStart(2, includeLine = true)
        shellProducer.recordCommandFinished(24, exitCode = 0)
        val sessionBuffer = TerminalBuffers.create(80, 24, 0)
        sessionBuffer.writeCodepoint('A'.code)
        session = TerminalSession.create(sessionBuffer, Connector, shellIntegration = TerminalShellIntegrationFactory.host(shellProducer))
        shell = session.shellIntegrationState
        if (frame == "published") {
            val buffer = TerminalBuffers.create(80, 24, 0)
            buffer.writeCodepoint('A'.code)
            publisher.updateAndPublish(buffer)
            session.requestRender(0)
            runBlocking { session.renderGeneration.first { it >= 0 } }
        }
    }

    @Benchmark
    fun readLease(blackhole: Blackhole) {
        publisher.readCurrent { cache -> blackhole.consume(cache.codeWords[0]) }
    }

    @Benchmark
    fun readSessionLease(blackhole: Blackhole) {
        session.readPublishedFrame { cache -> blackhole.consume(cache.codeWords[0]) }
    }

    @Benchmark
    fun projectShellView(blackhole: Blackhole) {
        shell.copyViewport(lineIds, 24, promptStarts, commandStarts, commandEnds, recordIds, lifecycles)
        blackhole.consume(recordIds[1])
    }

    /** Deliberately escaping capture demonstrates the GC profiler detects a per-call object. */
    @Benchmark
    fun allocatingCallbackControl(blackhole: Blackhole) {
        val callback: (TerminalRenderCache) -> Unit = { cache -> blackhole.consume(cache.codeWords[0]) }
        blackhole.consume(callback)
        publisher.readCurrent(callback)
    }

    @TearDown
    fun tearDown() {
        session.close()
    }

    private object Connector : TerminalConnector {
        override fun start(listener: TerminalConnectorListener) = Unit

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
}

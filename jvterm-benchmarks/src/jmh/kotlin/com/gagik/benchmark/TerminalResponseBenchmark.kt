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

import com.gagik.core.TerminalBuffers
import com.gagik.core.api.TerminalBufferApi
import com.gagik.core.api.TerminalResponseChannel
import com.gagik.core.model.AttributeColor
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.TimeUnit

/**
 * Benchmarks the terminal response channel query throughput.
 *
 * Measures the allocation profile and throughput of DECRQSS, XTGETTCAP,
 * DSR, and DA queries. Each benchmark calls a query method, drains the
 * response queue, and blackholes the byte count. The `gc` profiler reports
 * `gc.alloc.rate.norm` (B/op) to verify allocation-free response generation.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
open class TerminalResponseBenchmark {
    private lateinit var buffer: TerminalBufferApi
    private lateinit var complexBuffer: TerminalBufferApi
    private lateinit var drainBuffer: ByteArray

    @Setup(Level.Trial)
    open fun setup() {
        drainBuffer = ByteArray(512)

        buffer = TerminalBuffers.create(width = 80, height = 24)

        // Pre-configure a terminal with complex pen for DECRQSS SGR worst case.
        complexBuffer = TerminalBuffers.create(width = 80, height = 24)
        complexBuffer.setPenColors(
            foreground = AttributeColor.rgb(0xFF, 0x00, 0x00),
            background = AttributeColor.rgb(0x00, 0xFF, 0x00),
            underlineColor = AttributeColor.rgb(0x00, 0x00, 0xFF),
            bold = true,
            italic = true,
            strikethrough = true,
        )
    }

    // -- DECRQSS benchmarks --

    /** DECRQSS SGR query with default pen — minimal response (`0m`). */
    @Benchmark
    open fun queryDecrqssSgrDefault(bh: Blackhole) {
        buffer.queryStatusString("m")
        bh.consume(buffer.readResponseBytes(drainBuffer))
    }

    /** DECRQSS SGR query with complex pen — worst-case SGR serialization. */
    @Benchmark
    open fun queryDecrqssSgrComplex(bh: Blackhole) {
        complexBuffer.queryStatusString("m")
        bh.consume(complexBuffer.readResponseBytes(drainBuffer))
    }

    /** DECRQSS scroll margins query. */
    @Benchmark
    open fun queryDecrqssMargins(bh: Blackhole) {
        buffer.queryStatusString("r")
        bh.consume(buffer.readResponseBytes(drainBuffer))
    }

    /** DECRQSS unsupported query — rejection path. */
    @Benchmark
    open fun queryDecrqssRejection(bh: Blackhole) {
        buffer.queryStatusString("invalid")
        bh.consume(buffer.readResponseBytes(drainBuffer))
    }

    // -- XTGETTCAP benchmarks --

    /** XTGETTCAP single capability query (Co → 256). */
    @Benchmark
    open fun queryXtgettcapSingle(bh: Blackhole) {
        buffer.queryTerminfo("436f")
        bh.consume(buffer.readResponseBytes(drainBuffer))
    }

    /** XTGETTCAP multi-capability query (Co;TN;RGB;Tc). */
    @Benchmark
    open fun queryXtgettcapMulti(bh: Blackhole) {
        buffer.queryTerminfo("436f;544e;524742;5463")
        bh.consume(buffer.readResponseBytes(drainBuffer))
    }

    /** XTGETTCAP unsupported capability — rejection path. */
    @Benchmark
    open fun queryXtgettcapRejection(bh: Blackhole) {
        buffer.queryTerminfo("7878")
        bh.consume(buffer.readResponseBytes(drainBuffer))
    }

    // -- DSR / DA benchmarks --

    /** Device Status Report — cursor position (mode 6). */
    @Benchmark
    open fun queryDsr(bh: Blackhole) {
        buffer.requestDeviceStatusReport(6, false)
        bh.consume(buffer.readResponseBytes(drainBuffer))
    }

    /** Primary Device Attributes (DA1). */
    @Benchmark
    open fun queryDa1(bh: Blackhole) {
        buffer.requestDeviceAttributes(TerminalResponseChannel.DEVICE_ATTRIBUTES_PRIMARY, 0)
        bh.consume(buffer.readResponseBytes(drainBuffer))
    }
}

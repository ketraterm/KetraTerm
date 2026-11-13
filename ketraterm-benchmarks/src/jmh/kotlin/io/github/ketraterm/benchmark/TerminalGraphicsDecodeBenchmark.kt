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
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.transport.TerminalConnector
import io.github.ketraterm.transport.TerminalConnectorListener
import kotlinx.coroutines.Dispatchers
import org.openjdk.jmh.annotations.*
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

/** Synchronous session ingress, including decode; payload generation occurs only in setup. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
open class TerminalGraphicsDecodeBenchmark {
    /** Text control, ordinary RGBA PNG, or 4K sixteen-bit Adam7 PNG. */
    @Param("text", "png8", "png16Adam7")
    var workload: String = "text"
    private lateinit var session: TerminalSession
    private lateinit var input: ByteArray
    private val replies = ByteArrayOutputStream()

    /** Constructs fixed bytes and one session whose graphics queries retain no images. */
    @Setup
    fun setup() {
        input =
            if (workload == "text") {
                "\u001b[H0123456789abcdef".repeat(256).encodeToByteArray()
            } else {
                val interlaced = workload == "png16Adam7"
                val png =
                    png(
                        if (interlaced) 3840 else 512,
                        if (interlaced) 2160 else 512,
                        if (interlaced) 16 else 8,
                        interlaced,
                    )
                val payload = Base64.getEncoder().encodeToString(png)
                buildString {
                    val chunks = payload.chunked(64 * 1024)
                    for ((index, chunk) in chunks.withIndex()) {
                        val more = if (index == chunks.lastIndex) 0 else 1
                        val controls = if (index == 0) "a=q,f=100,i=1" else "a=q"
                        append("\u001b_G$controls,q=2,m=$more;$chunk\u001b\\")
                    }
                }.encodeToByteArray()
            }
        session =
            TerminalSession.create(
                TerminalBuffers.create(80, 24),
                object : TerminalConnector {
                    override fun start(listener: TerminalConnectorListener) = Unit

                    override fun write(
                        bytes: ByteArray,
                        offset: Int,
                        length: Int,
                    ) {
                        replies.write(bytes, offset, length)
                    }

                    override fun resize(
                        columns: Int,
                        rows: Int,
                    ) = Unit

                    override fun close() = Unit
                },
                workerDispatcher = Dispatchers.Unconfined,
                ioDispatcher = Dispatchers.Unconfined,
                cleanupDispatcher = Dispatchers.Unconfined,
            )
        session.start(80, 24)
        if (workload != "text") {
            val probe = input.decodeToString().replace("q=2", "q=0").encodeToByteArray()
            session.onBytes(probe, 0, probe.size)
            check(replies.toString(Charsets.UTF_8) == "\u001b_Gi=1;OK\u001b\\") {
                "Benchmark query must perform a successful decode: ${replies.toString(Charsets.UTF_8)}"
            }
            replies.reset()
        }
    }

    /** Includes decoding, protocol validation, and synchronous render publication. */
    @Benchmark
    fun consume() {
        session.onBytes(input, 0, input.size)
    }

    /** Releases session-owned jobs after each trial. */
    @TearDown
    fun close() {
        try {
            check(!session.isClosed && replies.size() == 0) { "Quiet workload failed or emitted a reply" }
        } finally {
            session.close()
        }
    }

    private fun png(
        width: Int,
        height: Int,
        depth: Int,
        interlaced: Boolean,
    ): ByteArray {
        val compressed = ByteArrayOutputStream()
        DeflaterOutputStream(compressed).use { output ->
            val passes =
                if (!interlaced) {
                    listOf(intArrayOf(0, 0, 1, 1))
                } else {
                    listOf(
                        intArrayOf(0, 0, 8, 8),
                        intArrayOf(4, 0, 8, 8),
                        intArrayOf(0, 4, 4, 8),
                        intArrayOf(2, 0, 4, 4),
                        intArrayOf(0, 2, 2, 4),
                        intArrayOf(1, 0, 2, 2),
                        intArrayOf(0, 1, 1, 2),
                    )
                }
            for ((startX, startY, stepX, stepY) in passes) {
                val pixels = (width - startX + stepX - 1) / stepX
                if (pixels <= 0) continue
                val row = ByteArray(1 + pixels * 4 * (depth / 8))
                for (y in startY until height step stepY) {
                    var offset = 1
                    for (x in startX until width step stepX) {
                        for (channel in 0..3) {
                            row[offset++] = (x * 13 + y * 7 + channel * 41).toByte()
                            if (depth == 16) row[offset++] = (x + y + channel).toByte()
                        }
                    }
                    output.write(row)
                }
            }
        }
        return ByteArrayOutputStream().use { file ->
            DataOutputStream(file).use { output ->
                output.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
                val header = ByteArrayOutputStream()
                DataOutputStream(header).use {
                    it.writeInt(width)
                    it.writeInt(height)
                    it.write(byteArrayOf(depth.toByte(), 6, 0, 0, if (interlaced) 1 else 0))
                }
                chunk(output, "IHDR", header.toByteArray())
                chunk(output, "IDAT", compressed.toByteArray())
                chunk(output, "IEND", byteArrayOf())
            }
            file.toByteArray()
        }
    }

    private fun chunk(
        output: DataOutputStream,
        name: String,
        data: ByteArray,
    ) {
        val type = name.encodeToByteArray()
        val crc = CRC32()
        crc.update(type)
        crc.update(data)
        output.writeInt(data.size)
        output.write(type)
        output.write(data)
        output.writeInt(crc.value.toInt())
    }
}

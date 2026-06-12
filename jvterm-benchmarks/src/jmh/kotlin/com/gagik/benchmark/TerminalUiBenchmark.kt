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
import com.gagik.integration.CoreTerminalCommandSink
import com.gagik.parser.api.TerminalParsers
import com.gagik.terminal.render.api.TerminalRenderFrameReader
import com.gagik.terminal.render.cache.TerminalRenderPublisher
import com.gagik.terminal.session.TerminalSession
import com.gagik.terminal.transport.TerminalConnector
import com.gagik.terminal.transport.TerminalConnectorListener
import com.gagik.terminal.ui.swing.api.TerminalSwingTerminal
import com.gagik.terminal.ui.swing.settings.TerminalSwingSettings
import com.gagik.terminal.ui.swing.settings.TerminalSwingSettingsProvider
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import java.awt.Graphics2D
import java.awt.image.BufferedImage
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
open class TerminalLargeInputBenchmark {
    @Param("ascii", "styled", "cjk", "emoji")
    lateinit var workload: String

    private lateinit var bytes: ByteArray
    private lateinit var terminal: TerminalBufferApi
    private lateinit var renderReader: TerminalRenderFrameReader
    private lateinit var publisher: TerminalRenderPublisher

    @Setup(Level.Trial)
    open fun setupBytes() {
        bytes =
            when (workload) {
                "ascii" -> buildAsciiInput(LARGE_INPUT_LINES, LARGE_INPUT_COLUMNS)
                "styled" -> buildStyledInput(LARGE_INPUT_LINES, LARGE_INPUT_COLUMNS)
                "cjk" -> buildCjkInput(LARGE_INPUT_LINES, LARGE_INPUT_COLUMNS)
                "emoji" -> buildEmojiInput(LARGE_INPUT_LINES, LARGE_INPUT_COLUMNS)
                else -> error("unknown workload: $workload")
            }
    }

    @Setup(Level.Trial)
    open fun setupTerminal() {
        terminal =
            TerminalBuffers.create(
                width = LARGE_INPUT_COLUMNS,
                height = LARGE_INPUT_ROWS,
                maxHistory = LARGE_INPUT_LINES,
            )
        renderReader = terminal as TerminalRenderFrameReader
        publisher = TerminalRenderPublisher(LARGE_INPUT_COLUMNS, LARGE_INPUT_ROWS)
    }

    @Setup(Level.Invocation)
    open fun clearTerminal() {
        terminal.clearAll()
    }

    @Benchmark
    open fun parseCoreAndPublishLargeInput(blackhole: Blackhole) {
        val parser = TerminalParsers.create(CoreTerminalCommandSink(terminal))
        parser.accept(bytes, 0, bytes.size)
        publisher.updateAndPublish(renderReader)
        blackhole.consume(bytes.size)
        blackhole.consume(publisher.current()?.frameGeneration)
    }
}

@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
open class TerminalRenderPublishBenchmark {
    @Param("0", "5000", "15000")
    var scrollbackOffset: Int = 0

    private lateinit var renderReader: TerminalRenderFrameReader
    private lateinit var publisher: TerminalRenderPublisher

    @Setup(Level.Trial)
    open fun setupTerminal() {
        val terminal =
            TerminalBuffers.create(
                width = LARGE_INPUT_COLUMNS,
                height = LARGE_INPUT_ROWS,
                maxHistory = LARGE_INPUT_LINES,
            )
        writeScrollbackContent(terminal, LARGE_INPUT_LINES, LARGE_INPUT_COLUMNS)
        renderReader = terminal as TerminalRenderFrameReader
    }

    @Setup(Level.Trial)
    open fun setupPublisher() {
        publisher = TerminalRenderPublisher(LARGE_INPUT_COLUMNS, LARGE_INPUT_ROWS)
    }

    @Benchmark
    open fun publishViewportFromLargeScrollback(blackhole: Blackhole) {
        publisher.updateAndPublish(renderReader, scrollbackOffset)
        val cache = publisher.current()
        blackhole.consume(cache?.scrollbackOffset)
        val generations = cache?.lineGenerations
        if (generations != null) {
            var checksum = 0L
            var row = 0
            while (row < generations.size) {
                checksum = checksum xor generations[row]
                row++
            }
            blackhole.consume(checksum)
        }
    }
}

@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
open class TerminalSwingPaintBenchmark {
    @Param("80", "160", "240")
    var columns: Int = 0

    @Param("24", "48")
    var rows: Int = 0

    @Param("ascii", "styled", "clusters")
    lateinit var workload: String

    private lateinit var terminal: TerminalBufferApi
    private lateinit var session: TerminalSession
    private lateinit var component: TerminalSwingTerminal
    private lateinit var image: BufferedImage
    private lateinit var graphics: Graphics2D

    @Setup
    open fun setup() {
        terminal = TerminalBuffers.create(columns, rows, maxHistory = rows)
        when (workload) {
            "ascii" -> writeAsciiViewport(terminal, rows, columns)
            "styled" -> writeStyledViewport(terminal, rows, columns)
            "clusters" -> writeClusterViewport(terminal, rows, columns)
            else -> error("unknown workload: $workload")
        }

        session = benchmarkSession(terminal)
        session.publisher.updateAndPublish(terminal as TerminalRenderFrameReader)

        component =
            TerminalSwingTerminal(
                TerminalSwingSettingsProvider {
                    TerminalSwingSettings(
                        columns = columns,
                        rows = rows,
                        useSystemFallbackFonts = false,
                    )
                },
            )
        component.bind(session)
        component.size = component.preferredSize

        image = BufferedImage(component.width, component.height, BufferedImage.TYPE_INT_ARGB)
        graphics = image.createGraphics()
    }

    @TearDown
    open fun tearDown() {
        graphics.dispose()
        session.close()
    }

    @Benchmark
    open fun paintPublishedFrame(blackhole: Blackhole) {
        component.paint(graphics)
        blackhole.consume(image.getRGB(component.width - 1, component.height - 1))
    }
}

private const val LARGE_INPUT_COLUMNS = 160
private const val LARGE_INPUT_ROWS = 48
private const val LARGE_INPUT_LINES = 20_000

private fun buildAsciiInput(
    lines: Int,
    columns: Int,
): ByteArray {
    val builder = StringBuilder(lines * (columns + 2))
    repeat(lines) { row ->
        appendLinePayload(builder, row, columns)
    }
    return builder.toString().toByteArray(StandardCharsets.UTF_8)
}

private fun buildStyledInput(
    lines: Int,
    columns: Int,
): ByteArray {
    val builder = StringBuilder(lines * (columns + 16))
    repeat(lines) { row ->
        builder.append("\u001b[3").append(row % 8).append('m')
        appendLinePayload(builder, row, columns)
        builder.append("\u001b[0m")
    }
    return builder.toString().toByteArray(StandardCharsets.UTF_8)
}

private fun appendLinePayload(
    builder: StringBuilder,
    row: Int,
    columns: Int,
) {
    var column = 0
    while (column < columns) {
        val codepoint = 'A'.code + ((row + column) % 26)
        builder.append(codepoint.toChar())
        column++
    }
    builder.append('\r').append('\n')
}

private fun writeScrollbackContent(
    terminal: TerminalBufferApi,
    lines: Int,
    columns: Int,
) {
    repeat(lines) { row ->
        var column = 0
        while (column < columns) {
            terminal.writeCodepoint('0'.code + ((row + column) % 10))
            column++
        }
        terminal.carriageReturn()
        terminal.newLine()
    }
}

private fun writeAsciiViewport(
    terminal: TerminalBufferApi,
    rows: Int,
    columns: Int,
) {
    for (row in 0 until rows) {
        terminal.positionCursor(0, row)
        var column = 0
        while (column < columns) {
            terminal.writeCodepoint('a'.code + ((row + column) % 26))
            column++
        }
    }
}

private fun writeStyledViewport(
    terminal: TerminalBufferApi,
    rows: Int,
    columns: Int,
) {
    for (row in 0 until rows) {
        terminal.positionCursor(0, row)
        terminal.setPenAttributes(
            fg = row % 16 + 1,
            bg = (row / 2) % 16 + 1,
            bold = row % 3 == 0,
            italic = row % 5 == 0,
            strikethrough = row % 7 == 0,
        )
        var column = 0
        while (column < columns) {
            terminal.writeCodepoint('A'.code + ((row + column) % 26))
            column++
        }
    }
    terminal.resetPen()
}

/** CJK 3-byte UTF-8 characters — wide char width calculation end-to-end. */
private fun buildCjkInput(
    lines: Int,
    columns: Int,
): ByteArray {
    val sb = StringBuilder(lines * (columns + 2))
    repeat(lines) { row ->
        var col = 0
        while (col < columns) {
            val cp = 0x4E00 + (row + col) % (0x9FFF - 0x4E00)
            sb.appendCodePoint(cp)
            col++
        }
        sb.append('\r').append('\n')
    }
    return sb.toString().toByteArray(StandardCharsets.UTF_8)
}

/** Emoji with ZWJ sequences — grapheme segmenter and cluster store end-to-end. */
private fun buildEmojiInput(
    lines: Int,
    columns: Int,
): ByteArray {
    val sb = StringBuilder(lines * (columns * 2 + 2))
    repeat(lines) { row ->
        var col = 0
        while (col < columns) {
            if ((row + col) % 8 == 0) {
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

private fun writeClusterViewport(
    terminal: TerminalBufferApi,
    rows: Int,
    columns: Int,
) {
    val accentedE = intArrayOf('e'.code, 0x0301)
    for (row in 0 until rows) {
        terminal.positionCursor(0, row)
        var column = 0
        while (column < columns) {
            if ((row + column) % 8 == 0) {
                terminal.writeCluster(accentedE)
            } else {
                terminal.writeCodepoint('a'.code + ((row + column) % 26))
            }
            column++
        }
    }
}

private fun benchmarkSession(terminal: TerminalBufferApi): TerminalSession =
    TerminalSession.create(
        terminal = terminal,
        connector = NoOpTerminalConnector,
    )

private object NoOpTerminalConnector : TerminalConnector {
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

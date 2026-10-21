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
import io.github.ketraterm.core.api.TerminalRenderBuffer
import io.github.ketraterm.host.HostCommandAdapter
import io.github.ketraterm.parser.api.TerminalOutputParser
import io.github.ketraterm.parser.api.TerminalParsers
import io.github.ketraterm.render.cache.TerminalRenderCache
import io.github.ketraterm.render.cache.TerminalRenderPublisher
import io.github.ketraterm.ui.swing.render.GraphicsRasterCache
import org.openjdk.jmh.annotations.*
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * Allocation-permitted raw RGBA replacement, publication and Swing raster preparation.
 * Each invocation transmits new pixels under the same image ID, replaces its placement,
 * copies the published frame and builds a new raster. Upload bytes and Base64 encoding
 * are prepared once; parsing, staging growth, canonical pixels, pressure eviction and
 * raster construction remain measured. PNG/zlib decoding and painting are excluded.
 *
 * @property side source image width and height in pixels.
 */
@State(Scope.Thread)
@Threads(1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
open class TerminalGraphicsReplacementBenchmark {
    @Param("64", "512")
    var side: Int = 64
    private lateinit var terminal: TerminalRenderBuffer
    private lateinit var parser: TerminalOutputParser
    private lateinit var publisher: TerminalRenderPublisher
    private lateinit var cache: TerminalRenderCache
    private lateinit var rasters: GraphicsRasterCache
    private lateinit var uploads: Array<ByteArray>
    private var next = 0

    /** Prepares two complete chunked uploads and verifies replacement changes pixel identity and color. */
    @Setup
    fun setup() {
        terminal = TerminalBuffers.create(80, 60)
        terminal.resize(80, 60, 0, 640, 960)
        parser = TerminalParsers.create(HostCommandAdapter(terminal))
        publisher = TerminalRenderPublisher(80, 60)
        cache = TerminalRenderCache(80, 60)
        rasters = GraphicsRasterCache()
        uploads = arrayOf(upload(red = true), upload(red = false))
        val first = replaceAndPrepare()
        val second = replaceAndPrepare()
        check(first !== second)
        check(first.getRGB(0, 0) == Color.RED.rgb)
        check(second.getRGB(0, 0) == Color.GREEN.rgb)
        check(cache.images.size == 1)
    }

    /**
     * Replaces the existing image and prepares the resulting frame's new raster.
     *
     * @return the newly constructed raster, consumed by JMH to keep the work observable.
     */
    @Benchmark
    fun replaceAndPrepare(): BufferedImage {
        parser.accept(uploads[next])
        next = next xor 1
        publisher.updateAndPublish(terminal)
        publisher.readCurrent { cache.updateFrom(it) }
        rasters.prepare(cache.images)
        return rasters.image(0)
    }

    /** Releases cached resources after the trial. */
    @TearDown
    fun close() = rasters.clear()

    private fun upload(red: Boolean): ByteArray {
        val pixels = ByteArray(side * side * 4)
        for (offset in pixels.indices step 4) {
            pixels[offset + (if (red) 0 else 1)] = 255.toByte()
            pixels[offset + 3] = 255.toByte()
        }
        val encoded = Base64.getEncoder().encode(pixels)
        val stream = ByteArrayOutputStream()
        var offset = 0
        while (offset < encoded.size) {
            val count = minOf(4096, encoded.size - offset)
            val more = if (offset + count < encoded.size) 1 else 0
            val controls =
                if (offset == 0) "a=T,i=7,f=32,s=$side,v=$side,C=1,c=40,r=30,q=2,m=$more" else "m=$more"
            stream.write("\u001b_G$controls;".encodeToByteArray())
            stream.write(encoded, offset, count)
            stream.write("\u001b\\".encodeToByteArray())
            offset += count
        }
        return stream.toByteArray()
    }
}

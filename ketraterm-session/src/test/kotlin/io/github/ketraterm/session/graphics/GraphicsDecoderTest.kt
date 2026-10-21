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
package io.github.ketraterm.session.graphics

import io.github.ketraterm.protocol.TerminalGraphicsCommand
import io.github.ketraterm.protocol.TerminalGraphicsLimits
import io.github.ketraterm.protocol.TerminalGraphicsResult
import io.github.ketraterm.render.api.TerminalRenderImage
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import kotlin.test.*

class GraphicsDecoderTest {
    private val decoder = GraphicsDecoder()

    @Test
    fun `raw RGB and RGBA use exact samples and keep pixels independent of staging`() {
        val rgb = bytes(1, 2, 3, 254, 128, 64)
        val rgbImage = success(command('f' to 24, 's' to 2, 'v' to 1), rgb)
        rgb.fill(0)
        assertPixels(rgbImage, 0xff010203.toInt(), 0xfffe8040.toInt())
        val rgba = success(command('s' to 2, 'v' to 1), bytes(1, 2, 3, 0, 255, 128, 64, 127))
        assertPixels(rgba, 0x00010203, 0x7fff8040)
        val copy = IntArray(4) { -1 }
        rgba.copyArgb(copy, 1)
        assertContentEquals(intArrayOf(-1, 0x00010203, 0x7fff8040, -1), copy)
        copy.fill(0)
        assertPixels(rgba, 0x00010203, 0x7fff8040)
        assertFailsWith<IllegalArgumentException> { rgba.copyArgb(IntArray(2), -1) }
        assertFailsWith<IllegalArgumentException> { rgba.copyArgb(IntArray(2), 1) }
    }

    @Test
    fun `zlib RGB and RGBA require complete exact streams`() {
        for (format in listOf(24L, 32L)) {
            val controls = command('f' to format, 's' to 1, 'v' to 1, 'o' to 'z'.code.toLong())
            val raw = if (format == 24L) bytes(9, 8, 7) else bytes(9, 8, 7, 6)
            val compressed = zlib(raw)
            assertPixels(success(controls, compressed), if (format == 24L) 0xff090807.toInt() else 0x06090807)
            failure(controls, compressed.copyOf(compressed.size - 1), TerminalGraphicsResult.INVALID)
            failure(controls, compressed + byteArrayOf(0), TerminalGraphicsResult.INVALID)
            failure(controls, compressed + compressed, TerminalGraphicsResult.INVALID)
            failure(controls, zlib(raw + byteArrayOf(0)), TerminalGraphicsResult.INVALID)
            failure(controls, zlib(raw.copyOf(raw.size - 1)), TerminalGraphicsResult.INVALID)
            failure(
                controls,
                compressed.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() },
                TerminalGraphicsResult.INVALID,
            )
        }
    }

    @Test
    fun `raw dimensions and payload bounds reject before allocation`() {
        val empty = byteArrayOf()
        failure(command('s' to 0, 'v' to 1), empty, TerminalGraphicsResult.INVALID)
        failure(command('s' to -1, 'v' to 1), empty, TerminalGraphicsResult.INVALID)
        failure(command('s' to 8193, 'v' to 1), empty, TerminalGraphicsResult.TOO_LARGE)
        failure(command('s' to 8192, 'v' to 1025), empty, TerminalGraphicsResult.TOO_LARGE)
        failure(command('s' to Long.MAX_VALUE, 'v' to Long.MAX_VALUE), empty, TerminalGraphicsResult.TOO_LARGE)
        failure(command('s' to 1, 'v' to 1), bytes(1, 2, 3), TerminalGraphicsResult.INVALID)
        failure(command('s' to 1, 'v' to 1), bytes(1, 2, 3, 4, 5), TerminalGraphicsResult.INVALID)
        failure(command('f' to 64), empty, TerminalGraphicsResult.UNSUPPORTED)
        failure(command('f' to 100, 't' to 'f'.code.toLong()), empty, TerminalGraphicsResult.UNSUPPORTED)
        failure(command('f' to 100, 'o' to 'x'.code.toLong()), empty, TerminalGraphicsResult.UNSUPPORTED)
        assertFailsWith<IllegalArgumentException> { decoder.decode(command(), empty, 1) }
        assertFailsWith<IllegalArgumentException> { decoder.decode(command(), empty, -1) }
    }

    @Test
    fun `PNG grayscale samples avoid color conversion at every bit depth`() {
        for ((depth, sample, encoded, expected) in listOf(
            GrayCase(1, 1, bytes(0x80), 255),
            GrayCase(2, 2, bytes(0x80), 170),
            GrayCase(4, 8, bytes(0x80), 136),
            GrayCase(8, 128, bytes(0x80), 128),
            GrayCase(16, 0x8123, bytes(0x81, 0x23), 129),
        )) {
            val opaque = (255 shl 24) or (expected shl 16) or (expected shl 8) or expected
            assertPixels(success(pngCommand, png(depth, 0, encoded)), opaque)
            assertPixels(success(pngCommand, png(depth, 0, encoded, transparency = shorts(sample))), opaque and 0xffffff)
            assertPixels(success(pngCommand, png(depth, 0, encoded, transparency = shorts(sample - 1))), opaque)
        }
    }

    @Test
    fun `PNG grayscale transparency masks unused key bits at subbyte and eight bit depths`() {
        for ((depth, sample, encoded, expected) in listOf(
            GrayCase(1, 1, bytes(0x80), 255),
            GrayCase(2, 2, bytes(0x80), 170),
            GrayCase(4, 8, bytes(0x80), 136),
            GrayCase(8, 128, bytes(0x80), 128),
        )) {
            val unusedBits = (0xffff shl depth) and 0xffff
            val opaque = (255 shl 24) or (expected shl 16) or (expected shl 8) or expected
            assertPixels(
                success(pngCommand, png(depth, 0, encoded, transparency = shorts(sample or unusedBits))),
                opaque and 0xffffff,
            )
            assertPixels(
                success(pngCommand, png(depth, 0, encoded, transparency = shorts((sample - 1) or unusedBits))),
                opaque,
            )
        }
    }

    @Test
    fun `PNG eight bit truecolor transparency masks every key channel without masking pixel differences`() {
        val row = bytes(18, 86, 154, 19, 86, 154, 18, 87, 154, 18, 86, 155)
        val input = png(8, 2, row, width = 4, transparency = shorts(0x0112, 0x8056, 0xff9a))
        assertPixels(success(pngCommand, input), 0x0012569a, 0xff13569a.toInt(), 0xff12579a.toInt(), 0xff12569b.toInt())
        // The compacted stream remains valid, including the rewritten tRNS CRC.
        assertPixels(success(pngCommand, input), 0x0012569a, 0xff13569a.toInt(), 0xff12579a.toInt(), 0xff12569b.toInt())
    }

    @Test
    fun `PNG key normalization rejects corruption of unused bits before rewriting the CRC`() {
        val transparency = chunk("tRNS", shorts(0xff80)).also { it[8] = (it[8].toInt() xor 1).toByte() }
        val input = container(header(colorType = 0), transparency, chunk("IDAT", zlib(bytes(0, 128))), chunk("IEND"))
        failure(pngCommand, input, TerminalGraphicsResult.INVALID)
    }

    @Test
    fun `PNG sixteen bit grayscale transparency keeps both high and low key bits`() {
        val input = png(16, 0, shorts(0x8123, 0x0023, 0x8124), width = 3, transparency = shorts(0x8123))
        assertPixels(success(pngCommand, input), 0x00818181, 0xff000000.toInt(), 0xff818181.toInt())
    }

    @Test
    fun `PNG palette resolves exact colors and short alpha tables at every bit depth`() {
        for (depth in listOf(1, 2, 4, 8)) {
            val palette = bytes(128, 64, 32, 10, 20, 30)
            val row = if (depth == 8) bytes(0, 1) else bytes(1 shl (8 - 2 * depth))
            val image = success(pngCommand, png(depth, 3, row, width = 2, palette = palette, transparency = bytes(64)))
            assertPixels(image, 0x40804020, 0xff0a141e.toInt())
        }
    }

    @Test
    fun `PNG gray alpha and truecolor retain unassociated samples`() {
        assertPixels(success(pngCommand, png(8, 4, bytes(128, 63))), 0x3f808080)
        assertPixels(success(pngCommand, png(16, 4, shorts(0x8123, 0x7f80))), 0x7f818181)
        assertPixels(success(pngCommand, png(8, 2, bytes(128, 64, 32))), 0xff804020.toInt())
        assertPixels(success(pngCommand, png(16, 2, shorts(0x8123, 0x4567, 0x89ab))), 0xff814589.toInt())
        assertPixels(success(pngCommand, png(8, 6, bytes(128, 64, 32, 0))), 0x00804020)
        assertPixels(success(pngCommand, png(16, 6, shorts(0x8123, 0x4567, 0x89ab, 0x7f80))), 0x7f814589)
    }

    @Test
    fun `PNG truecolor transparency compares full sixteen bit samples`() {
        val transparent = shorts(0x1234, 0x5678, 0x9abc)
        assertPixels(success(pngCommand, png(16, 2, transparent, transparency = transparent)), 0x0012569a)
        assertPixels(success(pngCommand, png(16, 2, shorts(0x1235, 0x5678, 0x9abc), transparency = transparent)), 0xff12569a.toInt())
        assertPixels(success(pngCommand, png(8, 2, bytes(18, 86, 154), transparency = shorts(18, 86, 154))), 0x0012569a)
    }

    @Test
    fun `PNG Adam7 preserves all seven pass geometries and empty narrow passes`() {
        for ((width, height) in listOf(9 to 7, 1 to 1, 2 to 3, 1 to 9, 9 to 1)) {
            val expected =
                IntArray(width * height) { index ->
                    val x = index % width
                    val y = index / width
                    ((x * 27 + 3) shl 24) or ((y * 31 + 4) shl 16) or ((x * 25 + 5) shl 8) or (y * 23 + 6)
                }
            val stream = ByteArrayOutputStream()
            val starts = arrayOf(0 to 0, 4 to 0, 0 to 4, 2 to 0, 0 to 2, 1 to 0, 0 to 1)
            val steps = arrayOf(8 to 8, 8 to 8, 4 to 8, 4 to 4, 2 to 4, 2 to 2, 1 to 2)
            for (pass in starts.indices) {
                val (startX, startY) = starts[pass]
                val (stepX, stepY) = steps[pass]
                if (startX >= width) continue
                for (y in startY until height step stepY) {
                    stream.write(0)
                    for (x in startX until width step stepX) {
                        val pixel = expected[y * width + x]
                        stream.write(pixel ushr 16)
                        stream.write(pixel ushr 8)
                        stream.write(pixel)
                        stream.write(pixel ushr 24)
                    }
                }
            }
            val input = container(header(width, height, 8, 6, interlace = 1), chunk("IDAT", zlib(stream.toByteArray())), chunk("IEND"))
            assertContentEquals(expected, pixels(success(pngCommand, input)))
        }
    }

    @Test
    fun `PNG wrapped in zlib requires positive bounded exact S`() {
        val input = png(8, 6, bytes(1, 2, 3, 4))
        val compressed = zlib(input)
        val compressedCommand = command('f' to 100, 'o' to 'z'.code.toLong(), 'S' to input.size.toLong())
        assertPixels(success(compressedCommand, compressed), 0x04010203)
        failure(command('f' to 100, 'o' to 'z'.code.toLong()), compressed, TerminalGraphicsResult.INVALID)
        failure(command('f' to 100, 'o' to 'z'.code.toLong(), 'S' to 0), compressed, TerminalGraphicsResult.INVALID)
        failure(command('f' to 100, 'o' to 'z'.code.toLong(), 'S' to -1), compressed, TerminalGraphicsResult.INVALID)
        failure(
            command('f' to 100, 'o' to 'z'.code.toLong(), 'S' to TerminalGraphicsLimits.TRANSFER_BYTES.toLong() + 1),
            compressed,
            TerminalGraphicsResult.TOO_LARGE,
        )
        failure(command('f' to 100, 'o' to 'z'.code.toLong(), 'S' to input.size.toLong() - 1), compressed, TerminalGraphicsResult.INVALID)
        failure(command('f' to 100, 'o' to 'z'.code.toLong(), 'S' to input.size.toLong() + 1), compressed, TerminalGraphicsResult.INVALID)
        failure(compressedCommand, compressed + byteArrayOf(0), TerminalGraphicsResult.INVALID)
    }

    @Test
    fun `PNG uses IHDR dimensions and ignores bytes beyond the borrowed logical length`() {
        val input = png(8, 2, bytes(1, 2, 3))
        val result = decoder.decode(command('f' to 100, 's' to 99, 'v' to 99), input + bytes(4, 5, 6), input.size)
        assertEquals(TerminalGraphicsResult.OK, result.status)
        val image = assertNotNull(result.image)
        assertEquals(1, image.width)
        assertEquals(1, image.height)
        assertPixels(image, 0xff010203.toInt())
        failure(pngCommand, input + byteArrayOf(0), TerminalGraphicsResult.INVALID)
    }

    @Test
    fun `PNG rejects every truncated prefix and CRC corruption including ancillary chunks`() {
        val input = png(8, 6, bytes(1, 2, 3, 4))
        for (length in 0 until input.size) {
            val result = decoder.decode(pngCommand, input.copyOf(), length)
            assertEquals(TerminalGraphicsResult.INVALID, result.status, "truncated length $length")
            assertNull(result.image)
        }
        for (offset in listOf(0, 29, 33, input.lastIndex)) {
            failure(pngCommand, input.copyOf().also { it[offset] = (it[offset].toInt() xor 1).toByte() }, TerminalGraphicsResult.INVALID)
        }
        val text = chunk("tEXt", bytes(65, 0, 66)).also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        failure(pngCommand, container(header(), text, imageData(), chunk("IEND")), TerminalGraphicsResult.INVALID)
    }

    @Test
    fun `PNG rejects malformed headers dimensions critical chunks and invalid chunk order`() {
        val validHeader = header()
        val data = imageData()
        val end = chunk("IEND")
        val invalidStreams =
            listOf(
                container(data, validHeader, end),
                container(validHeader, validHeader, data, end),
                container(validHeader, chunk("ABCD"), data, end),
                container(validHeader, chunk("abca"), data, end),
                container(validHeader, chunk("t3Xt"), data, end),
                container(validHeader, end),
                container(validHeader, data, chunk("IEND", bytes(0))),
                container(validHeader, data, chunk("tEXt"), data, end),
                container(validHeader, data, chunk("PLTE", bytes(1, 2, 3)), end),
                container(header(depth = 3), data, end),
                container(header(colorType = 1), data, end),
                container(header(compression = 1), data, end),
                container(header(filter = 1), data, end),
                container(header(interlace = 2), data, end),
                container(header(width = 0), data, end),
                container(header(height = 0), data, end),
                container(validHeader, bytes(127, 255, 255, 255, 73, 68, 65, 84), end),
            )
        for ((index, input) in invalidStreams.withIndex()) {
            assertEquals(TerminalGraphicsResult.INVALID, decoder.decode(pngCommand, input, input.size).status, "case $index")
        }
        failure(pngCommand, container(header(width = 8193), data, end), TerminalGraphicsResult.TOO_LARGE)
        failure(pngCommand, container(header(width = 8192, height = 1025), data, end), TerminalGraphicsResult.TOO_LARGE)
        failure(pngCommand, container(header(width = -1), data, end), TerminalGraphicsResult.TOO_LARGE)
    }

    @Test
    fun `PNG enforces palette and transparency cardinality and position`() {
        val palette = chunk("PLTE", bytes(128, 64, 32))
        val end = chunk("IEND")
        val grayData = chunk("IDAT", zlib(bytes(0, 0)))
        val invalidStreams =
            listOf(
                container(header(colorType = 3), grayData, end),
                container(header(colorType = 3), chunk("PLTE"), grayData, end),
                container(header(colorType = 3), chunk("PLTE", bytes(1, 2)), grayData, end),
                container(header(colorType = 3), chunk("PLTE", ByteArray(771)), grayData, end),
                container(header(depth = 1, colorType = 3), chunk("PLTE", ByteArray(9)), grayData, end),
                container(header(colorType = 3), palette, palette, grayData, end),
                container(header(colorType = 0), palette, grayData, end),
                container(header(colorType = 4), palette, grayData, end),
                container(header(colorType = 3), chunk("tRNS", bytes(1)), palette, grayData, end),
                container(header(colorType = 3), palette, chunk("tRNS"), grayData, end),
                container(header(colorType = 3), palette, chunk("tRNS", bytes(1, 2)), grayData, end),
                container(header(colorType = 3), palette, grayData, chunk("tRNS", bytes(1)), end),
                container(header(colorType = 3), palette, chunk("tRNS", bytes(1)), chunk("tRNS", bytes(1)), grayData, end),
                container(header(colorType = 3), palette, chunk("IDAT", zlib(bytes(0, 1))), end),
                container(header(colorType = 0), chunk("tRNS", bytes(1)), grayData, end),
                container(header(colorType = 4), chunk("tRNS", shorts(1)), grayData, end),
                container(header(colorType = 6), chunk("tRNS", shorts(1, 2, 3)), imageData(), end),
            )
        for ((index, input) in invalidStreams.withIndex()) {
            assertEquals(TerminalGraphicsResult.INVALID, decoder.decode(pngCommand, input, input.size).status, "case $index")
        }
    }

    @Test
    fun `PNG validates IDAT zlib completion checksums and exact filtered data length`() {
        val image = zlib(bytes(0, 1, 2, 3, 4))
        val invalidPayloads =
            listOf(
                image.copyOf(image.size - 1),
                image + byteArrayOf(0),
                image + image,
                image.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() },
                zlib(bytes(0, 1, 2, 3)),
                zlib(bytes(0, 1, 2, 3, 4, 5)),
                zlib(bytes(5, 1, 2, 3, 4)),
                zlib(ByteArray(1024 * 1024)),
            )
        for ((index, input) in invalidPayloads.withIndex()) {
            val png = container(header(), chunk("IDAT", input), chunk("IEND"))
            assertEquals(TerminalGraphicsResult.INVALID, decoder.decode(pngCommand, png, png.size).status, "case $index")
        }
    }

    @Test
    fun `PNG reads only the supplied prefix and owns pixels after staging reuse`() {
        val payload = png(8, 6, bytes(1, 2, 3, 4))
        val staging = payload + ByteArray(16_384) { 0x7f }
        val result = decoder.decode(pngCommand, staging, payload.size)
        assertEquals(TerminalGraphicsResult.OK, result.status)
        val image = assertNotNull(result.image)
        staging.fill(0)
        assertPixels(image, 0x04010203)

        // A complete PNG after the supplied range cannot repair a truncated input.
        val truncated = decoder.decode(pngCommand, payload, payload.size - 1)
        assertEquals(TerminalGraphicsResult.INVALID, truncated.status)
        assertNull(truncated.image)
        assertPixels(success(pngCommand, png(8, 6, bytes(9, 8, 7, 6))), 0x06090807)
        assertPixels(image, 0x04010203)
    }

    @Test
    fun `PNG compacts metadata and thousands of IDAT chunks before invoking ImageIO`() {
        val metadata = chunk("zTXt", bytes(65, 0, 0) + zlib(ByteArray(1024 * 1024)))
        val stream = ByteArrayOutputStream()
        stream.write(signature)
        stream.write(header())
        repeat(2000) { stream.write(metadata) }
        val data = zlib(bytes(0, 1, 2, 3, 4))
        repeat(20_000) { stream.write(chunk("IDAT")) }
        for (byte in data) stream.write(chunk("IDAT", byteArrayOf(byte)))
        stream.write(chunk("IEND"))
        val input = stream.toByteArray()
        assertPixels(success(pngCommand, input), 0x04010203)
        var offset = 8
        val names = ArrayList<String>()
        while (true) {
            val name = input.copyOfRange(offset + 4, offset + 8).decodeToString()
            names.add(name)
            val size =
                java.nio.ByteBuffer
                    .wrap(input, offset, 4)
                    .int
            if (name == "IEND") break
            offset += size + 12
        }
        assertEquals(listOf("IHDR", "IDAT", "IEND"), names)
        assertEquals(data.size + 57, offset + 12)
    }

    private fun success(
        controls: TerminalGraphicsCommand,
        input: ByteArray,
    ): TerminalRenderImage {
        val result = decoder.decode(controls, input, input.size)
        assertEquals(TerminalGraphicsResult.OK, result.status)
        return assertNotNull(result.image)
    }

    private fun failure(
        controls: TerminalGraphicsCommand,
        input: ByteArray,
        expected: TerminalGraphicsResult,
    ) {
        val result = decoder.decode(controls, input, input.size)
        assertEquals(expected, result.status)
        assertNull(result.image)
    }

    private fun assertPixels(
        image: TerminalRenderImage,
        vararg expected: Int,
    ) {
        assertContentEquals(expected, pixels(image))
    }

    private fun pixels(image: TerminalRenderImage): IntArray = IntArray(image.width * image.height).also { image.copyArgb(it) }

    private fun command(vararg entries: Pair<Char, Long>): TerminalGraphicsCommand =
        object : TerminalGraphicsCommand {
            override fun value(key: Char): Long = entries.firstOrNull { it.first == key }?.second ?: TerminalGraphicsCommand.OMITTED
        }

    private data class GrayCase(
        val depth: Int,
        val sample: Int,
        val encoded: ByteArray,
        val expected: Int,
    )

    private companion object {
        val signature = bytes(137, 80, 78, 71, 13, 10, 26, 10)
        val pngCommand =
            object : TerminalGraphicsCommand {
                override fun value(key: Char): Long = if (key == 'f') 100 else TerminalGraphicsCommand.OMITTED
            }

        fun bytes(vararg bytes: Int): ByteArray = ByteArray(bytes.size) { bytes[it].toByte() }

        fun shorts(vararg samples: Int): ByteArray =
            ByteArray(samples.size * 2) {
                (if (it % 2 == 0) samples[it / 2] ushr 8 else samples[it / 2]).toByte()
            }

        fun zlib(bytes: ByteArray): ByteArray =
            ByteArrayOutputStream().use { output ->
                DeflaterOutputStream(output).use { it.write(bytes) }
                output.toByteArray()
            }

        fun chunk(
            name: String,
            data: ByteArray = byteArrayOf(),
        ): ByteArray =
            ByteArrayOutputStream().use { buffer ->
                DataOutputStream(buffer).use { output ->
                    output.writeInt(data.size)
                    val nameBytes = name.encodeToByteArray()
                    output.write(nameBytes)
                    output.write(data)
                    val crc = CRC32()
                    crc.update(nameBytes)
                    crc.update(data)
                    output.writeInt(crc.value.toInt())
                }
                buffer.toByteArray()
            }

        fun header(
            width: Int = 1,
            height: Int = 1,
            depth: Int = 8,
            colorType: Int = 6,
            compression: Int = 0,
            filter: Int = 0,
            interlace: Int = 0,
        ): ByteArray =
            chunk(
                "IHDR",
                ByteArrayOutputStream().use { buffer ->
                    DataOutputStream(buffer).use { output ->
                        output.writeInt(width)
                        output.writeInt(height)
                        output.write(bytes(depth, colorType, compression, filter, interlace))
                    }
                    buffer.toByteArray()
                },
            )

        fun imageData(): ByteArray = chunk("IDAT", zlib(bytes(0, 1, 2, 3, 4)))

        fun container(vararg chunks: ByteArray): ByteArray =
            ByteArrayOutputStream().use { output ->
                output.write(signature)
                for (chunk in chunks) output.write(chunk)
                output.toByteArray()
            }

        fun png(
            depth: Int,
            colorType: Int,
            row: ByteArray,
            width: Int = 1,
            palette: ByteArray? = null,
            transparency: ByteArray? = null,
        ): ByteArray =
            ByteArrayOutputStream().use { output ->
                output.write(signature)
                output.write(header(width = width, depth = depth, colorType = colorType))
                if (palette != null) output.write(chunk("PLTE", palette))
                if (transparency != null) output.write(chunk("tRNS", transparency))
                output.write(chunk("IDAT", zlib(byteArrayOf(0) + row)))
                output.write(chunk("IEND"))
                output.toByteArray()
            }
    }
}

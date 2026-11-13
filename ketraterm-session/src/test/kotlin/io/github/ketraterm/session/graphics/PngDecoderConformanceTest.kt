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
import io.github.ketraterm.protocol.TerminalGraphicsResult
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PngDecoderConformanceTest {
    @Test
    fun `pinned PNGSuite images match independent native sample pixel hashes`() {
        val manifest =
            checkNotNull(javaClass.getResourceAsStream("/pngsuite/pixels.tsv"))
                .bufferedReader()
                .use { it.readLines() }
        assertEquals(24, manifest.size)
        for (entry in manifest) {
            val (name, sourceHash, pixelHash) = entry.split('\t')
            val input = checkNotNull(javaClass.getResourceAsStream("/pngsuite/$name")).use { it.readBytes() }
            val digest = MessageDigest.getInstance("SHA-256")
            assertEquals(sourceHash, digest.digest(input).toHexString(), name)
            val result = GraphicsDecoder().decode(command, input, input.size)
            assertEquals(TerminalGraphicsResult.OK, result.status, name)
            val image = assertNotNull(result.image, name)
            assertEquals(32, image.width, name)
            assertEquals(32, image.height, name)
            val pixels = IntArray(32 * 32)
            image.copyArgb(pixels)
            for (pixel in pixels) {
                digest.update((pixel ushr 24).toByte())
                digest.update((pixel ushr 16).toByte())
                digest.update((pixel ushr 8).toByte())
                digest.update(pixel.toByte())
            }
            assertEquals(pixelHash, digest.digest().toHexString(), name)
        }
    }

    @Test
    fun `all PNG sample formats filters and Adam7 passes recover independently generated pixels`() {
        for ((type, depths) in formats) {
            for (depth in depths) {
                for (interlace in 0..1) {
                    for (filter in 0..4) {
                        for ((width, height) in listOf(1 to 1, 1 to 9, 9 to 1, 2 to 3, 9 to 7, 17 to 13)) {
                            val png = encode(width, height, depth, type, interlace, filter)
                            val result = GraphicsDecoder().decode(command, png, png.size)
                            val context = "type=$type depth=$depth interlace=$interlace filter=$filter ${width}x$height"
                            assertEquals(TerminalGraphicsResult.OK, result.status, context)
                            val image = assertNotNull(result.image, context)
                            assertEquals(width, image.width)
                            assertEquals(height, image.height)
                            val actual = IntArray(width * height)
                            image.copyArgb(actual)
                            val expected = IntArray(actual.size) { expected(it % width, it / width, type, depth) }
                            assertContentEquals(expected, actual, context)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `mixed row filters and nonzero packed padding preserve pixels in every Adam7 pass`() {
        for ((type, depths) in formats) {
            for (depth in depths) {
                for (interlace in 0..1) {
                    val input = encode(17, 13, depth, type, interlace, filter = -1)
                    val result = GraphicsDecoder().decode(command, input, input.size)
                    val context = "type=$type depth=$depth interlace=$interlace"
                    assertEquals(TerminalGraphicsResult.OK, result.status, context)
                    val image = assertNotNull(result.image)
                    val actual = IntArray(17 * 13)
                    image.copyArgb(actual)
                    assertContentEquals(IntArray(actual.size) { expected(it % 17, it / 17, type, depth) }, actual, context)
                }
            }
        }
    }

    @Test
    fun `single byte mutations reject without partial images and a valid decode recovers`() {
        val original = encode(17, 13, 16, 6, 1, 4)
        val decoder = GraphicsDecoder()
        for (index in original.indices) {
            val input = original.copyOf()
            input[index] = (input[index].toInt() xor 0x80).toByte()
            val result = decoder.decode(command, input, input.size)
            assertEquals(TerminalGraphicsResult.INVALID, result.status, "mutated byte $index")
            assertNull(result.image)
        }
        val valid = original.copyOf()
        assertEquals(TerminalGraphicsResult.OK, decoder.decode(command, valid, valid.size).status)
    }

    @Test
    fun `truncated interlaced IDAT streams reject even with valid container checksums`() {
        val original = encode(17, 13, 16, 6, 1, 4)
        var offset = 8
        while (original.copyOfRange(offset + 4, offset + 8).decodeToString() != "IDAT") {
            offset += java.nio.ByteBuffer
                .wrap(original, offset, 4)
                .int + 12
        }
        val size =
            java.nio.ByteBuffer
                .wrap(original, offset, 4)
                .int
        val payload = original.copyOfRange(offset + 8, offset + 8 + size)
        val prefix = original.copyOfRange(0, offset)
        val suffix = original.copyOfRange(offset + size + 12, original.size)
        for (length in payload.indices) {
            val input = prefix + chunk("IDAT", payload.copyOf(length)) + suffix
            val result = GraphicsDecoder().decode(command, input, input.size)
            assertEquals(TerminalGraphicsResult.INVALID, result.status, "IDAT prefix $length")
            assertNull(result.image)
        }
    }

    private fun encode(
        width: Int,
        height: Int,
        depth: Int,
        type: Int,
        interlace: Int,
        filter: Int,
    ): ByteArray {
        val channels =
            when (type) {
                0, 3 -> 1
                2 -> 3
                4 -> 2
                else -> 4
            }
        val image = ByteArrayOutputStream()
        val passes =
            if (interlace == 0) {
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
            val xs = (startX until width step stepX).toList()
            if (xs.isEmpty()) continue
            var previous = ByteArray((xs.size * channels * depth + 7) / 8)
            for (y in startY until height step stepY) {
                val row = ByteArray(previous.size)
                var bit = 0
                for (x in xs) {
                    for (channel in 0 until channels) {
                        val value = sample(x, y, channel, depth)
                        for (shift in depth - 1 downTo 0) {
                            row[bit / 8] = (row[bit / 8].toInt() or (((value ushr shift) and 1) shl (7 - bit % 8))).toByte()
                            bit++
                        }
                    }
                }
                // PNG leaves unused low bits unspecified. Include nonzero bits in
                // the filtered bytes so row reconstruction must preserve them.
                val unusedBits = (8 - bit % 8) % 8
                if (unusedBits != 0) row[row.lastIndex] = (row.last().toInt() or ((1 shl unusedBits) - 1)).toByte()
                val rowFilter = if (filter < 0) y % 5 else filter
                image.write(rowFilter)
                val stride = maxOf(1, (channels * depth + 7) / 8)
                for (i in row.indices) {
                    val a = if (i >= stride) row[i - stride].toInt() and 255 else 0
                    val b = previous[i].toInt() and 255
                    val c = if (i >= stride) previous[i - stride].toInt() and 255 else 0
                    val prediction =
                        when (rowFilter) {
                            0 -> 0
                            1 -> a
                            2 -> b
                            3 -> (a + b) / 2
                            else -> listOf(a, b, c).minBy { kotlin.math.abs(a + b - c - it) }
                        }
                    image.write((row[i].toInt() and 255) - prediction)
                }
                previous = row
            }
        }
        return ByteArrayOutputStream().use { output ->
            output.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
            val header = ByteArrayOutputStream()
            DataOutputStream(header).use {
                it.writeInt(width)
                it.writeInt(height)
                it.write(byteArrayOf(depth.toByte(), type.toByte(), 0, 0, interlace.toByte()))
            }
            output.write(chunk("IHDR", header.toByteArray()))
            if (type == 3) {
                val count = 1 shl depth
                output.write(chunk("PLTE", ByteArray(count * 3) { palette(it / 3, it % 3).toByte() }))
                output.write(chunk("tRNS", ByteArray(count / 2) { (it * 17).toByte() }))
            } else if (type == 0 || type == 2) {
                output.write(
                    chunk(
                        "tRNS",
                        ByteArray(if (type == 0) 2 else 6) { i ->
                            val value = sample(0, 0, i / 2, depth)
                            (if (i % 2 == 0) value ushr 8 else value).toByte()
                        },
                    ),
                )
            }
            val compressed = ByteArrayOutputStream()
            DeflaterOutputStream(compressed).use { it.write(image.toByteArray()) }
            output.write(chunk("IDAT", compressed.toByteArray()))
            output.write(chunk("IEND", byteArrayOf()))
            output.toByteArray()
        }
    }

    private fun expected(
        x: Int,
        y: Int,
        type: Int,
        depth: Int,
    ): Int {
        val samples = IntArray(4) { sample(x, y, it, depth) }

        fun eight(value: Int) = if (depth == 16) value / 256 else value * 255 / ((1 shl depth) - 1)
        if (type == 3) {
            val index = samples[0]
            val alpha = if (index < (1 shl depth) / 2) index * 17 and 255 else 255
            return (alpha shl 24) or (palette(index, 0) shl 16) or (palette(index, 1) shl 8) or palette(index, 2)
        }
        val gray = type == 0 || type == 4
        val red = eight(samples[0])
        val green = if (gray) red else eight(samples[1])
        val blue = if (gray) red else eight(samples[2])
        val alpha =
            when (type) {
                4 -> eight(samples[1])
                6 -> eight(samples[3])
                else -> if ((0 until if (gray) 1 else 3).all { samples[it] == sample(0, 0, it, depth) }) 0 else 255
            }
        return (alpha shl 24) or (red shl 16) or (green shl 8) or blue
    }

    private fun sample(
        x: Int,
        y: Int,
        channel: Int,
        depth: Int,
    ) = (x * 313 + y * 107 + channel * 211 + 31) and ((1 shl depth) - 1)

    private fun palette(
        index: Int,
        channel: Int,
    ) = (index * 53 + channel * 97) and 255

    private fun chunk(
        name: String,
        data: ByteArray,
    ): ByteArray =
        ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { output ->
                output.writeInt(data.size)
                val type = name.encodeToByteArray()
                output.write(type)
                output.write(data)
                val crc = CRC32()
                crc.update(type)
                crc.update(data)
                output.writeInt(crc.value.toInt())
            }
            buffer.toByteArray()
        }

    private companion object {
        val formats =
            listOf(
                0 to listOf(1, 2, 4, 8, 16),
                2 to listOf(8, 16),
                3 to listOf(1, 2, 4, 8),
                4 to listOf(8, 16),
                6 to listOf(8, 16),
            )
        val command =
            object : TerminalGraphicsCommand {
                override fun value(key: Char): Long = if (key == 'f') 100 else TerminalGraphicsCommand.OMITTED
            }
    }
}

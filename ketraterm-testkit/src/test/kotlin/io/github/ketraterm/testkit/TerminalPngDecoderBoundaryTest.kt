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
package io.github.ketraterm.testkit

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import javax.imageio.ImageReader
import javax.imageio.spi.IIORegistry
import javax.imageio.spi.ImageReaderSpi
import javax.imageio.stream.MemoryCacheImageInputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** Decoder assumptions for the graphics design; these tests do not claim terminal PNG support. */
class TerminalPngDecoderBoundaryTest {
    @ParameterizedTest(name = "PNG color type {0}, depth {1}, channels {2}")
    @CsvSource(
        "0, 1, 1",
        "0, 2, 1",
        "0, 4, 1",
        "0, 8, 1",
        "0, 16, 1",
        "2, 8, 3",
        "2, 16, 3",
        "3, 1, 1",
        "3, 2, 1",
        "3, 4, 1",
        "3, 8, 1",
        "4, 8, 2",
        "4, 16, 2",
        "6, 8, 4",
        "6, 16, 4",
    )
    fun `native decoding covers every standard color type and bit depth`(
        colorType: Int,
        bitDepth: Int,
        channels: Int,
    ) {
        val samples = ByteArray((channels * bitDepth + 7) / 8) { if (colorType == 3) 0 else 0xff.toByte() }
        val image = decode(png(bitDepth, colorType, byteArrayOf(0) + samples))

        assertEquals(1, image.width)
        assertEquals(1, image.height)
        assertEquals(bitDepth, image.sampleModel.getSampleSize(0))
        assertContentEquals(
            IntArray(channels) { if (colorType == 3) 0 else (1 shl bitDepth) - 1 },
            image.raster.getPixel(0, 0, null as IntArray?),
        )
    }

    @ParameterizedTest
    @ValueSource(ints = [8, 16])
    fun `native grayscale samples preserve intensity for sRGB replication`(bitDepth: Int) {
        val samples = if (bitDepth == 8) bytes(0x80) else bytes(0x80, 0x00)
        val image = decode(png(bitDepth, 0, byteArrayOf(0) + samples))
        val gray = image.raster.getSample(0, 0, 0)

        assertEquals(if (bitDepth == 8) 0x80 else 0x8000, gray)
        assertEquals(0x80, gray ushr (bitDepth - 8))
    }

    @Test
    fun `sixteen bit transparency compares the complete source sample before narrowing`() {
        val transparent = bytes(0x12, 0x34, 0x56, 0x78, 0x9a, 0xbc)
        val opaque = bytes(0x12, 0x35, 0x56, 0x78, 0x9a, 0xbc)
        val image = decode(png(16, 2, byteArrayOf(0) + transparent + opaque, width = 2, transparency = transparent))

        assertContentEquals(intArrayOf(16, 16, 16, 16), image.sampleModel.sampleSize)
        assertContentEquals(intArrayOf(0x1234, 0x5678, 0x9abc, 0), image.raster.getPixel(0, 0, null as IntArray?))
        assertContentEquals(intArrayOf(0x1235, 0x5678, 0x9abc, 0xffff), image.raster.getPixel(1, 0, null as IntArray?))
    }

    @Test
    fun `indexed transparency preserves alpha and palette samples`() {
        val image = decode(png(8, 3, bytes(0, 0, 1), width = 2, transparency = bytes(0x40)))

        assertContentEquals(intArrayOf(0, 1), image.raster.getSamples(0, 0, 2, 1, 0, null as IntArray?))
        assertEquals(0x40804020, image.colorModel.getRGB(0))
        assertEquals(0xff204080.toInt(), image.colorModel.getRGB(1))
    }

    @Test
    fun `Adam7 passes reconstruct their original pixel positions`() {
        val xStarts = intArrayOf(0, 4, 0, 2, 0, 1, 0)
        val yStarts = intArrayOf(0, 0, 4, 0, 2, 0, 1)
        val xSteps = intArrayOf(8, 8, 4, 4, 2, 2, 1)
        val ySteps = intArrayOf(8, 8, 8, 4, 4, 2, 2)
        val scanlines = ByteArrayOutputStream()
        for (pass in xStarts.indices) {
            for (y in yStarts[pass] until 7 step ySteps[pass]) {
                scanlines.write(0)
                for (x in xStarts[pass] until 9 step xSteps[pass]) {
                    scanlines.write(bytes(x, y, x + y, 255 - x - y))
                }
            }
        }
        val image = decode(png(8, 6, scanlines.toByteArray(), width = 9, height = 7, interlaced = true))

        for (y in 0 until 7) {
            for (x in 0 until 9) {
                assertContentEquals(
                    intArrayOf(x, y, x + y, 255 - x - y),
                    image.raster.getPixel(x, y, null as IntArray?),
                    "pixel ($x, $y)",
                )
            }
        }
    }

    private fun decode(png: ByteArray): BufferedImage {
        val providers = IIORegistry.getDefaultInstance().getServiceProviders(ImageReaderSpi::class.java, false)
        val provider =
            providers.asSequence().first {
                it.javaClass.module == ImageReader::class.java.module &&
                    it.formatNames.any { name -> name.equals("png", ignoreCase = true) }
            }
        val reader = provider.createReaderInstance()
        try {
            return MemoryCacheImageInputStream(ByteArrayInputStream(png)).use { input ->
                reader.setInput(input, true, true)
                reader.read(0)
            }
        } finally {
            reader.dispose()
        }
    }

    private fun png(
        bitDepth: Int,
        colorType: Int,
        scanlines: ByteArray,
        width: Int = 1,
        height: Int = 1,
        transparency: ByteArray? = null,
        interlaced: Boolean = false,
    ): ByteArray {
        val header = ByteArrayOutputStream()
        DataOutputStream(header).use {
            it.writeInt(width)
            it.writeInt(height)
            it.write(bytes(bitDepth, colorType, 0, 0, if (interlaced) 1 else 0))
        }
        val compressed = ByteArrayOutputStream()
        DeflaterOutputStream(compressed).use { it.write(scanlines) }
        val encoded = ByteArrayOutputStream()
        DataOutputStream(encoded).use {
            it.write(bytes(137, 80, 78, 71, 13, 10, 26, 10))
            it.writeChunk("IHDR", header.toByteArray())
            if (colorType == 3) it.writeChunk("PLTE", bytes(0x80, 0x40, 0x20, 0x20, 0x40, 0x80))
            if (transparency != null) it.writeChunk("tRNS", transparency)
            it.writeChunk("IDAT", compressed.toByteArray())
            it.writeChunk("IEND", byteArrayOf())
        }
        return encoded.toByteArray()
    }

    private fun DataOutputStream.writeChunk(
        name: String,
        payload: ByteArray,
    ) {
        val type = name.toByteArray(Charsets.US_ASCII)
        val crc = CRC32()
        crc.update(type)
        crc.update(payload)
        writeInt(payload.size)
        write(type)
        write(payload)
        writeInt(crc.value.toInt())
    }

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }
}

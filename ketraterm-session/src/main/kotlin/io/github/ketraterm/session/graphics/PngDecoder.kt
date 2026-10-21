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

import io.github.ketraterm.protocol.TerminalGraphicsResult
import io.github.ketraterm.render.api.TerminalRenderImage
import java.awt.image.BufferedImage
import java.util.*
import java.util.zip.CRC32
import java.util.zip.Inflater
import javax.imageio.ImageReader
import javax.imageio.spi.IIORegistry
import javax.imageio.spi.ImageReaderSpi
import javax.imageio.stream.ImageInputStreamImpl

/** Validates and strips unneeded PNG chunks before invoking the JDK's bounded raster decoder. */
internal object PngDecoder {
    private const val IHDR = 0x49484452
    private const val PLTE = 0x504c5445
    private const val TRNS = 0x74524e53
    private const val IDAT = 0x49444154
    private const val IEND = 0x49454e44
    private val signature = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
    private val provider: ImageReaderSpi by lazy { findProvider() }

    fun decode(
        bytes: ByteArray,
        length: Int,
    ): TerminalRenderImage {
        val png = validate(bytes, length)
        val compactedLength = compact(bytes, length, png)
        validateImageStream(bytes, png)
        val reader = provider.createReaderInstance()
        try {
            ArrayImageInputStream(bytes, compactedLength).use { input ->
                reader.setInput(input, true, true)
                // Keep native samples: an 8-bit destination changes 16-bit tRNS matching.
                val image = reader.read(0, reader.defaultReadParam)
                return normalize(image, png)
            }
        } finally {
            reader.dispose()
        }
    }

    private fun validate(
        bytes: ByteArray,
        length: Int,
    ): PngHeader {
        if (length < signature.size) invalid()
        var index = 0
        while (index < signature.size) {
            if (bytes[index] != signature[index]) invalid()
            index++
        }
        val crc = CRC32()
        var header: PngHeader? = null
        var paletteSeen = false
        var transparencySeen = false
        var dataSeen = false
        var dataEnded = false
        while (index <= length - 12) {
            val size = intAt(bytes, index)
            if (size < 0 || size > length - index - 12) invalid()
            val type = intAt(bytes, index + 4)
            var nameIndex = index + 4
            while (nameIndex < index + 8) {
                val letter = bytes[nameIndex++].toInt() and 255
                if (letter !in 65..90 && letter !in 97..122) invalid()
            }
            if (bytes[index + 6].toInt() and 32 != 0) invalid()
            crc.reset()
            crc.update(bytes, index + 4, size + 4)
            if (crc.value.toInt() != intAt(bytes, index + 8 + size)) invalid()
            val data = index + 8
            if (header == null) {
                if (type != IHDR || size != 13) invalid()
                header = readHeader(bytes, data)
            } else {
                if (dataSeen && type != IDAT) dataEnded = true
                when (type) {
                    IHDR -> invalid()
                    PLTE -> {
                        if (paletteSeen ||
                            transparencySeen ||
                            dataSeen ||
                            header.colorType == 0 ||
                            header.colorType == 4 ||
                            size == 0 ||
                            size % 3 != 0 ||
                            size > 768
                        ) {
                            invalid()
                        }
                        val count = size / 3
                        if (header.colorType == 3 && count > 1 shl header.depth) invalid()
                        header.palette = IntArray(count)
                        var color = 0
                        while (color < count) {
                            val offset = data + color * 3
                            header.palette[color++] = (255 shl 24) or
                                ((bytes[offset].toInt() and 255) shl 16) or
                                ((bytes[offset + 1].toInt() and 255) shl 8) or
                                (bytes[offset + 2].toInt() and 255)
                        }
                        paletteSeen = true
                    }
                    TRNS -> {
                        if (transparencySeen || dataSeen) invalid()
                        readTransparency(bytes, data, size, header)
                        transparencySeen = true
                    }
                    IDAT -> {
                        if (dataEnded || header.colorType == 3 && !paletteSeen) invalid()
                        dataSeen = true
                    }
                    IEND -> {
                        if (size != 0 || !dataSeen || index + 12 != length) invalid()
                        return header
                    }
                    else -> if (type and 0x20000000 == 0) invalid()
                }
            }
            index += size + 12
        }
        invalid()
    }

    private fun readHeader(
        bytes: ByteArray,
        offset: Int,
    ): PngHeader {
        val width = intAt(bytes, offset).toLong() and 0xffffffffL
        val height = intAt(bytes, offset + 4).toLong() and 0xffffffffL
        validateDimensions(width, height)
        val depth = bytes[offset + 8].toInt() and 255
        val colorType = bytes[offset + 9].toInt() and 255
        val validDepth =
            when (colorType) {
                0 -> depth == 1 || depth == 2 || depth == 4 || depth == 8 || depth == 16
                2, 4, 6 -> depth == 8 || depth == 16
                3 -> depth == 1 || depth == 2 || depth == 4 || depth == 8
                else -> false
            }
        val interlace = bytes[offset + 12].toInt() and 255
        if (!validDepth || bytes[offset + 10] != 0.toByte() || bytes[offset + 11] != 0.toByte() || interlace > 1) invalid()
        return PngHeader(width.toInt(), height.toInt(), depth, colorType, interlace)
    }

    private fun readTransparency(
        bytes: ByteArray,
        offset: Int,
        length: Int,
        header: PngHeader,
    ) {
        val maxSample = (1 shl header.depth) - 1
        when (header.colorType) {
            0 -> {
                if (length != 2) invalid()
                header.transparentGray = shortAt(bytes, offset) and maxSample
            }
            2 -> {
                if (length != 6) invalid()
            }
            3 -> {
                if (length == 0 || length > header.palette.size) invalid()
                var index = 0
                while (index < length) {
                    header.palette[index] = (header.palette[index] and 0xffffff) or ((bytes[offset + index].toInt() and 255) shl 24)
                    index++
                }
            }
            else -> invalid()
        }
    }

    /** All source chunks have already passed bounds/CRC/order checks. Writes never overtake reads. */
    private fun compact(
        bytes: ByteArray,
        length: Int,
        header: PngHeader,
    ): Int {
        val crc = CRC32()
        var read = 8
        var write = 8
        var imageData = -1
        var imageDataLength = 0
        while (read < length) {
            val size = intAt(bytes, read)
            val type = intAt(bytes, read + 4)
            if (type == IDAT) {
                if (imageData < 0) {
                    imageData = write
                    writeInt(bytes, write + 4, IDAT)
                    write += 8
                }
                bytes.copyInto(bytes, write, read + 8, read + 8 + size)
                write += size
                imageDataLength += size
            } else {
                if (imageData >= 0 && type == IEND) {
                    writeInt(bytes, imageData, imageDataLength)
                    crc.reset()
                    crc.update(bytes, imageData + 4, imageDataLength + 4)
                    writeInt(bytes, write, crc.value.toInt())
                    write += 4
                }
                if (type == IHDR || type == PLTE || type == TRNS || type == IEND) {
                    bytes.copyInto(bytes, write, read, read + size + 12)
                    if (type == TRNS && header.colorType != 3 && header.depth < 16) {
                        // PNG requires ignoring unused key bits. Normalize before
                        // ImageIO compares samples, after validating the original CRC.
                        val maxSample = (1 shl header.depth) - 1
                        var sample = write + 8
                        while (sample < write + 8 + size) {
                            val value = shortAt(bytes, sample) and maxSample
                            bytes[sample] = 0
                            bytes[sample + 1] = value.toByte()
                            sample += 2
                        }
                        crc.reset()
                        crc.update(bytes, write + 4, size + 4)
                        writeInt(bytes, write + 8 + size, crc.value.toInt())
                    }
                    write += size + 12
                }
            }
            read += size + 12
        }
        return write
    }

    /** ImageIO may stop after the requested rows; verify the zlib trailer and full scanline size independently. */
    private fun validateImageStream(
        bytes: ByteArray,
        header: PngHeader,
    ) {
        var offset = 8
        while (intAt(bytes, offset + 4) != IDAT) offset += intAt(bytes, offset) + 12
        val length = intAt(bytes, offset)
        val channels =
            when (header.colorType) {
                0, 3 -> 1
                2 -> 3
                4 -> 2
                else -> 4
            }
        val bitsPerPixel = channels * header.depth
        var expected = 0L
        if (header.interlace == 0) {
            expected = (((header.width.toLong() * bitsPerPixel + 7) / 8) + 1) * header.height
        } else {
            var pass = 0
            while (pass < 7) {
                val xStart =
                    when (pass) {
                        1 -> 4
                        3 -> 2
                        5 -> 1
                        else -> 0
                    }
                val yStart =
                    when (pass) {
                        2 -> 4
                        4 -> 2
                        6 -> 1
                        else -> 0
                    }
                val xStep =
                    when (pass) {
                        0, 1 -> 8
                        2, 3 -> 4
                        4, 5 -> 2
                        else -> 1
                    }
                val yStep =
                    when (pass) {
                        0, 1, 2 -> 8
                        3, 4 -> 4
                        else -> 2
                    }
                val width = (header.width - xStart + xStep - 1) / xStep
                val height = (header.height - yStart + yStep - 1) / yStep
                if (width > 0 && height > 0) expected += ((width.toLong() * bitsPerPixel + 7) / 8 + 1) * height
                pass++
            }
        }
        val scratch = ByteArray(8192)
        val inflater = Inflater()
        try {
            inflater.setInput(bytes, offset + 8, length)
            var expanded = 0L
            while (!inflater.finished()) {
                val count = inflater.inflate(scratch)
                expanded += count
                if (expanded > expected || count == 0 && !inflater.finished()) invalid()
            }
            if (expanded != expected || inflater.remaining != 0) invalid()
        } finally {
            inflater.end()
        }
    }

    private fun normalize(
        image: BufferedImage,
        header: PngHeader,
    ): TerminalRenderImage {
        if (image.width != header.width || image.height != header.height) invalid()
        val raster = image.raster
        // Grayscale tRNS can expand sub-byte samples to an 8-bit gray/alpha raster.
        val sampleDepth = raster.sampleModel.getSampleSize(0)
        // JDK PNG compares gray tRNS after expanding sub-byte samples, against the
        // unexpanded key. Compare in the actual raster's sample range ourselves.
        val transparentGray =
            if (header.transparentGray < 0) {
                -1
            } else {
                (header.transparentGray.toLong() * ((1 shl sampleDepth) - 1) / ((1 shl header.depth) - 1)).toInt()
            }
        val pixels = IntArray(header.width * header.height)
        var target = 0
        var y = 0
        while (y < header.height) {
            var x = 0
            while (x < header.width) {
                val first = raster.getSample(x, y, 0)
                pixels[target++] =
                    when (header.colorType) {
                        3 -> {
                            if (first >= header.palette.size) invalid()
                            header.palette[first]
                        }
                        0, 4 -> {
                            val gray = sample8(first, sampleDepth)
                            val alpha =
                                if (header.colorType == 4) {
                                    sample8(raster.getSample(x, y, 1), sampleDepth)
                                } else if (first == transparentGray) {
                                    0
                                } else {
                                    255
                                }
                            (alpha shl 24) or (gray shl 16) or (gray shl 8) or gray
                        }
                        else -> {
                            val green = raster.getSample(x, y, 1)
                            val blue = raster.getSample(x, y, 2)
                            val alpha = if (raster.numBands == 4) sample8(raster.getSample(x, y, 3), sampleDepth) else 255
                            (alpha shl 24) or (sample8(first, sampleDepth) shl 16) or (sample8(green, sampleDepth) shl 8) or
                                sample8(blue, sampleDepth)
                        }
                    }
                x++
            }
            y++
        }
        return DecodedGraphicsImage(header.width, header.height, pixels)
    }

    private fun sample8(
        value: Int,
        depth: Int,
    ): Int =
        when (depth) {
            16 -> value ushr 8
            8 -> value
            else -> value * 255 / ((1 shl depth) - 1)
        }

    private fun findProvider(): ImageReaderSpi {
        val providers = IIORegistry.getDefaultInstance().getServiceProviders(ImageReaderSpi::class.java, false)
        while (providers.hasNext()) {
            val candidate = providers.next()
            if (candidate.javaClass.module != ImageReader::class.java.module) continue
            if (candidate.formatNames.any { it.equals("png", ignoreCase = true) }) return candidate
        }
        throw IllegalStateException("The JDK PNG reader is unavailable")
    }

    private fun shortAt(
        bytes: ByteArray,
        offset: Int,
    ): Int = ((bytes[offset].toInt() and 255) shl 8) or (bytes[offset + 1].toInt() and 255)

    private fun intAt(
        bytes: ByteArray,
        offset: Int,
    ): Int =
        ((bytes[offset].toInt() and 255) shl 24) or ((bytes[offset + 1].toInt() and 255) shl 16) or
            ((bytes[offset + 2].toInt() and 255) shl 8) or (bytes[offset + 3].toInt() and 255)

    private fun writeInt(
        bytes: ByteArray,
        offset: Int,
        value: Int,
    ) {
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }

    private fun invalid(): Nothing = throw GraphicsDecodeException(TerminalGraphicsResult.INVALID)

    private class PngHeader(
        val width: Int,
        val height: Int,
        val depth: Int,
        val colorType: Int,
        val interlace: Int,
    ) {
        var palette = IntArray(0)
        var transparentGray = -1
    }

    /** Reads only the compacted prefix; no caching, filesystem access, or global ImageIO changes. */
    private class ArrayImageInputStream(
        private val bytes: ByteArray,
        private val size: Int,
    ) : ImageInputStreamImpl() {
        override fun read(): Int {
            checkClosed()
            bitOffset = 0
            if (streamPos >= size) return -1
            val position = streamPos.toInt()
            streamPos++
            return bytes[position].toInt() and 255
        }

        override fun read(
            destination: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            checkClosed()
            Objects.checkFromIndexSize(offset, length, destination.size)
            bitOffset = 0
            if (length == 0) return 0
            if (streamPos >= size) return -1
            val count = minOf(length.toLong(), size - streamPos).toInt()
            bytes.copyInto(destination, offset, streamPos.toInt(), streamPos.toInt() + count)
            streamPos += count
            return count
        }

        override fun length(): Long = size.toLong()
    }
}

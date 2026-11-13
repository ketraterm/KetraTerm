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
import java.util.zip.CRC32
import java.util.zip.Inflater

/** Validates PNG containers and decodes scanlines directly into immutable ARGB pixels. */
internal object PngDecoder {
    private const val IHDR = 0x49484452
    private const val PLTE = 0x504c5445
    private const val TRNS = 0x74524e53
    private const val IDAT = 0x49444154
    private const val IEND = 0x49454e44
    private val signature = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)

    // Adam7 passes followed by the single noninterlaced pass.
    private val xStarts = intArrayOf(0, 4, 0, 2, 0, 1, 0, 0)
    private val yStarts = intArrayOf(0, 0, 4, 0, 2, 0, 1, 0)
    private val xSteps = intArrayOf(8, 8, 4, 4, 2, 2, 1, 1)
    private val ySteps = intArrayOf(8, 8, 8, 4, 4, 2, 2, 1)

    fun decode(
        bytes: ByteArray,
        length: Int,
    ): TerminalRenderImage {
        val header = validate(bytes, length)
        val dataLength = compactImageData(bytes, length)
        return decodeImageStream(bytes, dataLength, header)
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
                header.transparentRed = shortAt(bytes, offset) and maxSample
                header.transparentGreen = shortAt(bytes, offset + 2) and maxSample
                header.transparentBlue = shortAt(bytes, offset + 4) and maxSample
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

    /** All chunks are validated first. Copy only IDAT bytes; writes never overtake unread data. */
    private fun compactImageData(
        bytes: ByteArray,
        length: Int,
    ): Int {
        var read = 8
        var write = 0
        while (read < length) {
            val size = intAt(bytes, read)
            if (intAt(bytes, read + 4) == IDAT) {
                bytes.copyInto(bytes, write, read + 8, read + 8 + size)
                write += size
            }
            read += size + 12
        }
        return write
    }

    /** Two scanline buffers are reused across passes; no native-depth image is retained. */
    private fun decodeImageStream(
        bytes: ByteArray,
        length: Int,
        header: PngHeader,
    ): TerminalRenderImage {
        if (length == 0) invalid()
        val channels =
            when (header.colorType) {
                0, 3 -> 1
                2 -> 3
                4 -> 2
                else -> 4
            }
        val bitsPerPixel = channels * header.depth
        val bytesPerPixel = (bitsPerPixel + 7) / 8
        val rowCapacity = (header.width * bitsPerPixel + 7) / 8 + 1
        var row = ByteArray(rowCapacity)
        var previous = ByteArray(rowCapacity)
        val pixels = IntArray(header.width * header.height)
        val inflater = Inflater()
        try {
            inflater.setInput(bytes, 0, length)
            val firstPass = if (header.interlace == 0) 7 else 0
            val endPass = if (header.interlace == 0) 8 else 7
            for (pass in firstPass until endPass) {
                val width = (header.width - xStarts[pass] + xSteps[pass] - 1) / xSteps[pass]
                if (width <= 0 || yStarts[pass] >= header.height) continue
                val rowLength = (width * bitsPerPixel + 7) / 8 + 1
                previous.fill(0, 0, rowLength)
                var y = yStarts[pass]
                while (y < header.height) {
                    var used = 0
                    while (used < rowLength) {
                        val count = inflater.inflate(row, used, rowLength - used)
                        if (count == 0) invalid()
                        used += count
                    }
                    unfilter(row, previous, rowLength, bytesPerPixel)
                    var x = xStarts[pass]
                    var sample = 0
                    while (x < header.width) {
                        pixels[y * header.width + x] = pixel(row, sample, header)
                        sample += channels
                        x += xSteps[pass]
                    }
                    val swap = previous
                    previous = row
                    row = swap
                    y += ySteps[pass]
                }
            }
            // Rows can end before zlib's checksum. Accept no extra expanded byte,
            // concatenated stream, or trailing compressed suffix.
            if (inflater.inflate(row, 0, 1) != 0 || !inflater.finished() || inflater.remaining != 0) invalid()
        } finally {
            inflater.end()
        }
        return DecodedGraphicsImage(header.width, header.height, pixels)
    }

    private fun unfilter(
        row: ByteArray,
        previous: ByteArray,
        length: Int,
        stride: Int,
    ) {
        val filter = row[0].toInt() and 255
        if (filter > 4) invalid()
        if (filter == 0) return
        var index = 1
        while (index < length) {
            val left = if (index > stride) row[index - stride].toInt() and 255 else 0
            val up = previous[index].toInt() and 255
            val upperLeft = if (index > stride) previous[index - stride].toInt() and 255 else 0
            val prediction =
                when (filter) {
                    1 -> left
                    2 -> up
                    3 -> (left + up) / 2
                    else -> paeth(left, up, upperLeft)
                }
            row[index] = (row[index].toInt() + prediction).toByte()
            index++
        }
    }

    private fun paeth(
        left: Int,
        up: Int,
        upperLeft: Int,
    ): Int {
        val prediction = left + up - upperLeft
        val leftDistance = kotlin.math.abs(prediction - left)
        val upDistance = kotlin.math.abs(prediction - up)
        val cornerDistance = kotlin.math.abs(prediction - upperLeft)
        return when {
            leftDistance <= upDistance && leftDistance <= cornerDistance -> left
            upDistance <= cornerDistance -> up
            else -> upperLeft
        }
    }

    private fun pixel(
        row: ByteArray,
        sample: Int,
        header: PngHeader,
    ): Int {
        val first = sample(row, sample, header.depth)
        return when (header.colorType) {
            3 -> {
                if (first >= header.palette.size) invalid()
                header.palette[first]
            }
            0, 4 -> {
                val gray = sample8(first, header.depth)
                val alpha =
                    when {
                        header.colorType == 4 -> sample8(sample(row, sample + 1, header.depth), header.depth)
                        first == header.transparentGray -> 0
                        else -> 255
                    }
                (alpha shl 24) or (gray shl 16) or (gray shl 8) or gray
            }
            else -> {
                val green = sample(row, sample + 1, header.depth)
                val blue = sample(row, sample + 2, header.depth)
                val alpha =
                    when {
                        header.colorType == 6 -> sample8(sample(row, sample + 3, header.depth), header.depth)
                        first == header.transparentRed && green == header.transparentGreen && blue == header.transparentBlue -> 0
                        else -> 255
                    }
                (alpha shl 24) or (sample8(first, header.depth) shl 16) or
                    (sample8(green, header.depth) shl 8) or sample8(blue, header.depth)
            }
        }
    }

    private fun sample(
        row: ByteArray,
        index: Int,
        depth: Int,
    ): Int =
        when (depth) {
            16 -> shortAt(row, 1 + index * 2)
            8 -> row[1 + index].toInt() and 255
            else -> {
                val bit = index * depth
                (row[1 + bit / 8].toInt() ushr (8 - depth - bit % 8)) and ((1 shl depth) - 1)
            }
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
        var transparentRed = -1
        var transparentGreen = -1
        var transparentBlue = -1
    }
}

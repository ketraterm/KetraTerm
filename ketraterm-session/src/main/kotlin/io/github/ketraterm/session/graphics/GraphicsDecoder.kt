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
import java.io.IOException
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/** A completed decode owns its immutable image; failures never carry partially decoded pixels. */
internal class GraphicsDecodeResult(
    val status: TerminalGraphicsResult,
    val image: TerminalRenderImage? = null,
)

/**
 * Synchronous, bounded image decoding outside session mutation locks. The caller
 * exclusively owns [decode]'s staging array until it returns: PNG compaction may
 * overwrite it. No staging bytes, AWT object, or codec state escape a decode.
 */
internal class GraphicsDecoder {
    fun decode(
        command: TerminalGraphicsCommand,
        bytes: ByteArray,
        length: Int,
    ): GraphicsDecodeResult {
        require(length in 0..bytes.size) { "Invalid graphics payload length" }
        if (length > TerminalGraphicsLimits.TRANSFER_BYTES) return GraphicsDecodeResult(TerminalGraphicsResult.TOO_LARGE)
        val format = command.valueOr('f', 32)
        val compression = command.valueOr('o', 0)
        if (command.valueOr('t', 'd'.code.toLong()) != 'd'.code.toLong() ||
            format != 24L &&
            format != 32L &&
            format != 100L ||
            compression != 0L &&
            compression != 'z'.code.toLong()
        ) {
            return GraphicsDecodeResult(TerminalGraphicsResult.UNSUPPORTED)
        }
        return try {
            if (format == 100L) {
                val png =
                    if (compression == 0L) {
                        bytes
                    } else {
                        val size = command.valueOr('S', 0)
                        if (size <= 0L) throw GraphicsDecodeException(TerminalGraphicsResult.INVALID)
                        if (size > TerminalGraphicsLimits.TRANSFER_BYTES) throw GraphicsDecodeException(TerminalGraphicsResult.TOO_LARGE)
                        inflate(bytes, length, size.toInt())
                    }
                GraphicsDecodeResult(TerminalGraphicsResult.OK, PngDecoder.decode(png, if (png === bytes) length else png.size))
            } else {
                val width = command.valueOr('s', 0)
                val height = command.valueOr('v', 0)
                validateDimensions(width, height)
                val stride = (format / 8).toInt()
                val expected = (width * height * stride).toInt()
                val raw = if (compression == 0L) bytes else inflate(bytes, length, expected)
                if ((if (raw === bytes) length else raw.size) != expected) throw GraphicsDecodeException(TerminalGraphicsResult.INVALID)
                val pixels = IntArray((width * height).toInt())
                var source = 0
                var destination = 0
                while (destination < pixels.size) {
                    val red = raw[source++].toInt() and 255
                    val green = raw[source++].toInt() and 255
                    val blue = raw[source++].toInt() and 255
                    val alpha = if (stride == 4) raw[source++].toInt() and 255 else 255
                    pixels[destination++] = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
                }
                GraphicsDecodeResult(TerminalGraphicsResult.OK, DecodedGraphicsImage(width.toInt(), height.toInt(), pixels))
            }
        } catch (failure: GraphicsDecodeException) {
            GraphicsDecodeResult(failure.status)
        } catch (_: DataFormatException) {
            GraphicsDecodeResult(TerminalGraphicsResult.INVALID)
        } catch (_: IOException) {
            GraphicsDecodeResult(TerminalGraphicsResult.INVALID)
        }
    }

    private fun inflate(
        bytes: ByteArray,
        length: Int,
        expected: Int,
    ): ByteArray {
        val result = ByteArray(expected)
        val inflater = Inflater()
        try {
            inflater.setInput(bytes, 0, length)
            var written = 0
            while (written < result.size && !inflater.finished()) {
                val count = inflater.inflate(result, written, result.size - written)
                if (count == 0 && !inflater.finished()) throw GraphicsDecodeException(TerminalGraphicsResult.INVALID)
                written += count
            }
            // A full output buffer can precede the checksum/end marker. Consume it,
            // while detecting even one extra expanded byte without growing storage.
            if (written == result.size && !inflater.finished()) {
                if (inflater.inflate(ByteArray(1)) != 0) throw GraphicsDecodeException(TerminalGraphicsResult.INVALID)
            }
            if (!inflater.finished() || inflater.remaining != 0 || written != expected) {
                throw GraphicsDecodeException(TerminalGraphicsResult.INVALID)
            }
            return result
        } finally {
            inflater.end()
        }
    }
}

/** Checks wire dimensions before multiplication or any raster allocation. */
internal fun validateDimensions(
    width: Long,
    height: Long,
) {
    if (width <= 0L || height <= 0L) throw GraphicsDecodeException(TerminalGraphicsResult.INVALID)
    if (width > TerminalGraphicsLimits.DIMENSION ||
        height > TerminalGraphicsLimits.DIMENSION ||
        width * height > TerminalGraphicsLimits.PIXELS
    ) {
        throw GraphicsDecodeException(TerminalGraphicsResult.TOO_LARGE)
    }
}

/** Stack traces are unnecessary for rejected, untrusted image data. */
internal class GraphicsDecodeException(
    val status: TerminalGraphicsResult,
) : Exception(null, null, false, false)

/** Takes exclusive ownership of newly normalized pixels; only copying access escapes. */
internal class DecodedGraphicsImage(
    override val width: Int,
    override val height: Int,
    private val pixels: IntArray,
) : TerminalRenderImage {
    override fun copyArgb(
        destination: IntArray,
        offset: Int,
    ) {
        require(offset >= 0 && offset <= destination.size && pixels.size <= destination.size - offset) {
            "Destination cannot hold the image pixels"
        }
        pixels.copyInto(destination, offset)
    }
}

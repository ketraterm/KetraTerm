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
package io.github.ketraterm.parser.ansi.osc

/** OSC 21 color decoding, independent of the older OSC 4 color syntax. */
internal object KittyColorParser {
    /** Returns unsigned packed ARGB, or -1 for an invalid value in the borrowed byte range. */
    fun parse(
        payload: ByteArray,
        start: Int,
        end: Int,
    ): Long {
        if (start < 0 || end > payload.size || start >= end) return -1L
        var colorStart = start
        var valueEnd = end
        while (colorStart < valueEnd && isWhitespace(payload[colorStart])) colorStart++
        while (valueEnd > colorStart && isWhitespace(payload[valueEnd - 1])) valueEnd--
        if (colorStart == valueEnd) return -1L

        var colorEnd = colorStart
        while (colorEnd < valueEnd && payload[colorEnd] != '@'.code.toByte()) colorEnd++
        var alpha = 255
        if (colorEnd < valueEnd) {
            alpha = parseDecimalChannel(payload, colorEnd + 1, valueEnd)
            if (alpha < 0) return -1L
        }
        while (colorEnd > colorStart && isWhitespace(payload[colorEnd - 1])) colorEnd--
        if (colorStart == colorEnd) return -1L

        val rgb =
            when {
                payload[colorStart] == '#'.code.toByte() -> parseHex(payload, colorStart + 1, colorEnd)
                hasPrefix(payload, colorStart, colorEnd, "rgb:") -> parseChannels(payload, colorStart + 4, colorEnd, floating = false)
                hasPrefix(payload, colorStart, colorEnd, "rgbi:") -> parseChannels(payload, colorStart + 5, colorEnd, floating = true)
                else -> GeneratedKittyColorNames.lookup(payload, colorStart, colorEnd)
            }
        if (rgb < 0) return -1L
        return (alpha.toLong() shl 24) or rgb.toLong()
    }

    private fun parseHex(
        payload: ByteArray,
        start: Int,
        end: Int,
    ): Int {
        val length = end - start
        if (length != 3 && length != 6 && length != 9 && length != 12) return -1
        val digits = length / 3
        var rgb = 0
        var offset = start
        while (offset < end) {
            val channel = parseHexChannel(payload, offset, offset + digits)
            if (channel < 0) return -1
            // The # form places digits in the most significant bits; it does not scale.
            val component = if (digits == 1) channel shl 4 else channel ushr ((digits - 2) * 4)
            rgb = (rgb shl 8) or component
            offset += digits
        }
        return rgb
    }

    private fun parseChannels(
        payload: ByteArray,
        start: Int,
        end: Int,
        floating: Boolean,
    ): Int {
        var offset = start
        var rgb = 0
        var channel = 0
        while (channel < 3) {
            var channelEnd = offset
            while (channelEnd < end && payload[channelEnd] != '/'.code.toByte()) channelEnd++
            if ((channel < 2) != (channelEnd < end)) return -1
            val component =
                if (floating) {
                    val value = parseDecimalChannel(payload, offset, channelEnd)
                    if (value < 0) return -1
                    value
                } else {
                    val digits = channelEnd - offset
                    if (digits !in 1..4) return -1
                    val value = parseHexChannel(payload, offset, channelEnd)
                    if (value < 0) return -1
                    val maximum = (1 shl (digits * 4)) - 1
                    (value * 255 + maximum / 2) / maximum
                }
            rgb = (rgb shl 8) or component
            offset = channelEnd + 1
            channel++
        }
        return rgb
    }

    private fun parseHexChannel(
        payload: ByteArray,
        start: Int,
        end: Int,
    ): Int {
        var value = 0
        var offset = start
        while (offset < end) {
            val digit =
                when (val byte = payload[offset].toInt()) {
                    in '0'.code..'9'.code -> byte - '0'.code
                    in 'a'.code..'f'.code -> byte - 'a'.code + 10
                    in 'A'.code..'F'.code -> byte - 'A'.code + 10
                    else -> return -1
                }
            value = (value shl 4) or digit
            offset++
        }
        return value
    }

    /** Validates the entire decimal, clips to 0..1, and rounds to the nearest byte (ties up); -1 if invalid. */
    private fun parseDecimalChannel(
        payload: ByteArray,
        start: Int,
        end: Int,
    ): Int {
        var offset = start
        var numberEnd = end
        while (offset < numberEnd && isWhitespace(payload[offset])) offset++
        while (numberEnd > offset && isWhitespace(payload[numberEnd - 1])) numberEnd--
        if (offset == numberEnd) return -1
        val negative = payload[offset] == '-'.code.toByte()
        if (negative || payload[offset] == '+'.code.toByte()) offset++

        val mantissaStart = offset
        var fractionalDigits = 0
        var hasDigit = false
        var hasPoint = false
        while (offset < numberEnd) {
            val byte = payload[offset].toInt()
            if (byte == '.'.code && !hasPoint) {
                hasPoint = true
            } else if (byte in '0'.code..'9'.code) {
                hasDigit = true
                if (hasPoint) fractionalDigits++
            } else {
                break
            }
            offset++
        }
        if (!hasDigit) return -1
        val mantissaEnd = offset
        var decimalPlace = -fractionalDigits.toLong()
        if (offset < numberEnd && (payload[offset] == 'e'.code.toByte() || payload[offset] == 'E'.code.toByte())) {
            offset++
            var exponentNegative = false
            if (offset < numberEnd) {
                exponentNegative = payload[offset] == '-'.code.toByte()
                if (exponentNegative || payload[offset] == '+'.code.toByte()) offset++
            }
            val exponentStart = offset
            var exponent = 0L
            // Multiplication by 255 adds at most three digits; larger exponents
            // cannot be cancelled by any mantissa that fits in this input.
            val exponentLimit = (end - start).toLong() + 3
            while (offset < numberEnd && payload[offset].toInt() in '0'.code..'9'.code) {
                exponent = minOf(exponent * 10 + payload[offset] - '0'.code, exponentLimit)
                offset++
            }
            if (offset == exponentStart) return -1
            decimalPlace += if (exponentNegative) -exponent else exponent
        }
        if (offset != numberEnd) return -1
        if (negative) return 0

        // Multiply the decimal digits by 255 from right to left, carrying exactly.
        // Retain only the integer places and the tenths digit needed for rounding.
        // This also handles long mantissas without a precision limit or scratch storage.
        var digitOffset = mantissaEnd - 1
        var carry = 0
        var channel = 0
        var roundUp = 0
        while (digitOffset >= mantissaStart || carry != 0) {
            if (digitOffset >= mantissaStart && payload[digitOffset] == '.'.code.toByte()) {
                digitOffset--
                continue
            }
            val digit = if (digitOffset >= mantissaStart) payload[digitOffset--].toInt() - '0'.code else 0
            val product = digit * 255 + carry
            val scaledDigit = product % 10
            carry = product / 10
            when (decimalPlace) {
                -1L -> roundUp = if (scaledDigit >= 5) 1 else 0
                0L -> channel += scaledDigit
                1L -> channel += scaledDigit * 10
                2L -> channel += scaledDigit * 100
                else -> if (decimalPlace > 2 && scaledDigit != 0) return 255
            }
            decimalPlace++
        }
        return minOf(channel + roundUp, 255)
    }

    private fun hasPrefix(
        payload: ByteArray,
        start: Int,
        end: Int,
        prefix: String,
    ): Boolean {
        if (end - start < prefix.length) return false
        var index = 0
        while (index < prefix.length) {
            var byte = payload[start + index].toInt()
            if (byte in 'A'.code..'Z'.code) byte += 'a'.code - 'A'.code
            if (byte != prefix[index].code) return false
            index++
        }
        return true
    }

    private fun isWhitespace(byte: Byte): Boolean = byte == ' '.code.toByte() || byte.toInt() in 9..13
}

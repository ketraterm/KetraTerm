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
package io.github.jvterm.core.buffer.impl

import io.github.jvterm.core.api.TerminalResponseChannel
import io.github.jvterm.core.codec.AttributeCodec
import io.github.jvterm.core.model.CellColor
import io.github.jvterm.core.model.CellColorKind
import io.github.jvterm.core.model.UnderlineStyle
import io.github.jvterm.core.state.TerminalState

internal class BufferResponseChannel(
    private val state: TerminalState,
) : TerminalResponseChannel {
    override val pendingResponseBytes: Int
        get() = state.hostResponses.pendingByteCount

    override fun readResponseBytes(
        dst: ByteArray,
        offset: Int,
        length: Int,
    ): Int = state.hostResponses.read(dst, offset, length)

    override fun clearResponseBytes() {
        state.hostResponses.clear()
    }

    override fun requestDeviceStatusReport(
        mode: Int,
        decPrivate: Boolean,
    ) {
        when {
            !decPrivate && mode == 5 -> enqueueOperatingStatusReport()
            mode == 6 -> enqueueCursorPositionReport(decPrivate)
        }
    }

    override fun requestDeviceAttributes(
        kind: Int,
        parameter: Int,
    ) {
        if (parameter != 0) return

        when (kind) {
            TerminalResponseChannel.DEVICE_ATTRIBUTES_PRIMARY -> {
                // Conservative VT100-with-advanced-video identity. Avoid overclaiming xterm.
                enqueuePrimaryDeviceAttributes()
            }
            TerminalResponseChannel.DEVICE_ATTRIBUTES_SECONDARY -> {
                // Generic versionless secondary DA. Avoid leaking product/version identity.
                enqueueSecondaryDeviceAttributes()
            }
            TerminalResponseChannel.DEVICE_ATTRIBUTES_TERTIARY -> {
                // DA3 can expose a stable terminal unit id. Keep it silent until policy exists.
            }
        }
    }

    override fun setWindowSizePixels(
        width: Int,
        height: Int,
    ) {
        if (width <= 0 || height <= 0) {
            state.windowPixelWidth = 0
            state.windowPixelHeight = 0
            return
        }

        state.windowPixelWidth = width
        state.windowPixelHeight = height
    }

    override fun requestWindowReport(mode: Int) {
        when (mode) {
            TerminalResponseChannel.WINDOW_REPORT_PIXELS -> {
                if (state.windowPixelWidth > 0 && state.windowPixelHeight > 0) {
                    enqueueWindowReport(
                        reportType = 4,
                        height = state.windowPixelHeight,
                        width = state.windowPixelWidth,
                    )
                }
            }
            TerminalResponseChannel.WINDOW_REPORT_GRID_CELLS ->
                enqueueWindowReport(
                    reportType = 8,
                    height = state.dimensions.height,
                    width = state.dimensions.width,
                )
        }
    }

    private fun enqueueOperatingStatusReport() {
        enqueueCsiPrefix()
        state.hostResponses.enqueueByte('0'.code)
        state.hostResponses.enqueueByte('n'.code)
    }

    private fun enqueuePrimaryDeviceAttributes() {
        enqueueCsiPrefix()
        state.hostResponses.enqueueByte('?'.code)
        state.hostResponses.enqueueByte('1'.code)
        state.hostResponses.enqueueByte(';'.code)
        state.hostResponses.enqueueByte('2'.code)
        state.hostResponses.enqueueByte('c'.code)
    }

    private fun enqueueSecondaryDeviceAttributes() {
        enqueueCsiPrefix()
        state.hostResponses.enqueueByte('>'.code)
        state.hostResponses.enqueueByte('0'.code)
        state.hostResponses.enqueueByte(';'.code)
        state.hostResponses.enqueueByte('0'.code)
        state.hostResponses.enqueueByte(';'.code)
        state.hostResponses.enqueueByte('0'.code)
        state.hostResponses.enqueueByte('c'.code)
    }

    private fun enqueueCursorPositionReport(decPrivate: Boolean) {
        enqueueCsiPrefix()
        if (decPrivate) {
            state.hostResponses.enqueueByte('?'.code)
        }
        state.hostResponses.enqueuePositiveDecimal(state.cursor.row + 1)
        state.hostResponses.enqueueByte(';'.code)
        state.hostResponses.enqueuePositiveDecimal(state.cursor.col + 1)
        state.hostResponses.enqueueByte('R'.code)
    }

    private fun enqueueWindowReport(
        reportType: Int,
        height: Int,
        width: Int,
    ) {
        enqueueCsiPrefix()
        state.hostResponses.enqueuePositiveDecimal(reportType)
        state.hostResponses.enqueueByte(';'.code)
        state.hostResponses.enqueuePositiveDecimal(height)
        state.hostResponses.enqueueByte(';'.code)
        state.hostResponses.enqueuePositiveDecimal(width)
        state.hostResponses.enqueueByte('t'.code)
    }

    override fun queryPaletteColor(index: Int) {
        if (index !in 0..255) return
        val color = state.palette.indexedColor(index)
        enqueueOscPrefix()
        state.hostResponses.enqueueByte('4'.code)
        state.hostResponses.enqueueByte(';'.code)
        state.hostResponses.enqueuePositiveDecimal(index)
        state.hostResponses.enqueueByte(';'.code)
        enqueueColorResponse(color)
        enqueueStSuffix()
    }

    override fun queryDynamicColor(target: Int) {
        val color =
            when (target) {
                10 -> state.palette.defaultForeground
                11 -> state.palette.defaultBackground
                12 -> state.palette.cursorBackground
                else -> return
            }
        enqueueOscPrefix()
        state.hostResponses.enqueuePositiveDecimal(target)
        state.hostResponses.enqueueByte(';'.code)
        enqueueColorResponse(color)
        enqueueStSuffix()
    }

    override fun queryStatusString(query: String) {
        when (query) {
            "m" -> {
                val sgrString = getActiveSgrString()
                enqueueDecrqssResponse(status = 1, sgrString)
            }
            "r" -> {
                val top = state.activeBuffer.scrollTop + 1
                val bottom = state.activeBuffer.scrollBottom + 1
                enqueueDecrqssResponse(status = 1, "$top;${bottom}r")
            }
            "s" -> {
                val left = state.activeBuffer.leftMargin + 1
                val right = state.activeBuffer.rightMargin + 1
                enqueueDecrqssResponse(status = 1, "$left;${right}s")
            }
            "q" -> {
                val shapeCode =
                    when (state.cursorShape) {
                        io.github.jvterm.render.api.TerminalRenderCursorShape.BLOCK -> if (state.modes.isCursorBlinking) 1 else 2
                        io.github.jvterm.render.api.TerminalRenderCursorShape.UNDERLINE -> if (state.modes.isCursorBlinking) 3 else 4
                        io.github.jvterm.render.api.TerminalRenderCursorShape.BAR -> if (state.modes.isCursorBlinking) 5 else 6
                    }
                enqueueDecrqssResponse(status = 1, "$shapeCode q")
            }
            else -> {
                enqueueDecrqssResponse(status = 0, query)
            }
        }
    }

    /**
     * Emits `DCS [status] $ r [responseData] ST`.
     *
     * Status 1 = valid, status 0 = invalid/unsupported. Response data is the
     * setting value for valid queries or the original query string for failures.
     * All response data characters are ASCII, so char-by-char enqueue is safe.
     */
    private fun enqueueDecrqssResponse(
        status: Int,
        responseData: String,
    ) {
        state.hostResponses.enqueueByte(io.github.jvterm.protocol.ControlCode.ESC)
        state.hostResponses.enqueueByte('P'.code)
        state.hostResponses.enqueuePositiveDecimal(status)
        state.hostResponses.enqueueByte('$'.code)
        state.hostResponses.enqueueByte('r'.code)
        enqueueAsciiString(responseData)
        enqueueStSuffix()
    }

    /**
     * Resolves XTGETTCAP capability queries against the security allowlist and
     * streams the response directly into the host response queue.
     *
     * The [rawPayload] contains one or more semicolon-separated hex-encoded
     * capability names. Each name is decoded, checked against the allowlist,
     * and if recognized, its hex-encoded key (optionally `=value`) is written
     * as part of a success response (`DCS 1 + r ... ST`). Unrecognized names
     * are silently omitted. If nothing matches, a failure response is sent
     * (`DCS 0 + r ST`).
     *
     * **Allowlisted capabilities:**
     * - `Co` / `colors` → `256`
     * - `TN` / `name` → `xterm-256color`
     * - `RGB` / `Tc` → boolean (present, no value)
     */
    override fun queryTerminfo(rawPayload: String) {
        // First pass: determine if any capability matches so we know success/failure.
        var anyMatched = false
        var start = 0
        while (start <= rawPayload.length) {
            val end = rawPayload.indexOf(';', start).let { if (it < 0) rawPayload.length else it }
            val capHex = rawPayload.substring(start, end)
            val capName = decodeHex(capHex)
            if (capName != null && resolveCapability(capName) != null) {
                anyMatched = true
                break
            }
            start = end + 1
        }

        if (!anyMatched) {
            // DCS 0 + r ST
            enqueueXtgettcapPrefix('0')
            enqueueStSuffix()
            return
        }

        // DCS 1 + r [results] ST
        enqueueXtgettcapPrefix('1')
        var first = true
        start = 0
        while (start <= rawPayload.length) {
            val end = rawPayload.indexOf(';', start).let { if (it < 0) rawPayload.length else it }
            val capHex = rawPayload.substring(start, end)
            val capName = decodeHex(capHex)
            if (capName != null) {
                val capValue = resolveCapability(capName)
                if (capValue != null) {
                    if (!first) {
                        state.hostResponses.enqueueByte(';'.code)
                    }
                    first = false
                    enqueueAsciiString(capHex)
                    if (capValue.isNotEmpty()) {
                        state.hostResponses.enqueueByte('='.code)
                        enqueueHexEncoded(capValue)
                    }
                }
            }
            start = end + 1
        }
        enqueueStSuffix()
    }

    /** Emits `ESC P [statusChar] + r`. */
    private fun enqueueXtgettcapPrefix(statusChar: Char) {
        state.hostResponses.enqueueByte(io.github.jvterm.protocol.ControlCode.ESC)
        state.hostResponses.enqueueByte('P'.code)
        state.hostResponses.enqueueByte(statusChar.code)
        state.hostResponses.enqueueByte('+'.code)
        state.hostResponses.enqueueByte('r'.code)
    }

    /**
     * Security allowlist for XTGETTCAP capability queries.
     *
     * Returns the capability value string, empty string for boolean capabilities,
     * or null if the capability is not recognized/allowed.
     */
    private fun resolveCapability(name: String): String? =
        when (name) {
            "Co", "colors" -> "256"
            "TN", "name" -> "xterm-256color"
            "RGB", "Tc" -> "" // Boolean capability: present without value.
            else -> null
        }

    /**
     * Decodes a hex-encoded ASCII string (e.g. `"436f"` → `"Co"`).
     *
     * Returns null if the hex string has odd length or contains non-hex characters.
     * Each pair of hex digits produces one byte of the result string.
     */
    private fun decodeHex(hex: String): String? {
        val clean = hex.trim()
        if (clean.isEmpty() || clean.length % 2 != 0) return null
        val bytes = ByteArray(clean.length / 2)
        for (i in bytes.indices) {
            val digit1 = Character.digit(clean[i * 2], 16)
            val digit2 = Character.digit(clean[i * 2 + 1], 16)
            if (digit1 < 0 || digit2 < 0) return null
            bytes[i] = ((digit1 shl 4) or digit2).toByte()
        }
        return bytes.decodeToString()
    }

    /**
     * Hex-encodes [str] and writes the hex digits directly to the response queue.
     *
     * Each byte of the UTF-8 encoding produces two lowercase hex digit bytes.
     * This avoids building an intermediate hex string via StringBuilder.
     */
    private fun enqueueHexEncoded(str: String) {
        for (ch in str) {
            val b = ch.code and 0xFF
            state.hostResponses.enqueueByte(HEX_CHARS[b ushr 4].code)
            state.hostResponses.enqueueByte(HEX_CHARS[b and 0x0F].code)
        }
    }

    /** Writes each character of an ASCII string directly to the response queue. */
    private fun enqueueAsciiString(str: String) {
        for (ch in str) {
            state.hostResponses.enqueueByte(ch.code)
        }
    }

    /**
     * Builds the DECRQSS SGR response string for the current pen attributes.
     *
     * Unpacks the active pen into its individual SGR components and reassembles
     * them into the canonical semicolon-separated form terminated by `m`.
     * A completely default pen returns `"0m"` (the SGR reset identity).
     *
     * The returned string maps directly to what `CSI [params] m` would produce
     * the current pen state. This includes bold, faint, italic, underline style
     * (single/double/curly/dotted/dashed), blink, inverse, conceal, strikethrough,
     * overline, foreground/background/underline color in indexed-8, indexed-16,
     * indexed-256, and RGB forms.
     */
    private fun getActiveSgrString(): String {
        val attrs = AttributeCodec.unpack(state.pen.currentAttr, state.pen.currentExtendedAttr)
        val list = mutableListOf<String>()

        val hasAttr =
            attrs.bold ||
                attrs.faint ||
                attrs.italic ||
                attrs.blink ||
                attrs.inverse ||
                attrs.conceal ||
                attrs.strikethrough ||
                attrs.overline ||
                attrs.underlineStyle != UnderlineStyle.NONE ||
                attrs.foreground != CellColor.DEFAULT ||
                attrs.background != CellColor.DEFAULT ||
                attrs.underlineColor != CellColor.DEFAULT

        if (!hasAttr) {
            return "0m"
        }

        if (attrs.bold) list.add("1")
        if (attrs.faint) list.add("2")
        if (attrs.italic) list.add("3")
        if (attrs.underlineStyle == UnderlineStyle.SINGLE) {
            list.add("4")
        } else if (attrs.underlineStyle == UnderlineStyle.DOUBLE) {
            list.add("21")
        } else if (attrs.underlineStyle != UnderlineStyle.NONE) {
            list.add("4:${attrs.underlineStyle.sgrCode}")
        }
        if (attrs.blink) list.add("5")
        if (attrs.inverse) list.add("7")
        if (attrs.conceal) list.add("8")
        if (attrs.strikethrough) list.add("9")
        if (attrs.overline) list.add("53")

        appendColorSgr(list, attrs.foreground, 30, 90, 38)
        appendColorSgr(list, attrs.background, 40, 100, 48)
        appendUnderlineColorSgr(list, attrs.underlineColor)

        return list.joinToString(";") + "m"
    }

    /** Appends foreground or background SGR codes for the given [color]. */
    private fun appendColorSgr(
        list: MutableList<String>,
        color: CellColor,
        base8: Int,
        base16: Int,
        extBase: Int,
    ) {
        when (color.kind) {
            CellColorKind.INDEXED -> {
                val idx = color.value
                if (idx < 8) {
                    list.add((base8 + idx).toString())
                } else if (idx < 16) {
                    list.add((base16 + (idx - 8)).toString())
                } else {
                    list.add("$extBase;5;$idx")
                }
            }
            CellColorKind.RGB -> {
                val r = (color.value ushr 16) and 0xFF
                val g = (color.value ushr 8) and 0xFF
                val b = color.value and 0xFF
                list.add("$extBase;2;$r;$g;$b")
            }
            CellColorKind.DEFAULT -> {}
        }
    }

    /** Appends underline color SGR codes (58;5;N or 58;2;R;G;B). */
    private fun appendUnderlineColorSgr(
        list: MutableList<String>,
        color: CellColor,
    ) {
        when (color.kind) {
            CellColorKind.INDEXED -> {
                list.add("58;5;${color.value}")
            }
            CellColorKind.RGB -> {
                val r = (color.value ushr 16) and 0xFF
                val g = (color.value ushr 8) and 0xFF
                val b = color.value and 0xFF
                list.add("58;2;$r;$g;$b")
            }
            CellColorKind.DEFAULT -> {}
        }
    }

    private fun enqueueColorResponse(argb: Int) {
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF

        state.hostResponses.enqueueByte('r'.code)
        state.hostResponses.enqueueByte('g'.code)
        state.hostResponses.enqueueByte('b'.code)
        state.hostResponses.enqueueByte(':'.code)
        enqueue4HexDigits(r)
        state.hostResponses.enqueueByte('/'.code)
        enqueue4HexDigits(g)
        state.hostResponses.enqueueByte('/'.code)
        enqueue4HexDigits(b)
    }

    private fun enqueue4HexDigits(value8: Int) {
        val value16 = (value8 shl 8) or value8
        state.hostResponses.enqueueByte(HEX_CHARS[(value16 ushr 12) and 0xF].code)
        state.hostResponses.enqueueByte(HEX_CHARS[(value16 ushr 8) and 0xF].code)
        state.hostResponses.enqueueByte(HEX_CHARS[(value16 ushr 4) and 0xF].code)
        state.hostResponses.enqueueByte(HEX_CHARS[value16 and 0xF].code)
    }

    private fun enqueueOscPrefix() {
        state.hostResponses.enqueueByte(io.github.jvterm.protocol.ControlCode.ESC)
        state.hostResponses.enqueueByte(']'.code)
    }

    private fun enqueueStSuffix() {
        state.hostResponses.enqueueByte(io.github.jvterm.protocol.ControlCode.ESC)
        state.hostResponses.enqueueByte('\\'.code)
    }

    private fun enqueueCsiPrefix() {
        state.hostResponses.enqueueByte(io.github.jvterm.protocol.ControlCode.ESC)
        state.hostResponses.enqueueByte('['.code)
    }

    companion object {
        private const val HEX_CHARS = "0123456789abcdef"
    }
}

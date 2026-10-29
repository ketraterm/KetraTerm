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
package io.github.ketraterm.core.buffer.impl

import io.github.ketraterm.core.api.TerminalInputState
import io.github.ketraterm.core.model.HostResponseQueue
import io.github.ketraterm.protocol.TerminalCapabilityIdentity
import io.github.ketraterm.protocol.keyboard.LegacyKeySequence
import io.github.ketraterm.protocol.keyboard.ModifyOtherKeysMode
import io.github.ketraterm.protocol.keyboard.XtermKeyResource

/** Owns the XTGETTCAP allowlist and bounded, per-capability reply encoding. */
internal object XtgettcapResponder {
    // Independent of parser collection: direct core callers also cannot build an unbounded reply.
    private const val MAX_RESPONSE_BYTES = 64 * 1024
    private const val FRAME_BYTES = 7 // ESC P, status, +r, ESC \.
    private const val HEX = "0123456789abcdef"

    private enum class Kind { BOOLEAN, NUMBER, STRING }

    private enum class KeyMode { NONE, CURSOR, KEYPAD, BACKSPACE, BACK_TAB }

    private class Capability(
        val names: Array<String>,
        val kind: Kind,
        val value: String = "",
        val available: Boolean = true,
        val keyResource: Int = -1,
        val keyMode: KeyMode = KeyMode.NONE,
        val alternateValue: String = value,
        val shiftedValues: Array<String>? = null,
    )

    // Values are decoded ASCII bytes, including real ESC bytes. Parameter expressions
    // belong to the application; the terminal never evaluates a terminfo program.
    private val capabilities =
        arrayOf(
            Capability(arrayOf("TN", "name"), Kind.STRING, TerminalCapabilityIdentity.TERM_NAME),
            Capability(arrayOf("Co", "colors"), Kind.NUMBER, TerminalCapabilityIdentity.TERMINFO_COLOR_COUNT),
            Capability(arrayOf("RGB", "Tc"), Kind.BOOLEAN, available = TerminalCapabilityIdentity.TERMINFO_TRUECOLOR_SUPPORTED),
            Capability(arrayOf("indn"), Kind.STRING, "\u001B[%p1%dS"),
            Capability(arrayOf("PS"), Kind.STRING, "\u001B[200~"),
            Capability(arrayOf("PE"), Kind.STRING, "\u001B[201~"),
            Capability(arrayOf("Su"), Kind.BOOLEAN),
            Capability(
                arrayOf("kcuu1", "ku"),
                Kind.STRING,
                LegacyKeySequence.CURSOR_UP_NORMAL,
                keyResource = XtermKeyResource.CURSOR_KEYS,
                keyMode = KeyMode.CURSOR,
                alternateValue = LegacyKeySequence.CURSOR_UP_APPLICATION,
            ),
            Capability(
                arrayOf("kcud1", "kd"),
                Kind.STRING,
                LegacyKeySequence.CURSOR_DOWN_NORMAL,
                keyResource = XtermKeyResource.CURSOR_KEYS,
                keyMode = KeyMode.CURSOR,
                alternateValue = LegacyKeySequence.CURSOR_DOWN_APPLICATION,
            ),
            Capability(
                arrayOf("kcuf1", "kr"),
                Kind.STRING,
                LegacyKeySequence.CURSOR_RIGHT_NORMAL,
                keyResource = XtermKeyResource.CURSOR_KEYS,
                keyMode = KeyMode.CURSOR,
                alternateValue = LegacyKeySequence.CURSOR_RIGHT_APPLICATION,
            ),
            Capability(
                arrayOf("kcub1", "kl"),
                Kind.STRING,
                LegacyKeySequence.CURSOR_LEFT_NORMAL,
                keyResource = XtermKeyResource.CURSOR_KEYS,
                keyMode = KeyMode.CURSOR,
                alternateValue = LegacyKeySequence.CURSOR_LEFT_APPLICATION,
            ),
            Capability(
                arrayOf("khome", "kh"),
                Kind.STRING,
                LegacyKeySequence.HOME_NORMAL,
                keyResource = XtermKeyResource.CURSOR_KEYS,
                keyMode = KeyMode.CURSOR,
                alternateValue = LegacyKeySequence.HOME_APPLICATION,
            ),
            Capability(
                arrayOf("kend", "@7"),
                Kind.STRING,
                LegacyKeySequence.END_NORMAL,
                keyResource = XtermKeyResource.CURSOR_KEYS,
                keyMode = KeyMode.CURSOR,
                alternateValue = LegacyKeySequence.END_APPLICATION,
            ),
            Capability(arrayOf("kich1", "kI"), Kind.STRING, LegacyKeySequence.INSERT, keyResource = XtermKeyResource.CURSOR_KEYS),
            Capability(arrayOf("kdch1", "kD"), Kind.STRING, LegacyKeySequence.DELETE, keyResource = XtermKeyResource.CURSOR_KEYS),
            Capability(arrayOf("kpp", "kP"), Kind.STRING, LegacyKeySequence.PAGE_UP, keyResource = XtermKeyResource.CURSOR_KEYS),
            Capability(arrayOf("knp", "kN"), Kind.STRING, LegacyKeySequence.PAGE_DOWN, keyResource = XtermKeyResource.CURSOR_KEYS),
            shiftedCursor(arrayOf("kHOM", "#2"), LegacyKeySequence.HOME_NORMAL, LegacyKeySequence.HOME_APPLICATION),
            shiftedCursor(arrayOf("kLFT", "#4"), LegacyKeySequence.CURSOR_LEFT_NORMAL, LegacyKeySequence.CURSOR_LEFT_APPLICATION),
            shiftedCursor(arrayOf("kRIT", "%i"), LegacyKeySequence.CURSOR_RIGHT_NORMAL, LegacyKeySequence.CURSOR_RIGHT_APPLICATION),
            shiftedCursor(arrayOf("kEND", "*7"), LegacyKeySequence.END_NORMAL, LegacyKeySequence.END_APPLICATION),
            Capability(
                arrayOf("ka1", "K1"),
                Kind.STRING,
                LegacyKeySequence.KEYPAD_7_APPLICATION,
                keyResource = XtermKeyResource.KEYPAD_KEYS,
                keyMode = KeyMode.KEYPAD,
            ),
            Capability(
                arrayOf("ka3", "K3"),
                Kind.STRING,
                LegacyKeySequence.KEYPAD_9_APPLICATION,
                keyResource = XtermKeyResource.KEYPAD_KEYS,
                keyMode = KeyMode.KEYPAD,
            ),
            Capability(
                arrayOf("kc1", "K4"),
                Kind.STRING,
                LegacyKeySequence.KEYPAD_1_APPLICATION,
                keyResource = XtermKeyResource.KEYPAD_KEYS,
                keyMode = KeyMode.KEYPAD,
            ),
            Capability(
                arrayOf("kc3", "K5"),
                Kind.STRING,
                LegacyKeySequence.KEYPAD_3_APPLICATION,
                keyResource = XtermKeyResource.KEYPAD_KEYS,
                keyMode = KeyMode.KEYPAD,
            ),
            Capability(arrayOf("kf1", "k1"), Kind.STRING, LegacyKeySequence.F1, keyResource = XtermKeyResource.FUNCTION_KEYS),
            Capability(arrayOf("kf2", "k2"), Kind.STRING, LegacyKeySequence.F2, keyResource = XtermKeyResource.FUNCTION_KEYS),
            Capability(arrayOf("kf3", "k3"), Kind.STRING, LegacyKeySequence.F3, keyResource = XtermKeyResource.FUNCTION_KEYS),
            Capability(arrayOf("kf4", "k4"), Kind.STRING, LegacyKeySequence.F4, keyResource = XtermKeyResource.FUNCTION_KEYS),
            Capability(arrayOf("kf5", "k5"), Kind.STRING, LegacyKeySequence.F5, keyResource = XtermKeyResource.FUNCTION_KEYS),
            Capability(arrayOf("kf6", "k6"), Kind.STRING, LegacyKeySequence.F6, keyResource = XtermKeyResource.FUNCTION_KEYS),
            Capability(arrayOf("kf7", "k7"), Kind.STRING, LegacyKeySequence.F7, keyResource = XtermKeyResource.FUNCTION_KEYS),
            Capability(arrayOf("kf8", "k8"), Kind.STRING, LegacyKeySequence.F8, keyResource = XtermKeyResource.FUNCTION_KEYS),
            Capability(arrayOf("kf9", "k9"), Kind.STRING, LegacyKeySequence.F9, keyResource = XtermKeyResource.FUNCTION_KEYS),
            Capability(arrayOf("kf10", "k;"), Kind.STRING, LegacyKeySequence.F10, keyResource = XtermKeyResource.FUNCTION_KEYS),
            Capability(arrayOf("kf11", "F1"), Kind.STRING, LegacyKeySequence.F11, keyResource = XtermKeyResource.FUNCTION_KEYS),
            Capability(arrayOf("kf12", "F2"), Kind.STRING, LegacyKeySequence.F12, keyResource = XtermKeyResource.FUNCTION_KEYS),
            Capability(
                arrayOf("kbs", "kb"),
                Kind.STRING,
                "\u007f",
                keyResource = XtermKeyResource.SPECIAL_KEYS,
                keyMode = KeyMode.BACKSPACE,
                alternateValue = "\b",
            ),
            Capability(
                arrayOf("kcbt", "kB"),
                Kind.STRING,
                LegacyKeySequence.BACK_TAB,
                keyResource = XtermKeyResource.SPECIAL_KEYS,
                keyMode = KeyMode.BACK_TAB,
            ),
            Capability(arrayOf("Smulx"), Kind.STRING, "\u001B[4:%p1%dm"),
            Capability(
                arrayOf("Setulc"),
                Kind.STRING,
                "\u001B[58:2::%p1%{65536}%/%d:%p1%{256}%/%{255}%&%d:%p1%{255}%&%dm",
            ),
        )

    private fun shiftedCursor(
        names: Array<String>,
        normal: String,
        application: String,
    ): Capability {
        // Build only the four distinct Shift variants at initialization. Disabled
        // modifiers reuse the existing unmodified values; replies allocate no strings.
        // Slots 0/1 are level 0's normal/application forms (level 1 shares slot 0);
        // slots 2/3 correspond directly to those cursor modifier levels.
        val final = normal.last()
        return Capability(
            names,
            Kind.STRING,
            normal,
            keyResource = XtermKeyResource.CURSOR_KEYS,
            keyMode = KeyMode.CURSOR,
            alternateValue = application,
            shiftedValues = arrayOf("\u001b[2" + final, "\u001bO2" + final, "\u001b[1;2" + final, "\u001b[>1;2" + final),
        )
    }

    /**
     * Appends one frame per well-formed name, preserving order, duplicates and hex spelling.
     * Malformed batches or responses exceeding 64 KiB append one empty failure instead.
     * Validation precedes emission so a rejected batch cannot leave partial success frames.
     */
    fun respond(
        raw: String,
        queue: HostResponseQueue,
        modeBits: Long,
        defaultBackarrowSendsBackspace: Boolean?,
    ) {
        if (!validate(raw, modeBits, defaultBackarrowSendsBackspace)) {
            append(queue, "\u001BP0+r\u001B\\")
            return
        }
        var start = 0
        while (start < raw.length) {
            val end = tokenEnd(raw, start)
            val capability = lookup(raw, start, end, modeBits, defaultBackarrowSendsBackspace)
            append(queue, if (capability == null) "\u001BP0+r" else "\u001BP1+r")
            for (index in start until end) queue.enqueueByte(raw[index].code)
            if (capability != null && capability.kind != Kind.BOOLEAN) {
                queue.enqueueByte('='.code)
                for (char in value(capability, modeBits, defaultBackarrowSendsBackspace == true)) {
                    queue.enqueueByte(HEX[char.code ushr 4].code)
                    queue.enqueueByte(HEX[char.code and 15].code)
                }
            }
            append(queue, "\u001B\\")
            start = end + 1
        }
    }

    private fun validate(
        raw: String,
        modeBits: Long,
        defaultBackarrowSendsBackspace: Boolean?,
    ): Boolean {
        // Even unknown-name replies echo the validated hex key, so a longer input
        // cannot fit the reply budget. Check before scanning caller-owned strings.
        if (raw.isEmpty() || raw.length > MAX_RESPONSE_BYTES) return false
        var start = 0
        var responseBytes = 0
        while (start <= raw.length) {
            val end = tokenEnd(raw, start)
            val length = end - start
            if (length == 0 || length % 2 != 0) return false
            for (index in start until end) if (hexDigit(raw[index]) < 0) return false
            val capability = lookup(raw, start, end, modeBits, defaultBackarrowSendsBackspace)
            val valueBytes =
                if (capability == null ||
                    capability.kind == Kind.BOOLEAN
                ) {
                    0
                } else {
                    1 + 2 * value(capability, modeBits, defaultBackarrowSendsBackspace == true).length
                }
            val frameBytes = FRAME_BYTES + length + valueBytes
            if (frameBytes > MAX_RESPONSE_BYTES - responseBytes) return false
            responseBytes += frameBytes
            start = end + 1
        }
        return true
    }

    private fun tokenEnd(
        raw: String,
        start: Int,
    ): Int = raw.indexOf(';', start).let { if (it < 0) raw.length else it }

    private fun lookup(
        raw: String,
        start: Int,
        end: Int,
        modeBits: Long,
        defaultBackarrowSendsBackspace: Boolean?,
    ): Capability? {
        for (capability in capabilities) {
            for (name in capability.names) {
                if (end - start != name.length * 2) continue
                var index = 0
                while (index < name.length) {
                    val offset = start + index * 2
                    val decoded = (hexDigit(raw[offset]) shl 4) or hexDigit(raw[offset + 1])
                    if (decoded != name[index].code) break
                    index++
                }
                if (index == name.length) return if (isAvailable(capability, modeBits, defaultBackarrowSendsBackspace)) capability else null
            }
        }
        return null
    }

    private fun isAvailable(
        capability: Capability,
        modeBits: Long,
        defaultBackarrowSendsBackspace: Boolean?,
    ): Boolean {
        if (!capability.available) return false
        if (capability.keyResource < 0) return true
        // These entries describe legacy key presses. Do not promise those bytes
        // while another protocol has taken over the corresponding encoder path.
        if (TerminalInputState.kittyKeyboardFlags(modeBits) != 0 ||
            TerminalInputState.keyModifierOption(modeBits, capability.keyResource) >= 4
        ) {
            return false
        }
        val otherKeys = TerminalInputState.modifyOtherKeysMode(modeBits)
        return when (capability.keyMode) {
            // Numeric mode emits printable digits, not distinct function-key sequences.
            // Advertising those bytes could make consumers capture ordinary digit input.
            KeyMode.KEYPAD -> TerminalInputState.isApplicationKeypad(modeBits)
            KeyMode.BACKSPACE ->
                otherKeys != ModifyOtherKeysMode.MODE_3 &&
                    (defaultBackarrowSendsBackspace != null || TerminalInputState.isBackarrowKeyModeExplicit(modeBits))
            KeyMode.BACK_TAB -> otherKeys < ModifyOtherKeysMode.MODE_2
            else -> true
        }
    }

    private fun value(
        capability: Capability,
        modeBits: Long,
        defaultBackarrowSendsBackspace: Boolean,
    ): String =
        when (capability.keyMode) {
            KeyMode.CURSOR -> cursorValue(capability, modeBits)
            KeyMode.BACKSPACE ->
                if (TerminalInputState.backarrowSendsBackspace(
                        modeBits,
                        defaultBackarrowSendsBackspace,
                    )
                ) {
                    capability.alternateValue
                } else {
                    capability.value
                }
            else -> capability.value
        }

    private fun cursorValue(
        capability: Capability,
        modeBits: Long,
    ): String {
        val application = TerminalInputState.isApplicationCursorKeys(modeBits)
        val level = TerminalInputState.keyModifierOption(modeBits, XtermKeyResource.CURSOR_KEYS)
        val shifted = capability.shiftedValues
        if (shifted != null && level >= 0) {
            // isAvailable rejects extended level 4 before selecting a legacy value.
            val variant =
                when {
                    level >= 2 -> level
                    level == 0 && application -> 1
                    else -> 0
                }
            return shifted[variant]
        }
        return if (application) capability.alternateValue else capability.value
    }

    private fun hexDigit(char: Char): Int =
        when (char) {
            in '0'..'9' -> char - '0'
            in 'a'..'f' -> char - 'a' + 10
            in 'A'..'F' -> char - 'A' + 10
            else -> -1
        }

    private fun append(
        queue: HostResponseQueue,
        text: String,
    ) {
        for (char in text) queue.enqueueByte(char.code)
    }
}

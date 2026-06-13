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
package io.github.jvterm.input.impl

import io.github.jvterm.core.api.TerminalInputState
import io.github.jvterm.input.event.TerminalPasteEvent
import io.github.jvterm.input.impl.keyboard.CsiWriter
import io.github.jvterm.input.policy.PasteSanitizationPolicy
import io.github.jvterm.input.policy.TerminalInputPolicy
import io.github.jvterm.protocol.host.TerminalHostOutput

internal class PasteEncoder(
    private val output: TerminalHostOutput,
    private val scratch: InputScratchBuffer,
    private val policy: TerminalInputPolicy = TerminalInputPolicy(),
) {
    fun encode(
        event: TerminalPasteEvent,
        modeBits: Long,
    ) {
        if (TerminalInputState.isBracketedPasteEnabled(modeBits)) {
            output.writeBytes(
                TerminalSequences.BRACKETED_PASTE_START,
                0,
                TerminalSequences.BRACKETED_PASTE_START.size,
            )
            writePasteText(event.text)
            output.writeBytes(
                TerminalSequences.BRACKETED_PASTE_END,
                0,
                TerminalSequences.BRACKETED_PASTE_END.size,
            )
        } else {
            writePasteText(event.text)
        }
    }

    private fun writePasteText(text: String) {
        when (policy.pasteSanitizationPolicy) {
            PasteSanitizationPolicy.RAW -> output.writeUtf8(text)
            PasteSanitizationPolicy.STRIP_C0_EXCEPT_TAB_CR_LF -> writeStrippedC0(text)
            PasteSanitizationPolicy.NORMALIZE_LINE_ENDINGS -> writeNormalizedLineEndings(text)
        }
    }

    private fun writeStrippedC0(text: String) {
        var offset = 0
        while (offset < text.length) {
            val codepoint = text.codePointAt(offset)
            if (isAllowedAfterC0Strip(codepoint)) {
                writeUtf8Codepoint(codepoint)
            }
            offset += Character.charCount(codepoint)
        }
    }

    private fun isAllowedAfterC0Strip(codepoint: Int): Boolean =
        codepoint !in 0x00..0x1f ||
            codepoint == TAB ||
            codepoint == CR ||
            codepoint == LF

    private fun writeNormalizedLineEndings(text: String) {
        var offset = 0
        while (offset < text.length) {
            val codepoint = text.codePointAt(offset)
            if (codepoint == CR) {
                writeUtf8Codepoint(LF)
                offset += Character.charCount(codepoint)
                if (offset < text.length && text.codePointAt(offset) == LF) {
                    offset += Character.charCount(LF)
                }
            } else {
                writeUtf8Codepoint(codepoint)
                offset += Character.charCount(codepoint)
            }
        }
    }

    private fun writeUtf8Codepoint(codepoint: Int) {
        CsiWriter.writeUtf8Codepoint(scratch, output, codepoint)
    }

    private companion object {
        private const val CR: Int = 0x0d
        private const val LF: Int = 0x0a
        private const val TAB: Int = 0x09
    }
}

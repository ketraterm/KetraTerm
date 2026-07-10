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
package io.github.ketraterm.input.impl

import io.github.ketraterm.core.api.TerminalInputState
import io.github.ketraterm.input.event.TerminalPasteEvent
import io.github.ketraterm.input.impl.keyboard.CsiWriter
import io.github.ketraterm.input.policy.PasteLineEndingPolicy
import io.github.ketraterm.input.policy.PasteSanitizationPolicy
import io.github.ketraterm.input.policy.TerminalInputPolicy
import io.github.ketraterm.protocol.host.TerminalHostOutput

internal class PasteEncoder(
    private val output: TerminalHostOutput,
    private val scratch: InputScratchBuffer,
    @Volatile internal var policy: TerminalInputPolicy = TerminalInputPolicy(),
) {
    fun encode(
        event: TerminalPasteEvent,
        modeBits: Long,
    ) {
        val bracketedPaste = TerminalInputState.isBracketedPasteEnabled(modeBits)
        if (bracketedPaste) {
            output.writeBytes(
                TerminalSequences.BRACKETED_PASTE_START,
                0,
                TerminalSequences.BRACKETED_PASTE_START.size,
            )
            writePasteText(event.text, bracketedPaste = true)
            output.writeBytes(
                TerminalSequences.BRACKETED_PASTE_END,
                0,
                TerminalSequences.BRACKETED_PASTE_END.size,
            )
        } else {
            writePasteText(event.text, bracketedPaste = false)
        }
    }

    private fun writePasteText(
        text: String,
        bracketedPaste: Boolean,
    ) {
        when (policy.pasteSanitizationPolicy) {
            PasteSanitizationPolicy.RAW -> writeRaw(text, bracketedPaste)
            PasteSanitizationPolicy.STRIP_C0_EXCEPT_TAB_CR_LF -> writeStrippedC0(text, bracketedPaste)
            PasteSanitizationPolicy.NORMALIZE_LINE_ENDINGS ->
                if (bracketedPaste) {
                    output.writeUtf8(text)
                } else {
                    val lineEndingPolicy =
                        if (policy.pasteLineEndingPolicy == PasteLineEndingPolicy.PRESERVE) {
                            PasteLineEndingPolicy.LINE_FEED
                        } else {
                            policy.pasteLineEndingPolicy
                        }
                    writeCanonicalLineEndings(text, stripC0 = false, lineEndingPolicy)
                }
        }
    }

    private fun writeRaw(
        text: String,
        bracketedPaste: Boolean,
    ) {
        if (bracketedPaste || policy.pasteLineEndingPolicy == PasteLineEndingPolicy.PRESERVE) {
            output.writeUtf8(text)
        } else {
            writeCanonicalLineEndings(text, stripC0 = false, policy.pasteLineEndingPolicy)
        }
    }

    private fun writeStrippedC0(
        text: String,
        bracketedPaste: Boolean,
    ) {
        if (!bracketedPaste && policy.pasteLineEndingPolicy != PasteLineEndingPolicy.PRESERVE) {
            writeCanonicalLineEndings(text, stripC0 = true, policy.pasteLineEndingPolicy)
            return
        }

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

    private fun writeCanonicalLineEndings(
        text: String,
        stripC0: Boolean,
        lineEndingPolicy: PasteLineEndingPolicy,
    ) {
        var offset = 0
        while (offset < text.length) {
            val codepoint = text.codePointAt(offset)
            if (codepoint == CR || codepoint == LF) {
                writeCanonicalLineEnding(lineEndingPolicy)
                offset += Character.charCount(codepoint)
                if (codepoint == CR && offset < text.length && text.codePointAt(offset) == LF) {
                    offset += Character.charCount(LF)
                }
            } else {
                if (!stripC0 || isAllowedAfterC0Strip(codepoint)) {
                    writeUtf8Codepoint(codepoint)
                }
                offset += Character.charCount(codepoint)
            }
        }
    }

    private fun writeCanonicalLineEnding(lineEndingPolicy: PasteLineEndingPolicy) {
        when (lineEndingPolicy) {
            PasteLineEndingPolicy.PRESERVE -> error("preserve mode does not canonicalize line endings")
            PasteLineEndingPolicy.LINE_FEED -> writeUtf8Codepoint(LF)
            PasteLineEndingPolicy.CARRIAGE_RETURN -> writeUtf8Codepoint(CR)
            PasteLineEndingPolicy.CARRIAGE_RETURN_AND_LINE_FEED -> {
                writeUtf8Codepoint(CR)
                writeUtf8Codepoint(LF)
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

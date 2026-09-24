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
import io.github.ketraterm.input.policy.PasteControlPolicy
import io.github.ketraterm.input.policy.PasteLineEndingPolicy
import io.github.ketraterm.input.policy.TerminalInputPolicy
import io.github.ketraterm.protocol.host.TerminalHostOutput

internal class PasteEncoder(
    private val output: TerminalHostOutput,
    private val scratch: InputScratchBuffer,
    internal var policy: TerminalInputPolicy = TerminalInputPolicy(),
    private val bufferedOutput: BufferedHostOutput = BufferedHostOutput(output),
) {
    fun encode(
        event: TerminalPasteEvent,
        modeBits: Long,
    ) {
        val currentPolicy = policy
        val bracketed = TerminalInputState.isBracketedPasteEnabled(modeBits)
        val lineEndings = if (bracketed) PasteLineEndingPolicy.PRESERVE else currentPolicy.pasteLineEndingPolicy
        val stripC0 =
            when (currentPolicy.pasteControlPolicy) {
                PasteControlPolicy.PRESERVE -> false
                PasteControlPolicy.STRIP_C0_EXCEPT_TAB_CR_LF -> true
            }
        val transform = needsTransformation(event.text, bracketed, stripC0, lineEndings)
        val target = if (transform) bufferedOutput else output

        bufferedOutput.reset()
        try {
            if (bracketed) {
                target.writeBytes(TerminalSequences.BRACKETED_PASTE_START, 0, TerminalSequences.BRACKETED_PASTE_START.size)
            }
            if (transform) {
                writeTransformed(event.text, bracketed, stripC0, lineEndings)
            } else {
                target.writeUtf8(event.text)
            }
            if (bracketed) {
                target.writeBytes(TerminalSequences.BRACKETED_PASTE_END, 0, TerminalSequences.BRACKETED_PASTE_END.size)
            }
            bufferedOutput.flush()
        } finally {
            // A failed transport write must never leak pending bytes into the next input event.
            bufferedOutput.reset()
        }
    }

    private fun needsTransformation(
        text: String,
        bracketed: Boolean,
        stripC0: Boolean,
        lineEndings: PasteLineEndingPolicy,
    ): Boolean {
        var offset = 0
        while (offset < text.length) {
            val codepoint = text.codePointAt(offset)
            if ((stripC0 && isStrippedControl(codepoint)) ||
                (bracketed && (codepoint == ESC || codepoint == ETX || codepoint == CSI)) ||
                (lineEndings != PasteLineEndingPolicy.PRESERVE && (codepoint == CR || codepoint == LF)) ||
                codepoint in 0xd800..0xdfff
            ) {
                return true
            }
            offset += Character.charCount(codepoint)
        }
        return false
    }

    private fun writeTransformed(
        text: String,
        bracketed: Boolean,
        stripC0: Boolean,
        lineEndings: PasteLineEndingPolicy,
    ) {
        var offset = 0
        while (offset < text.length) {
            val codepoint = text.codePointAt(offset)
            offset += Character.charCount(codepoint)
            when {
                stripC0 && isStrippedControl(codepoint) -> Unit
                bracketed && codepoint == ESC -> writeCodepoint(0x241b)
                bracketed && codepoint == ETX -> writeCodepoint(0x2403)
                bracketed && codepoint == CSI -> bufferedOutput.writeAscii("\\u009b")
                lineEndings != PasteLineEndingPolicy.PRESERVE && (codepoint == CR || codepoint == LF) -> {
                    when (lineEndings) {
                        PasteLineEndingPolicy.CARRIAGE_RETURN -> writeCodepoint(CR)
                        PasteLineEndingPolicy.LINE_FEED -> writeCodepoint(LF)
                        PasteLineEndingPolicy.CARRIAGE_RETURN_AND_LINE_FEED -> {
                            writeCodepoint(CR)
                            writeCodepoint(LF)
                        }
                        PasteLineEndingPolicy.PRESERVE -> error("preserve mode does not canonicalize line endings")
                    }
                    if (codepoint == CR && offset < text.length && text[offset].code == LF) offset++
                }
                codepoint in 0xd800..0xdfff -> writeCodepoint(0xfffd)
                else -> writeCodepoint(codepoint)
            }
        }
    }

    private fun isStrippedControl(codepoint: Int): Boolean =
        codepoint in 0x00..0x1f && codepoint != TAB && codepoint != CR && codepoint != LF

    private fun writeCodepoint(codepoint: Int) {
        CsiWriter.writeUtf8Codepoint(scratch, bufferedOutput, codepoint)
    }

    private companion object {
        private const val ETX: Int = 0x03
        private const val TAB: Int = 0x09
        private const val LF: Int = 0x0a
        private const val CR: Int = 0x0d
        private const val ESC: Int = 0x1b
        private const val CSI: Int = 0x9b
    }
}

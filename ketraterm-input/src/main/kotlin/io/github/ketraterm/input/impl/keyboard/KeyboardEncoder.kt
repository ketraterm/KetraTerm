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
package io.github.ketraterm.input.impl.keyboard

import io.github.ketraterm.core.api.TerminalInputState
import io.github.ketraterm.input.event.TerminalKeyEvent
import io.github.ketraterm.input.event.TerminalKeyEventType
import io.github.ketraterm.input.impl.InputScratchBuffer
import io.github.ketraterm.input.policy.TerminalInputPolicy
import io.github.ketraterm.protocol.host.TerminalHostOutput
import io.github.ketraterm.protocol.keyboard.KittyKeyboardProgressiveFlag

/**
 * Routing facade for keyboard event encoding.
 *
 * Committed text without key identity is emitted directly while the protocol accepts
 * text. Physical-key events and enhanced text reports use the selected protocol encoder.
 *
 * @param output the target byte stream sink where generated escape sequences are written.
 * @param scratch a shared, allocation-free scratch buffer reused to format escape sequences.
 * @param policy configuration settings governing fallback behavior for ambiguous or unsupported keys.
 */
internal class KeyboardEncoder(
    private val output: TerminalHostOutput,
    private val scratch: InputScratchBuffer,
    policy: TerminalInputPolicy = TerminalInputPolicy(),
) {
    private val legacy = LegacyKeyboardEncoder(output, scratch, policy)
    private val kitty = KittyKeyboardEncoder(output, scratch, policy)

    @Volatile
    internal var policy: TerminalInputPolicy = policy
        set(value) {
            field = value
            legacy.policy = value
            kitty.policy = value
        }

    /**
     * Encodes a keyboard event into a sequence of bytes written to the output stream.
     *
     * Text-only commits bypass physical-key transformations unless Kitty report-all mode
     * requires an enhanced report. Other events use [KittyKeyboardEncoder] when progressive
     * flags are active, or [LegacyKeyboardEncoder] otherwise.
     *
     * @param event the keyboard event containing the key, modifiers, and optional codepoint.
     * @param modeBits the active terminal modes pack representing current DEC/ANSI and Kitty mode state.
     */
    fun encode(
        event: TerminalKeyEvent,
        modeBits: Long,
    ) {
        val kittyFlags = TerminalInputState.kittyKeyboardFlags(modeBits)
        if (
            event.type == TerminalKeyEventType.RELEASE &&
            (kittyFlags and KittyKeyboardProgressiveFlag.REPORT_EVENT_TYPES) == 0
        ) {
            return
        }
        if (
            event.codepoint == TerminalKeyEvent.TEXT_ONLY_CODEPOINT &&
            (kittyFlags and KittyKeyboardProgressiveFlag.REPORT_ALL_KEYS_AS_ESCAPE_CODES) == 0
        ) {
            if (event.type == TerminalKeyEventType.RELEASE) return
            val text = checkNotNull(event.associatedText)
            var index = 0
            while (index < text.length) {
                val codepoint = text.codePointAt(index)
                CsiWriter.writeUtf8Codepoint(scratch, output, codepoint)
                index += Character.charCount(codepoint)
            }
            return
        }
        if (kittyFlags > 0) {
            kitty.encode(event, kittyFlags, modeBits)
        } else {
            legacy.encode(event, modeBits)
        }
    }
}

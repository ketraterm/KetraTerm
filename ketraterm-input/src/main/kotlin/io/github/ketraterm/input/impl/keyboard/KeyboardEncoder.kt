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

/**
 * Routing facade for keyboard event encoding.
 *
 * This class determines whether a keyboard event should be processed under the
 * legacy xterm protocols or the modern Kitty keyboard protocol. The routing choice
 * is made dynamically per event by checking if the active Kitty progressive keyboard
 * flags (extracted from [modeBits]) are non-zero.
 *
 * This facade decouples the high-level input loop from the complex logic of the
 * individual protocol encoders, ensuring strict separation of concerns (SRP).
 *
 * @param output the target byte stream sink where generated escape sequences are written.
 * @param scratch a shared, allocation-free scratch buffer reused to format escape sequences.
 * @param policy configuration settings governing fallback behavior for ambiguous or unsupported keys.
 */
internal class KeyboardEncoder(
    output: TerminalHostOutput,
    scratch: InputScratchBuffer,
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
     * Delegates to [KittyKeyboardEncoder] if any Kitty keyboard protocol progressive mode
     * flags are active in the given [modeBits]. Otherwise, falls back to [LegacyKeyboardEncoder].
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
            (kittyFlags and io.github.ketraterm.protocol.keyboard.KittyKeyboardProgressiveFlag.REPORT_EVENT_TYPES) == 0
        ) {
            return
        }
        if (kittyFlags > 0) {
            kitty.encode(event, kittyFlags, modeBits)
        } else {
            legacy.encode(event, modeBits)
        }
    }
}

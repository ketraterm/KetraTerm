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
package com.gagik.terminal.input.impl

import com.gagik.core.api.TerminalInputState
import com.gagik.terminal.input.api.TerminalInputEncoder
import com.gagik.terminal.input.event.TerminalFocusEvent
import com.gagik.terminal.input.event.TerminalKeyEvent
import com.gagik.terminal.input.event.TerminalMouseEvent
import com.gagik.terminal.input.event.TerminalPasteEvent
import com.gagik.terminal.input.impl.keyboard.KeyboardEncoder
import com.gagik.terminal.input.policy.TerminalInputPolicy
import com.gagik.terminal.protocol.host.TerminalHostOutput

/**
 * Default terminal input encoder facade.
 *
 * The facade reads a coherent packed mode snapshot once per event and passes
 * that stable value to the specialized encoder for the event family.
 *
 * Not thread-safe. Calls must be serialized by the terminal event loop.
 *
 * This is intentional: terminal-to-host byte ordering must be deterministic,
 * and the encoder reuses one scratch buffer to avoid per-event allocation.
 * UI adapters should enqueue input events onto the same terminal actor that
 * writes parser/core responses to [TerminalHostOutput], so input reports and
 * terminal replies keep one coherent host-bound order.
 *
 * @param inputState read-only core mode state used for input decisions.
 * @param output host-bound byte sink.
 * @param policy policy for ambiguous or unsupported keyboard encodings.
 */
class DefaultTerminalInputEncoder(
    private val inputState: TerminalInputState,
    output: TerminalHostOutput,
    policy: TerminalInputPolicy = TerminalInputPolicy(),
) : TerminalInputEncoder {
    private val scratch = InputScratchBuffer()
    private val keyboard = KeyboardEncoder(output, scratch, policy)
    private val paste = PasteEncoder(output, scratch, policy)
    private val focus = FocusEncoder(output)
    private val mouse = MouseEncoder(output, scratch, policy)

    /**
     * Encodes one keyboard event using one packed mode read.
     *
     * @param event non-printable key or printable Unicode scalar event.
     */
    override fun encodeKey(event: TerminalKeyEvent) {
        val modeBits = inputState.getInputModeBits()
        keyboard.encode(event, modeBits)
    }

    /**
     * Encodes one paste event using one packed mode read.
     *
     * @param event pasted text event.
     */
    override fun encodePaste(event: TerminalPasteEvent) {
        val modeBits = inputState.getInputModeBits()
        paste.encode(event, modeBits)
    }

    /**
     * Encodes one focus transition event using one packed mode read.
     *
     * @param event terminal focus transition.
     */
    override fun encodeFocus(event: TerminalFocusEvent) {
        val modeBits = inputState.getInputModeBits()
        focus.encode(event, modeBits)
    }

    /**
     * Encodes one mouse event using one packed mode read.
     *
     * @param event zero-based cell-coordinate mouse event.
     */
    override fun encodeMouse(event: TerminalMouseEvent) {
        val modeBits = inputState.getInputModeBits()
        mouse.encode(event, modeBits)
    }
}

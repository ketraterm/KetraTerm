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
package io.github.ketraterm.input.api

/**
 * Encodes UI-level terminal input events into host-bound bytes.
 *
 * Implementations are responsible for reading the current input-facing mode
 * state at the appropriate event boundary and writing the resulting bytes to a
 * host output sink.
 */
interface TerminalInputEncoder {
    /**
     * Encodes one keyboard event.
     *
     * @param event non-printable key or printable Unicode scalar event.
     */
    fun encodeKey(event: io.github.ketraterm.input.event.TerminalKeyEvent)

    /**
     * Encodes one paste event.
     *
     * @param event pasted text event.
     */
    fun encodePaste(event: io.github.ketraterm.input.event.TerminalPasteEvent)

    /**
     * Encodes one logical text replacement around the active cursor.
     *
     * The default implementation preserves compatibility for lightweight host
     * adapters. Session-backed implementations should serialize the entire
     * operation as one outbound unit, and production encoders may batch the
     * repeated deletion sequences.
     *
     * @param event deletion counts and replacement text.
     */
    fun encodeTextReplacement(event: io.github.ketraterm.input.event.TerminalTextReplacementEvent) {
        if (event.deleteAfterCursorCount > 0) {
            val deleteEvent =
                io.github.ketraterm.input.event.TerminalKeyEvent.key(
                    io.github.ketraterm.input.event.TerminalKey.DELETE,
                )
            repeat(event.deleteAfterCursorCount) {
                encodeKey(deleteEvent)
            }
        }
        if (event.deleteBeforeCursorCount > 0) {
            val backspaceEvent =
                io.github.ketraterm.input.event.TerminalKeyEvent.key(
                    io.github.ketraterm.input.event.TerminalKey.BACKSPACE,
                )
            repeat(event.deleteBeforeCursorCount) {
                encodeKey(backspaceEvent)
            }
        }
        if (event.replacementText.isNotEmpty()) {
            encodePaste(
                io.github.ketraterm.input.event
                    .TerminalPasteEvent(event.replacementText),
            )
        }
    }

    /**
     * Encodes one focus transition event.
     *
     * @param event terminal focus transition.
     */
    fun encodeFocus(event: io.github.ketraterm.input.event.TerminalFocusEvent)

    /**
     * Encodes one mouse event.
     *
     * @param event zero-based cell-coordinate mouse event.
     */
    fun encodeMouse(event: io.github.ketraterm.input.event.TerminalMouseEvent)

    /**
     * Updates the input policy dynamically.
     *
     * @param policy new input policy.
     */
    fun setInputPolicy(policy: io.github.ketraterm.input.policy.TerminalInputPolicy) {}
}

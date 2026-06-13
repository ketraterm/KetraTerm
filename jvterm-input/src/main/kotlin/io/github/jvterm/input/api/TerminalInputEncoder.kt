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
package io.github.jvterm.input.api

import io.github.jvterm.input.event.TerminalFocusEvent
import io.github.jvterm.input.event.TerminalKeyEvent
import io.github.jvterm.input.event.TerminalMouseEvent
import io.github.jvterm.input.event.TerminalPasteEvent

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
    fun encodeKey(event: TerminalKeyEvent)

    /**
     * Encodes one paste event.
     *
     * @param event pasted text event.
     */
    fun encodePaste(event: TerminalPasteEvent)

    /**
     * Encodes one focus transition event.
     *
     * @param event terminal focus transition.
     */
    fun encodeFocus(event: TerminalFocusEvent)

    /**
     * Encodes one mouse event.
     *
     * @param event zero-based cell-coordinate mouse event.
     */
    fun encodeMouse(event: TerminalMouseEvent)
}

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
import io.github.jvterm.input.event.TerminalFocusEvent
import io.github.jvterm.protocol.host.TerminalHostOutput

internal class FocusEncoder(
    private val output: TerminalHostOutput,
) {
    fun encode(
        event: TerminalFocusEvent,
        modeBits: Long,
    ) {
        if (!TerminalInputState.isFocusReportingEnabled(modeBits)) {
            return
        }

        val sequence =
            if (event.focused) {
                TerminalSequences.FOCUS_IN
            } else {
                TerminalSequences.FOCUS_OUT
            }

        output.writeBytes(sequence, 0, sequence.size)
    }
}

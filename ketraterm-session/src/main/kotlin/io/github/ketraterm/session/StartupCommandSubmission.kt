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
package io.github.ketraterm.session

import io.github.ketraterm.input.api.TerminalInputEncoder
import io.github.ketraterm.input.event.TerminalKey
import io.github.ketraterm.input.event.TerminalKeyEvent
import io.github.ketraterm.input.event.TerminalPasteEvent
import io.github.ketraterm.protocol.ShellIntegrationMarker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** All methods run under the session outbound lock; marker observation also holds its mutation lock. */
internal class StartupCommandSubmission(
    command: TerminalStartupCommand,
) {
    private var pendingText: String? = command.text
    private var promptStarted = false
    private var promptReady = false
    private val mutableStatus = MutableStateFlow(TerminalStartupCommandStatus.WAITING)
    val status = mutableStatus.asStateFlow()

    fun observe(
        marker: ShellIntegrationMarker,
        primary: Boolean,
    ) {
        if (pendingText == null) return
        if (!primary) {
            invalidatePrompt()
            return
        }
        when (marker) {
            ShellIntegrationMarker.PROMPT_START -> {
                promptStarted = true
                promptReady = false
            }
            ShellIntegrationMarker.PROMPT_END -> promptReady = promptStarted
            ShellIntegrationMarker.COMMAND_START, ShellIntegrationMarker.COMMAND_FINISHED -> {
                promptStarted = false
                promptReady = false
            }
        }
    }

    fun invalidatePrompt() {
        promptStarted = false
        promptReady = false
    }

    fun submitIfReady(encoder: TerminalInputEncoder) {
        val text = pendingText ?: return
        if (!promptReady) return
        pendingText = null
        try {
            encoder.encodePaste(TerminalPasteEvent(text))
            encoder.encodeKey(TerminalKeyEvent(key = TerminalKey.ENTER))
            mutableStatus.value = TerminalStartupCommandStatus.SUBMITTED
        } catch (exception: Exception) {
            mutableStatus.value = TerminalStartupCommandStatus.FAILED
            throw exception
        }
    }

    fun cancel(reason: TerminalStartupCommandStatus) {
        if (pendingText == null) return
        pendingText = null
        mutableStatus.value = reason
    }
}

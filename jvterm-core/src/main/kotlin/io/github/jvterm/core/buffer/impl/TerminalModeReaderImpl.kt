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
package io.github.jvterm.core.buffer.impl

import io.github.jvterm.core.api.TerminalModeReader
import io.github.jvterm.core.api.TerminalModeSnapshot
import io.github.jvterm.core.state.TerminalState

internal class TerminalModeReaderImpl(
    private val state: TerminalState,
) : TerminalModeReader {
    override fun getModeBitsSnapshot(): Long = state.modes.getModeBitsSnapshot()

    override fun getInputModeBits(): Long = state.modes.getInputModeBits()

    override fun getModeSnapshot(): TerminalModeSnapshot = state.modes.getModeSnapshot()
}

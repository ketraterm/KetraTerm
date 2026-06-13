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
package io.github.jvterm.core

import io.github.jvterm.core.api.TerminalBuffer
import io.github.jvterm.core.buffer.DefaultTerminalBuffer

/**
 * Factory for creating terminal buffer instances behind the public core API.
 */
object TerminalBuffers {
    /**
     * Creates a terminal buffer with the requested visible dimensions and
     * scrollback capacity.
     */
    @JvmStatic
    @JvmOverloads
    fun create(
        width: Int,
        height: Int,
        maxHistory: Int = 1000,
    ): TerminalBuffer = DefaultTerminalBuffer(width, height, maxHistory)
}

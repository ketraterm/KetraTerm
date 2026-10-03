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
package io.github.ketraterm.core

import io.github.ketraterm.core.api.TerminalBuffer
import io.github.ketraterm.core.api.TerminalRenderBuffer
import io.github.ketraterm.core.buffer.DefaultTerminalBuffer

/**
 * Factory for creating terminal buffer instances behind the public core API.
 */
public object TerminalBuffers {
    /**
     * Creates a terminal buffer with the requested visible dimensions and
     * scrollback capacity.
     *
     * The returned [TerminalRenderBuffer] exposes both core state and render frames.
     * Direct embedders may copy frames while externally serializing grid
     * reads, borrowed views, and mutations as documented by [TerminalBuffer].
     * Atomic mode snapshots retain their narrower method-level concurrency
     * guarantees; the factory does not install a synchronization boundary.
     *
     * @param width Visible width in cells. Must be > 0.
     * @param height Visible height in rows. Must be > 0.
     * @param maxHistory Maximum history lines; non-negative and small enough that adding
     * [height] does not exceed [Int.MAX_VALUE]. Zero disables retained scrollback.
     * @return A new [TerminalBuffer] instance initialized with the given dimensions.
     * @throws IllegalArgumentException if either visible dimension is non-positive,
     * [maxHistory] is negative, or the retained line capacity would overflow [Int].
     */
    @JvmStatic
    @JvmOverloads
    public fun create(
        width: Int,
        height: Int,
        maxHistory: Int = 1000,
    ): TerminalRenderBuffer {
        require(width > 0) { "width must be positive, was $width" }
        require(height > 0) { "height must be positive, was $height" }
        require(maxHistory >= 0) { "maxHistory must be non-negative, was $maxHistory" }
        require(maxHistory <= Int.MAX_VALUE - height) {
            "maxHistory + height exceeds Int.MAX_VALUE: maxHistory=$maxHistory, height=$height"
        }
        return DefaultTerminalBuffer(width, height, maxHistory)
    }
}

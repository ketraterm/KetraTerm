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
package io.github.jvterm.core.api

import io.github.jvterm.core.model.Attributes

/**
 * Allocating inspection contract for the terminal buffer.
 *
 * Intended for tests and debugging only. Every method here allocates — do
 * not use on a hot rendering path. Production code should use [TerminalReader].
 */
interface TerminalInspector {
    /**
     * Returns the attributes at a screen position as an unpacked [Attributes] object.
     *
     * @param col Column index (0-based).
     * @param row Row index (0-based).
     * @return Unpacked attributes, or `null` if the position is out of bounds.
     */
    fun getAttrAt(
        col: Int,
        row: Int,
    ): Attributes?

    /**
     * Returns the content of a visible row as a string, trimming trailing blank
     * cells while preserving intentional space characters.
     *
     * @param row Visible row index (0-based).
     * @return The row text, or an empty string if the row is blank or out of bounds.
     */
    fun getLineAsString(row: Int): String

    /**
     * Returns the visible screen as a newline-joined string, top to bottom.
     */
    fun getScreenAsString(): String

    /**
     * Returns scrollback history followed by the visible screen as a
     * newline-joined string, oldest line first.
     */
    fun getAllAsString(): String
}

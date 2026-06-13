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
package io.github.jvterm.render.api

/**
 * Receives primitive cursor overlay state from a short-lived render frame.
 *
 * This sink lets render caches copy cursor data without requiring a
 * [TerminalRenderCursor] object allocation on every published frame.
 */
fun interface TerminalRenderCursorSink {
    /**
     * Copies one cursor snapshot.
     *
     * @param column zero-based visual cursor column.
     * @param row zero-based visual cursor row.
     * @param visible whether the cursor should be rendered.
     * @param blinking whether cursor blinking mode is enabled.
     * @param shape renderer-facing cursor shape.
     * @param generation generation that changes when cursor presentation changes.
     */
    fun onCursor(
        column: Int,
        row: Int,
        visible: Boolean,
        blinking: Boolean,
        shape: TerminalRenderCursorShape,
        generation: Long,
    )
}

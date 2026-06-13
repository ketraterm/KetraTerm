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
 * Stable public cursor overlay model for renderers.
 *
 * The terminal exposes whether blinking mode is enabled, but not the current
 * blink phase. UI modules own blink timers and repaint cadence.
 * Allocation-sensitive frame consumers should prefer
 * [TerminalRenderFrame.copyCursor] and copy primitive cursor fields directly.
 *
 * @property column zero-based visual cursor column.
 * @property row zero-based visual cursor row.
 * @property visible whether the cursor should be rendered.
 * @property blinking whether cursor blinking mode is enabled.
 * @property shape renderer-facing cursor shape.
 * @property generation generation that changes when position, visibility,
 * blinking mode, or shape changes.
 */
data class TerminalRenderCursor(
    val column: Int,
    val row: Int,
    val visible: Boolean,
    val blinking: Boolean,
    val shape: TerminalRenderCursorShape,
    val generation: Long,
)

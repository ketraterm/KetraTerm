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
package io.github.jvterm.ui.swing.input

import io.github.jvterm.input.event.TerminalMouseEvent
import io.github.jvterm.protocol.MouseTrackingMode
import io.github.jvterm.render.cache.TerminalRenderCache
import io.github.jvterm.ui.swing.settings.SwingMetrics
import io.github.jvterm.ui.swing.settings.SwingSettings
import java.awt.event.MouseEvent

/**
 * Narrow adapter for Swing mouse routing.
 */
internal interface SwingTerminalMouseHost {
    val settings: SwingSettings
    val metrics: SwingMetrics
    val renderCache: TerminalRenderCache

    fun mouseTrackingMode(): MouseTrackingMode

    fun encodeMouse(event: TerminalMouseEvent)

    fun cellAt(
        x: Int,
        y: Int,
        cache: TerminalRenderCache,
    ): Long

    fun terminalPixelYAt(
        y: Int,
        cache: TerminalRenderCache,
    ): Int

    fun visibleGridRows(): Int

    fun scrollViewportByPreciseRows(deltaRows: Double): Boolean

    fun finishViewportScroll()

    fun pasteClipboardText(): Boolean

    fun handlePromptMarkerMousePressed(event: MouseEvent): Boolean = false

    fun handlePromptMarkerMouseMoved(event: MouseEvent): Boolean = false

    fun handlePromptMarkerMouseExited() = Unit

    fun handleHyperlinkMousePressed(event: MouseEvent): Boolean

    fun handleHyperlinkMouseMoved(event: MouseEvent)

    fun handleHyperlinkMouseExited()

    fun clearHyperlinkHover()

    fun handleSelectionMousePressed(event: MouseEvent)

    fun handleSelectionMouseReleased(event: MouseEvent)

    fun handleSelectionMouseDragged(event: MouseEvent)
}

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
package io.github.ketraterm.ui.swing.input

import io.github.ketraterm.input.api.TerminalInputEncoder
import io.github.ketraterm.input.event.TerminalMouseEvent
import io.github.ketraterm.protocol.MouseTrackingMode
import io.github.ketraterm.render.cache.TerminalRenderCache
import io.github.ketraterm.ui.swing.settings.SwingMetrics
import io.github.ketraterm.ui.swing.settings.SwingSettings
import java.awt.event.MouseEvent

/**
 * Narrow adapter for Swing mouse routing.
 */
internal interface SwingTerminalMouseHost {
    val settings: SwingSettings
    val metrics: SwingMetrics
    val renderCache: TerminalRenderCache
    val session: TerminalInputEncoder?

    fun mouseTrackingMode(): MouseTrackingMode

    fun encodeMouse(event: TerminalMouseEvent)

    fun pasteClipboardText()

    fun cellAt(
        x: Int,
        y: Int,
        cache: TerminalRenderCache,
    ): Long

    /** Whether the pointer is over presentation space that has no terminal mouse coordinates. */
    fun isPromptDividerAt(
        x: Int,
        y: Int,
    ): Boolean = false

    fun terminalPixelYAt(
        y: Int,
        cache: TerminalRenderCache,
    ): Int

    fun visibleGridRows(): Int

    fun scrollViewportByPreciseRows(deltaRows: Double): Boolean

    fun finishViewportScroll()

    fun requestFocusInWindow(): Boolean

    fun handlePromptMarkerMousePressed(event: MouseEvent): Boolean = false

    fun handlePromptMarkerMouseMoved(event: MouseEvent): Boolean = false

    fun handlePromptMarkerMouseExited() = Unit

    fun handleContextMenuMouseEvent(
        event: MouseEvent,
        forcedByShift: Boolean,
    ): Boolean

    fun handleHyperlinkMousePressed(event: MouseEvent): Boolean

    fun handleHyperlinkMouseReleased(event: MouseEvent): Boolean

    fun handleHyperlinkMouseDragged()

    fun handleHyperlinkMouseMoved(
        event: MouseEvent,
        enabled: Boolean,
    )

    fun handleHyperlinkMouseExited()

    fun clearHyperlinkHover()

    fun handleSelectionMousePressed(event: MouseEvent)

    fun handleSelectionMouseReleased(event: MouseEvent)

    fun handleSelectionMouseDragged(event: MouseEvent)
}

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

import io.github.jvterm.input.event.TerminalModifiers
import io.github.jvterm.input.event.TerminalMouseButton
import io.github.jvterm.input.event.TerminalMouseEvent
import io.github.jvterm.input.event.TerminalMouseEventType
import io.github.jvterm.protocol.MouseTrackingMode
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.awt.event.MouseWheelEvent
import java.awt.event.MouseWheelListener
import javax.swing.SwingUtilities

/**
 * Swing mouse routing for terminal mouse protocol, links, selection, and wheel scrolling.
 */
internal class SwingTerminalMouseController(
    private val host: SwingTerminalMouseHost,
) {
    val wheelListener =
        MouseWheelListener { event ->
            handleMouseWheel(event)
        }

    val mouseListener =
        object : MouseAdapter() {
            override fun mousePressed(event: MouseEvent) {
                if (handleMouseTracking(event, TerminalMouseEventType.PRESS)) return
                if (host.handleHyperlinkMousePressed(event)) return
                if (SwingUtilities.isMiddleMouseButton(event)) {
                    if (host.settings.pasteOnMiddleClick) {
                        host.pasteClipboardText()
                        event.consume()
                        return
                    }
                }
                host.handleSelectionMousePressed(event)
            }

            override fun mouseReleased(event: MouseEvent) {
                if (handleMouseTracking(event, TerminalMouseEventType.RELEASE)) return
                host.handleSelectionMouseReleased(event)
            }

            override fun mouseExited(event: MouseEvent) {
                host.handleHyperlinkMouseExited()
            }
        }

    val mouseMotionListener =
        object : MouseMotionAdapter() {
            override fun mouseDragged(event: MouseEvent) {
                if (handleMouseTracking(event, TerminalMouseEventType.MOTION)) return
                host.handleSelectionMouseDragged(event)
            }

            override fun mouseMoved(event: MouseEvent) {
                if (handleMouseTracking(event, TerminalMouseEventType.MOTION)) {
                    host.clearHyperlinkHover()
                    return
                }
                host.handleHyperlinkMouseMoved(event)
            }
        }

    private fun handleMouseWheel(event: MouseWheelEvent) {
        if (handleMouseTracking(event, TerminalMouseEventType.WHEEL)) return
        val historySize = host.renderCache.historySize
        if (historySize == 0) return

        val delta = wheelScrollLines(event)
        if (delta == 0.0) return

        host.scrollViewportBy(delta, historySize)
        event.consume()
    }

    private fun isMouseTrackingIntercepted(event: MouseEvent): Boolean {
        if (event.isShiftDown) return false
        val trackingMode =
            host.session
                ?.terminal
                ?.getModeSnapshot()
                ?.mouseTrackingMode ?: MouseTrackingMode.OFF
        return trackingMode != MouseTrackingMode.OFF
    }

    private fun handleMouseTracking(
        event: MouseEvent,
        type: TerminalMouseEventType,
    ): Boolean {
        val boundSession = host.session ?: return false
        if (!isMouseTrackingIntercepted(event)) return false

        val cell = host.cellAt(event.x, event.y, host.renderCache)
        val column = unpackCellColumn(cell)
        val row = unpackCellRow(cell)

        val button =
            if (event is MouseWheelEvent) {
                if (event.wheelRotation < 0) TerminalMouseButton.WHEEL_UP else TerminalMouseButton.WHEEL_DOWN
            } else {
                when {
                    SwingUtilities.isLeftMouseButton(event) -> TerminalMouseButton.LEFT
                    SwingUtilities.isMiddleMouseButton(event) -> TerminalMouseButton.MIDDLE
                    SwingUtilities.isRightMouseButton(event) -> TerminalMouseButton.RIGHT
                    else -> TerminalMouseButton.NONE
                }
            }

        var mods = TerminalModifiers.NONE
        if (event.isShiftDown) mods = mods or TerminalModifiers.SHIFT
        if (event.isAltDown) mods = mods or TerminalModifiers.ALT
        if (event.isControlDown) mods = mods or TerminalModifiers.CTRL
        if (event.isMetaDown) mods = mods or TerminalModifiers.META

        val padding = host.settings.padding
        val gridWidth = host.renderCache.columns * host.metrics.cellWidth
        val gridHeight = host.renderCache.rows * host.metrics.cellHeight
        val pixelX = (event.x - padding.left).coerceIn(0, gridWidth - 1)
        val pixelY = (event.y - padding.top - host.contentYOffset(host.renderCache)).toInt().coerceIn(0, gridHeight - 1)

        val mouseEvent =
            TerminalMouseEvent(
                column = column,
                row = row,
                button = button,
                type = type,
                modifiers = mods,
                pixelX = pixelX,
                pixelY = pixelY,
            )
        boundSession.encodeMouse(mouseEvent)
        event.consume()
        return true
    }

    private fun wheelScrollLines(event: MouseWheelEvent): Double =
        when (event.scrollType) {
            MouseWheelEvent.WHEEL_UNIT_SCROLL -> -event.preciseWheelRotation * event.scrollAmount
            MouseWheelEvent.WHEEL_BLOCK_SCROLL -> -event.preciseWheelRotation * host.visibleGridRows()
            else -> -event.preciseWheelRotation
        }

    private companion object {
        private fun unpackCellColumn(packed: Long): Int = (packed ushr 32).toInt()

        private fun unpackCellRow(packed: Long): Int = packed.toInt()
    }
}

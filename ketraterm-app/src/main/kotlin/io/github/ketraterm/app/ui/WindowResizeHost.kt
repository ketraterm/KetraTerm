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
package io.github.ketraterm.app.ui

import io.github.ketraterm.render.api.TerminalRenderBufferKind
import io.github.ketraterm.ui.swing.api.SwingTerminal
import java.awt.Frame
import java.awt.Rectangle
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowStateListener
import javax.swing.JFrame

/** EDT-only native window boundary; absent geometry means the window is ineligible for application resizing. */
internal interface WindowResizeHost {
    fun readGeometry(terminal: SwingTerminal): WindowResizeGeometry?

    fun resize(bounds: Rectangle)

    fun observeGeometryChanges(refresh: () -> Unit): AutoCloseable
}

internal class SwingWindowResizeHost(
    private val frame: JFrame,
) : WindowResizeHost {
    override fun readGeometry(terminal: SwingTerminal): WindowResizeGeometry? {
        frame.validate()
        if (!frame.isShowing || !terminal.isShowing || frame.extendedState != Frame.NORMAL) return null
        val configuration = frame.graphicsConfiguration ?: return null
        if (configuration.device.fullScreenWindow != null) return null
        if (terminal.width <= 0 || terminal.height <= 0) return null
        val bounds = configuration.bounds
        val insets = frame.toolkit.getScreenInsets(configuration)
        val primary = terminal.preferredGridSize(0, 0)
        val alternate = terminal.preferredGridSize(0, 0, TerminalRenderBufferKind.ALTERNATE)
        val cell = terminal.preferredGridSize(1, 1)
        return WindowResizeGeometry(
            cellWidth = cell.width - primary.width,
            cellHeight = cell.height - primary.height,
            windowExtraWidth = frame.width - terminal.width,
            windowExtraHeight = frame.height - terminal.height,
            primaryInsetWidth = primary.width,
            primaryInsetHeight = primary.height,
            alternateInsetWidth = alternate.width,
            alternateInsetHeight = alternate.height,
            minimumWidth = frame.minimumSize.width,
            minimumHeight = frame.minimumSize.height,
            availableWidth = bounds.width - insets.left - insets.right,
            availableHeight = bounds.height - insets.top - insets.bottom,
            windowX = frame.x,
            windowY = frame.y,
            availableX = bounds.x + insets.left,
            availableY = bounds.y + insets.top,
        )
    }

    override fun resize(bounds: Rectangle) {
        frame.bounds = bounds
        frame.validate()
    }

    override fun observeGeometryChanges(refresh: () -> Unit): AutoCloseable {
        val componentListener =
            object : ComponentAdapter() {
                override fun componentResized(event: ComponentEvent) = refresh()

                override fun componentMoved(event: ComponentEvent) = refresh()

                override fun componentShown(event: ComponentEvent) = refresh()

                override fun componentHidden(event: ComponentEvent) = refresh()
            }
        val windowStateListener = WindowStateListener { refresh() }
        frame.addComponentListener(componentListener)
        frame.addWindowStateListener(windowStateListener)
        return AutoCloseable {
            frame.removeComponentListener(componentListener)
            frame.removeWindowStateListener(windowStateListener)
        }
    }
}

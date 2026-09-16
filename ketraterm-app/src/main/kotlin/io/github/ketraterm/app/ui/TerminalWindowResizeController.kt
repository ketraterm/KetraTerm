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
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.ui.swing.api.SwingTerminal
import java.awt.Dimension
import java.awt.Frame
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowStateListener
import javax.swing.JFrame
import javax.swing.SwingUtilities

/** Publishes EDT geometry for nonblocking application resize decisions. */
internal class TerminalWindowResizeController(
    private val frame: JFrame,
) : AutoCloseable {
    @Volatile private var snapshot: Snapshot? = null
    private var closed = false
    private var eligibleTerminal: SwingTerminal? = null
    private var eligibleSession: TerminalSession? = null
    private val componentListener =
        object : ComponentAdapter() {
            override fun componentResized(event: ComponentEvent) = refresh()

            override fun componentMoved(event: ComponentEvent) = refresh()

            override fun componentShown(event: ComponentEvent) = refresh()

            override fun componentHidden(event: ComponentEvent) = refresh()
        }
    private val windowStateListener = WindowStateListener { refresh() }

    init {
        frame.addComponentListener(componentListener)
        frame.addWindowStateListener(windowStateListener)
    }

    /** Publishes the product's single eligible pane; all calls must run on the EDT. */
    fun setTarget(
        session: TerminalSession,
        terminal: SwingTerminal,
    ) {
        clearTarget()
        if (closed) return
        eligibleSession = session
        eligibleTerminal = terminal
        terminal.addComponentListener(componentListener)
        refresh()
    }

    fun clearTarget() {
        check(SwingUtilities.isEventDispatchThread())
        snapshot = null
        eligibleTerminal?.removeComponentListener(componentListener)
        eligibleTerminal = null
        eligibleSession = null
    }

    private fun refresh() {
        check(SwingUtilities.isEventDispatchThread())
        if (closed) return
        frame.validate()
        snapshot = null
        val terminal = eligibleTerminal ?: return
        val session = eligibleSession ?: return
        if (!frame.isShowing || !terminal.isShowing || frame.extendedState != Frame.NORMAL) return
        val configuration = frame.graphicsConfiguration ?: return
        if (configuration.device.fullScreenWindow != null) return
        if (terminal.width <= 0 || terminal.height <= 0) return
        val bounds = configuration.bounds
        val insets = frame.toolkit.getScreenInsets(configuration)
        val primary = terminal.preferredGridSize(0, 0)
        val alternate = terminal.preferredGridSize(0, 0, TerminalRenderBufferKind.ALTERNATE)
        val cell = terminal.preferredGridSize(1, 1)
        snapshot =
            Snapshot(
                session,
                terminal,
                WindowResizeGeometry(
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
                    availableWidth = bounds.x + bounds.width - insets.right - frame.x,
                    availableHeight = bounds.y + bounds.height - insets.bottom - frame.y,
                    windowOriginFits = frame.x >= bounds.x + insets.left && frame.y >= bounds.y + insets.top,
                ),
            )
    }

    /** Called under the session mutation lock; no Swing access on this path. */
    fun request(
        session: TerminalSession,
        rows: Int,
        columns: Int,
    ): Boolean {
        val current = snapshot ?: return false
        if (current.session !== session) return false
        var alternate = false
        session.readRenderFrame { alternate = it.activeBuffer == TerminalRenderBufferKind.ALTERNATE }
        if (current.geometry.targetSize(columns, rows, alternate) == null) return false
        SwingUtilities.invokeLater {
            if (closed || session.isClosed) return@invokeLater
            // A user layout change after acceptance takes precedence over the queued request.
            refresh()
            val latest = snapshot
            val target = latest?.takeIf { it.session === session }?.geometry?.targetSize(columns, rows, alternate)
            if (target != null) {
                frame.size = target
                frame.validate()
            } else if (!session.isClosed) {
                val visible = current.terminal.visibleGridSize()
                session.resize(visible.width, visible.height)
            }
        }
        return true
    }

    override fun close() {
        closed = true
        clearTarget()
        frame.removeComponentListener(componentListener)
        frame.removeWindowStateListener(windowStateListener)
    }

    private data class Snapshot(
        val session: TerminalSession,
        val terminal: SwingTerminal,
        val geometry: WindowResizeGeometry,
    )
}

/** Immutable geometry only; rejects overflow and requests outside available desktop space. */
internal data class WindowResizeGeometry(
    val cellWidth: Int,
    val cellHeight: Int,
    val windowExtraWidth: Int,
    val windowExtraHeight: Int,
    val primaryInsetWidth: Int,
    val primaryInsetHeight: Int,
    val alternateInsetWidth: Int,
    val alternateInsetHeight: Int,
    val minimumWidth: Int,
    val minimumHeight: Int,
    val availableWidth: Int,
    val availableHeight: Int,
    val windowOriginFits: Boolean,
) {
    fun targetSize(
        columns: Int,
        rows: Int,
        alternate: Boolean,
    ): Dimension? {
        if (!windowOriginFits || columns <= 0 || rows <= 0 || cellWidth <= 0 || cellHeight <= 0) return null
        val width = columns.toLong() * cellWidth + windowExtraWidth + if (alternate) alternateInsetWidth else primaryInsetWidth
        val height = rows.toLong() * cellHeight + windowExtraHeight + if (alternate) alternateInsetHeight else primaryInsetHeight
        if (width !in minimumWidth.toLong()..availableWidth.toLong() ||
            height !in minimumHeight.toLong()..availableHeight.toLong()
        ) {
            return null
        }
        return Dimension(width.toInt(), height.toInt())
    }
}

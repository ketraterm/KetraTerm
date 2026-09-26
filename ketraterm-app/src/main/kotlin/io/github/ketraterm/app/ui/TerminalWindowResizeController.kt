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
import java.awt.Rectangle
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.JFrame
import javax.swing.SwingUtilities

/** Publishes EDT geometry for nonblocking application resize decisions. */
internal class TerminalWindowResizeController(
    private val window: WindowResizeHost,
    private val dispatch: (() -> Unit) -> Unit = { SwingUtilities.invokeLater(it) },
) : AutoCloseable {
    constructor(frame: JFrame) : this(SwingWindowResizeHost(frame))

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
    private val windowObservation = window.observeGeometryChanges(::refresh)

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
        val terminal = eligibleTerminal ?: return
        val session = eligibleSession ?: return
        // Publish one complete replacement: requests may read the previous valid geometry during refresh.
        snapshot = window.readGeometry(terminal)?.let { Snapshot(session, terminal, it) }
    }

    /**
     * Schedules an eligible window resize without Swing access on the calling thread.
     * [preserveGrid] keeps a logical column switch intact if the queued window update is cancelled.
     */
    fun request(
        session: TerminalSession,
        rows: Int,
        columns: Int,
        preserveGrid: Boolean = false,
    ): Boolean {
        val current = snapshot ?: return false
        if (current.session !== session) return false
        var alternate = false
        session.readRenderFrame { alternate = it.activeBuffer == TerminalRenderBufferKind.ALTERNATE }
        if (current.geometry.targetBounds(columns, rows, alternate) == null) return false
        dispatch {
            if (closed || session.isClosed) return@dispatch
            // A user layout change after acceptance takes precedence over the queued request.
            refresh()
            val latest = snapshot
            val target = latest?.takeIf { it.session === session }?.geometry?.targetBounds(columns, rows, alternate)
            if (target != null) {
                window.resize(target)
            } else if (!preserveGrid && !session.isClosed) {
                val visible = current.terminal.visibleGridSize()
                session.resize(visible.width, visible.height)
            }
        }
        return true
    }

    override fun close() {
        check(SwingUtilities.isEventDispatchThread())
        if (closed) return
        closed = true
        clearTarget()
        windowObservation.close()
    }

    private data class Snapshot(
        val session: TerminalSession,
        val terminal: SwingTerminal,
        val geometry: WindowResizeGeometry,
    )
}

/** Fits an exact grid into the monitor work area, preserving window position where possible. */
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
    val windowX: Int,
    val windowY: Int,
    val availableX: Int,
    val availableY: Int,
) {
    fun targetBounds(
        columns: Int,
        rows: Int,
        alternate: Boolean,
    ): Rectangle? {
        if (columns <= 0 || rows <= 0 || cellWidth <= 0 || cellHeight <= 0) return null
        val width = columns.toLong() * cellWidth + windowExtraWidth + if (alternate) alternateInsetWidth else primaryInsetWidth
        val height = rows.toLong() * cellHeight + windowExtraHeight + if (alternate) alternateInsetHeight else primaryInsetHeight
        if (width !in minimumWidth.coerceAtLeast(1).toLong()..availableWidth.toLong() ||
            height !in minimumHeight.coerceAtLeast(1).toLong()..availableHeight.toLong()
        ) {
            return null
        }
        val x = windowX.toLong().coerceIn(availableX.toLong(), availableX.toLong() + availableWidth - width)
        val y = windowY.toLong().coerceIn(availableY.toLong(), availableY.toLong() + availableHeight - height)
        return Rectangle(x.toInt(), y.toInt(), width.toInt(), height.toInt())
    }
}

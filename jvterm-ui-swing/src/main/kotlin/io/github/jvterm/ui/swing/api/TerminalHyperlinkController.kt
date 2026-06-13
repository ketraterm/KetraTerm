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
package io.github.jvterm.ui.swing.api

import io.github.jvterm.render.cache.TerminalRenderCache
import io.github.jvterm.session.TerminalSession
import java.awt.Cursor
import java.awt.event.MouseEvent
import javax.swing.SwingUtilities

internal interface TerminalHyperlinkHost {
    val renderCache: TerminalRenderCache
    val session: TerminalSession?
    val hostServices: TerminalSwingHostServices
    var cursor: Cursor

    fun cellAt(
        x: Int,
        y: Int,
    ): Long

    fun repaint()
}

internal class TerminalHyperlinkController(
    private val host: TerminalHyperlinkHost,
) {
    companion object {
        private const val NO_HYPERLINK_ID = 0
        private val HAND_CURSOR: Cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        private val DEFAULT_CURSOR: Cursor = Cursor.getDefaultCursor()

        private fun unpackCellColumn(packed: Long): Int = (packed ushr 32).toInt()

        private fun unpackCellRow(packed: Long): Int = packed.toInt()
    }

    var hoveredHyperlinkId: Int = NO_HYPERLINK_ID
        private set
    var hyperlinkActivationHover: Boolean = false
        private set

    fun handleMouseMoved(event: MouseEvent) {
        updateHyperlinkHover(resolvableHyperlinkIdAt(event), activationHover = event.isControlDown)
    }

    fun handleMouseExited() {
        clearHyperlinkHover()
    }

    fun handleMousePressed(event: MouseEvent): Boolean {
        if (!SwingUtilities.isLeftMouseButton(event) || !event.isControlDown) return false
        val uri = hyperlinkUriAt(event) ?: return false
        if (!host.hostServices.hyperlinkHandler.openHyperlink(uri)) return false
        event.consume()
        return true
    }

    fun updateHyperlinkActivationHover(active: Boolean) {
        if (hoveredHyperlinkId == NO_HYPERLINK_ID || hyperlinkActivationHover == active) return
        hyperlinkActivationHover = active
        host.repaint()
    }

    fun clearHyperlinkHover() {
        updateHyperlinkHover(NO_HYPERLINK_ID, activationHover = false)
    }

    private fun updateHyperlinkHover(
        hyperlinkId: Int,
        activationHover: Boolean,
    ) {
        val normalizedActivationHover = hyperlinkId != NO_HYPERLINK_ID && activationHover
        val changed = hoveredHyperlinkId != hyperlinkId || hyperlinkActivationHover != normalizedActivationHover
        hoveredHyperlinkId = hyperlinkId
        hyperlinkActivationHover = normalizedActivationHover
        val nextCursor = if (hyperlinkId != NO_HYPERLINK_ID) HAND_CURSOR else DEFAULT_CURSOR
        if (host.cursor !== nextCursor) host.cursor = nextCursor
        if (changed) host.repaint()
    }

    private fun hyperlinkUriAt(event: MouseEvent): String? {
        val hyperlinkId = hyperlinkIdAt(event, host.renderCache)
        if (hyperlinkId == NO_HYPERLINK_ID) return null
        return host.session?.hyperlinkUri(hyperlinkId)
    }

    private fun resolvableHyperlinkIdAt(event: MouseEvent): Int {
        val hyperlinkId = hyperlinkIdAt(event, host.renderCache)
        if (hyperlinkId == NO_HYPERLINK_ID) return NO_HYPERLINK_ID
        val boundSession = host.session ?: return NO_HYPERLINK_ID
        return if (boundSession.hyperlinkUri(hyperlinkId) != null) hyperlinkId else NO_HYPERLINK_ID
    }

    private fun hyperlinkIdAt(
        event: MouseEvent,
        cache: TerminalRenderCache,
    ): Int {
        if (cache.columns <= 0 || cache.rows <= 0) return NO_HYPERLINK_ID
        val cell = host.cellAt(event.x, event.y)
        val column = unpackCellColumn(cell)
        val row = unpackCellRow(cell)
        return cache.hyperlinkIds[cache.rowOffset(row) + column]
    }
}

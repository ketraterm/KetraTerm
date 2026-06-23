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
package io.github.jvterm.ui.swing.render

import io.github.jvterm.render.cache.TerminalRenderCache
import io.github.jvterm.session.TerminalSession
import io.github.jvterm.ui.swing.settings.SwingMetrics
import io.github.jvterm.ui.swing.settings.SwingSettings

/**
 * Narrow adapter for render-frame scheduling and repaint planning.
 */
internal interface SwingRenderFrameHost {
    val session: TerminalSession?
    val renderCache: TerminalRenderCache
    val settings: SwingSettings
    val metrics: SwingMetrics
    val visualGeometry: TerminalVisualViewportGeometry
    val componentWidth: Int
    val componentHeight: Int
    val cursorPresentationEnabled: Boolean

    fun dispatch(action: Runnable)

    fun resetCursorBlinkForFrame()

    fun refreshRenderCacheFromSession(session: TerminalSession)

    /**
     * Resizes the bound terminal grid when the latest rendered frame changes
     * active-buffer chrome enough to alter visible rows or columns.
     *
     * @return true when the terminal grid was resized and the render cache must
     * be refreshed from the session again.
     */
    fun syncTerminalGridToActiveChrome(): Boolean

    fun clampViewport(historySize: Int): Boolean

    fun requestedViewportOffset(): Int

    fun refreshShellIntegrationDecorations(session: TerminalSession): Boolean

    fun refreshSearchForFrame()

    fun publishViewportState(historySize: Int)

    fun repaint()

    fun repaintRegion(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    )
}

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

import io.github.jvterm.ui.swing.viewport.SwingRepaintPlanner
import io.github.jvterm.ui.swing.viewport.TerminalRepaintSink
import java.util.concurrent.atomic.AtomicBoolean

/**
 * EDT render-frame scheduler and repaint planner for the Swing terminal.
 */
internal class SwingRenderFrameController(
    private val host: SwingRenderFrameHost,
) {
    private val repaintPlanner = SwingRepaintPlanner()
    private val renderPending = AtomicBoolean(false)
    private val repaintSink =
        object : TerminalRepaintSink {
            override fun requestFullRepaint() {
                host.repaint()
            }

            override fun requestRegionRepaint(
                x: Int,
                y: Int,
                width: Int,
                height: Int,
            ) {
                host.repaintRegion(x, y, width, height)
            }
        }
    private val publishedFrameRunnable =
        Runnable {
            renderPending.set(false)
            handlePublishedFrame()
        }

    fun reset() {
        repaintPlanner.reset()
        renderPending.set(false)
    }

    /**
     * Coalesces high-frequency render requests from the background IO thread.
     */
    fun schedulePublishedFrame() {
        if (!renderPending.compareAndSet(false, true)) return
        host.dispatch(publishedFrameRunnable)
    }

    fun handlePublishedFrame() {
        val boundSession = host.session ?: return
        host.resetCursorBlinkForFrame()
        host.refreshRenderCacheFromSession(boundSession)
        if (host.clampViewport(host.renderCache.historySize) || host.renderCache.scrollbackOffset != host.requestedViewportOffset()) {
            host.refreshRenderCacheFromSession(boundSession)
        }
        val shellIntegrationDecorationsChanged = host.refreshShellIntegrationDecorations(boundSession)
        host.refreshSearchForFrame()
        host.publishViewportState(host.renderCache.historySize)
        val yOffset = host.contentYOffset(host.renderCache)
        repaintPlanner.requestFrameRepaint(
            cache = host.renderCache,
            metrics = host.metrics,
            componentWidth = host.componentWidth,
            componentHeight = host.componentHeight,
            contentYOffset = yOffset,
            padding = host.settings.padding,
            repaintSink = repaintSink,
            forceFullRepaint = shellIntegrationDecorationsChanged,
        )
    }

    fun repaintBlinkState() {
        if (host.session == null) return
        val yOffset = host.contentYOffset(host.renderCache)
        if (host.cursorPresentationEnabled) {
            repaintPlanner.requestCursorBlinkRepaint(
                cache = host.renderCache,
                metrics = host.metrics,
                componentWidth = host.componentWidth,
                componentHeight = host.componentHeight,
                contentYOffset = yOffset,
                padding = host.settings.padding,
                repaintSink = repaintSink,
            )
        }
        repaintPlanner.requestBlinkingTextRepaint(
            cache = host.renderCache,
            metrics = host.metrics,
            componentWidth = host.componentWidth,
            componentHeight = host.componentHeight,
            contentYOffset = yOffset,
            padding = host.settings.padding,
            repaintSink = repaintSink,
        )
    }

    fun repaintCursorState() {
        if (host.session == null) return
        val yOffset = host.contentYOffset(host.renderCache)
        repaintPlanner.requestCursorRepaint(
            cache = host.renderCache,
            metrics = host.metrics,
            componentWidth = host.componentWidth,
            componentHeight = host.componentHeight,
            contentYOffset = yOffset,
            padding = host.settings.padding,
            repaintSink = repaintSink,
        )
    }
}

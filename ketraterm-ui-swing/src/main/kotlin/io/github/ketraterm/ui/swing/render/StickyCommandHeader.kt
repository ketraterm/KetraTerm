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
package io.github.ketraterm.ui.swing.render

import io.github.ketraterm.render.api.TerminalColorPalette
import io.github.ketraterm.render.api.TerminalRenderBufferKind
import io.github.ketraterm.render.api.TerminalRenderFrame
import io.github.ketraterm.render.cache.TerminalRenderCache
import io.github.ketraterm.render.cache.TerminalRenderRowSnapshot
import io.github.ketraterm.session.TerminalShellIntegrationView
import io.github.ketraterm.ui.swing.api.TerminalFontResolver
import io.github.ketraterm.ui.swing.settings.*
import java.awt.Color
import java.awt.Graphics2D
import java.awt.GraphicsConfiguration
import java.awt.Rectangle
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import kotlin.math.ceil
import kotlin.math.floor

/** EDT-owned command context overlay. Metadata lookup and prompt raster preparation never run during painting. */
internal class StickyCommandHeader(
    private val chrome: SwingTerminalChrome,
    fontResolver: TerminalFontResolver? = null,
) {
    private val promptBidiLayout = TerminalBidiLayout()
    private val promptPainter = GridPainter(fontResolver, cellGeometry = promptBidiLayout)
    private val promptCache = TerminalRenderCache(1, 1)
    private val bounds = Rectangle()
    private var source: TerminalShellIntegrationView? = null
    private var queriedLineId = 0L
    private var queriedRevision = Long.MIN_VALUE
    private var selectedRecordId = 0
    private var prompt: TerminalRenderRowSnapshot? = null
    private var raster: BufferedImage? = null
    private var rasterPrompt: TerminalRenderRowSnapshot? = null
    private var rasterPalette: TerminalColorPalette? = null
    private var rasterSettings: SwingSettings? = null
    private var rasterMetrics: SwingMetrics? = null
    private var rasterTextX = 0
    private var rasterWidth = 0
    private var rasterHeight = 0
    private var shadowHeight = 0
    private var configuration: GraphicsConfiguration? = null
    private var scaleX = 1.0
    private var scaleY = 1.0
    private var pressedRecordId = 0
    private var pressedSource: TerminalShellIntegrationView? = null
    private var pressedButtons = 0
    private var gestureCancelled = false
    private var pointerKnown = false
    private var pointerX = 0
    private var pointerY = 0

    var recordId: Int = 0
        private set
    val visible: Boolean get() = recordId != 0
    val gestureActive: Boolean get() = pressedButtons != 0
    val bottom: Int get() = bounds.y + bounds.height
    var hovered: Boolean = false
        private set

    /** Refreshes selection and the bounded current-header raster, returning whether pixels changed. */
    fun update(
        state: TerminalShellIntegrationView,
        cache: TerminalRenderCache,
        geometry: TerminalVisualViewportGeometry,
        settings: SwingSettings,
        metrics: SwingMetrics,
        width: Int,
        height: Int,
        graphicsConfiguration: GraphicsConfiguration?,
    ): Boolean {
        if (source !== state) {
            source = state
            queriedRevision = Long.MIN_VALUE
            selectedRecordId = 0
            prompt = null
        }
        val top = chrome.top(settings, cache.activeBuffer)
        val left = settings.padding.left
        val right = width - chrome.right(settings, cache.activeBuffer)
        val availableHeight = height - top - chrome.bottom(settings, cache.activeBuffer)
        if (!settings.stickyCommandHeadersEnabled ||
            !cache.hasFrame ||
            cache.activeBuffer != TerminalRenderBufferKind.PRIMARY ||
            geometry.rowCount != cache.rows ||
            cache.rows == 0 ||
            right <= left ||
            availableHeight < metrics.cellHeight
        ) {
            return hide()
        }

        val row = geometry.rowAt(floor(-geometry.contentOriginY).toInt())
        val firstAbsoluteRow = cache.discardedCount + cache.historySize - cache.scrollbackOffset
        if (geometry.contentOriginY + geometry.rowTop(row) > 0.0 ||
            geometry.dividerRowAtComponentY(top, top) >= 0 ||
            firstAbsoluteRow + row >= cache.outputEndAbsoluteRow
        ) {
            return hide()
        }

        val lineId = cache.lineIds[row]
        val revision = state.revision.value
        if (queriedLineId != lineId || queriedRevision != revision) {
            val nextId = if (lineId > 0) state.commandOutputRecordIdAtLine(lineId) else 0
            val nextPrompt = if (nextId != 0) state.commandPrompt(nextId) else null
            if (state.revision.value != revision) return hide()
            queriedLineId = lineId
            queriedRevision = revision
            selectedRecordId = nextId
            prompt = nextPrompt
        }
        val capturedPrompt = prompt
        if (selectedRecordId == 0 || capturedPrompt == null) return hide()

        val nextShadowHeight = minOf(SHADOW_HEIGHT, availableHeight - metrics.cellHeight)
        val changed =
            recordId != selectedRecordId ||
                bounds.x != left ||
                bounds.y != top ||
                bounds.width != right - left ||
                bounds.height != metrics.cellHeight ||
                shadowHeight != nextShadowHeight
        bounds.setBounds(left, top, right - left, metrics.cellHeight)
        shadowHeight = nextShadowHeight
        recordId = selectedRecordId
        refreshPointer()
        val textX = chrome.left(settings, cache.activeBuffer) - left
        val rasterChanged =
            raster == null ||
                rasterPrompt !== capturedPrompt ||
                rasterSettings !== settings ||
                rasterMetrics != metrics ||
                rasterPalette !== cache.palette ||
                rasterTextX != textX ||
                rasterWidth != bounds.width ||
                rasterHeight != bounds.height + shadowHeight ||
                configuration !== graphicsConfiguration
        if (rasterChanged) {
            if (configuration !== graphicsConfiguration) {
                configuration = graphicsConfiguration
                val transform = graphicsConfiguration?.defaultTransform
                scaleX = transform?.scaleX ?: 1.0
                scaleY = transform?.scaleY ?: 1.0
            }
            prepareRaster(capturedPrompt, cache.palette, settings, metrics, textX)
        }
        return changed || rasterChanged
    }

    fun contains(
        x: Int,
        y: Int,
    ): Boolean = visible && bounds.contains(x, y)

    fun updatePointer(
        x: Int,
        y: Int,
    ) {
        pointerKnown = true
        pointerX = x
        pointerY = y
        hovered = contains(x, y)
    }

    fun refreshPointer() {
        hovered = pointerKnown && contains(pointerX, pointerY)
    }

    fun clearHover() {
        pointerKnown = false
        hovered = false
    }

    fun press(button: Int) {
        val mask = buttonMask(button)
        if (mask == 0) return
        if (gestureActive) {
            gestureCancelled = true
        } else {
            pressedRecordId = recordId
            pressedSource = source
            gestureCancelled = false
        }
        pressedButtons = pressedButtons or mask
    }

    /** Releases belong to the surface that received that button's press, regardless of pointer position. */
    fun ownsButton(button: Int): Boolean = pressedButtons and buttonMask(button) != 0

    fun drag(
        x: Int,
        y: Int,
    ) {
        if (!contains(x, y)) gestureCancelled = true
    }

    /** Returns a record to reveal only for an uncancelled primary click on the same binding/header. */
    fun release(
        x: Int,
        y: Int,
        button: Int,
    ): Int {
        if (!ownsButton(button)) return 0
        val reveal =
            if (button == 1 &&
                !gestureCancelled &&
                pressedSource === source &&
                pressedRecordId == recordId &&
                contains(x, y)
            ) {
                recordId
            } else {
                0
            }
        pressedButtons = pressedButtons and buttonMask(button).inv()
        if (!gestureActive) pressedSource = null
        return reveal
    }

    fun paint(g: Graphics2D) {
        val image = raster ?: return
        val paintHeight = bounds.height + shadowHeight
        if (visible && g.hitClip(bounds.x, bounds.y, bounds.width, paintHeight)) {
            g.drawImage(image, bounds.x, bounds.y, bounds.width, paintHeight, null)
        }
    }

    fun reset() {
        hide()
        source = null
        queriedLineId = 0
        queriedRevision = Long.MIN_VALUE
        selectedRecordId = 0
        prompt = null
        promptCache.reset()
        promptPainter.clearImages()
        raster?.flush()
        raster = null
        rasterPrompt = null
        rasterPalette = null
        rasterSettings = null
        rasterMetrics = null
        pressedButtons = 0
        pressedSource = null
    }

    private fun hide(): Boolean {
        val changed = visible
        recordId = 0
        hovered = false
        return changed
    }

    private fun prepareRaster(
        capturedPrompt: TerminalRenderRowSnapshot,
        palette: TerminalColorPalette,
        settings: SwingSettings,
        metrics: SwingMetrics,
        textX: Int,
    ) {
        val pixelWidth = ceil(bounds.width * scaleX).toInt().coerceAtLeast(1)
        val headerPixelHeight = ceil(bounds.height * scaleY).toInt().coerceAtLeast(1)
        val pixelHeight = ceil((bounds.height + shadowHeight) * scaleY).toInt().coerceAtLeast(headerPixelHeight)
        // LCD text antialiasing requires an opaque rendering surface.
        val opaqueHeader = BufferedImage(pixelWidth, headerPixelHeight, BufferedImage.TYPE_INT_RGB)
        val g = opaqueHeader.createGraphics()
        try {
            // Logical clears can round down at fractional scales; initialize every opaque device pixel.
            g.color = Color(palette.defaultBackground or 0xff000000.toInt(), true)
            g.fillRect(0, 0, pixelWidth, headerPixelHeight)
            g.scale(scaleX, scaleY)
            // Independent immutable snapshots can share their original line id and generation.
            promptBidiLayout.reset()
            promptCache.reset()
            promptCache.accept(
                object : TerminalRenderFrame by capturedPrompt {
                    override val palette = palette
                },
            )
            val presentation =
                settings.copy {
                    it.promptDecoration = SwingPromptDecoration.NONE
                    it.padding = SwingPadding(0, textX, 0, 0)
                }
            promptPainter.prepareFrame(promptCache)
            promptPainter.paint(
                g,
                promptCache,
                presentation,
                metrics,
                bounds.width,
                bounds.height,
                cursorBlinkVisible = false,
                textBlinkVisible = true,
            )
            val separator = settings.shellIntegrationPromptDividerColor ?: ((palette.defaultForeground and 0x00ffffff) or 0x30000000)
            g.color = Color(separator, true)
            g.transform = AffineTransform()
            g.fillRect(0, headerPixelHeight - 1, pixelWidth, 1)
        } finally {
            g.dispose()
        }
        val image = BufferedImage(pixelWidth, pixelHeight, BufferedImage.TYPE_INT_ARGB_PRE)
        val composite = image.createGraphics()
        try {
            composite.drawImage(opaqueHeader, 0, 0, null)
            val shadowPixels = pixelHeight - headerPixelHeight
            for (row in 0 until shadowPixels) {
                val alpha = if (shadowPixels == 1) SHADOW_ALPHA else SHADOW_ALPHA * (shadowPixels - row - 1) / (shadowPixels - 1)
                composite.color = Color(0, 0, 0, alpha)
                composite.fillRect(0, headerPixelHeight + row, pixelWidth, 1)
            }
        } finally {
            composite.dispose()
            opaqueHeader.flush()
        }
        raster?.flush()
        raster = image
        rasterPrompt = capturedPrompt
        rasterPalette = palette
        rasterSettings = settings
        rasterMetrics = metrics
        rasterTextX = textX
        rasterWidth = bounds.width
        rasterHeight = bounds.height + shadowHeight
    }

    private companion object {
        fun buttonMask(button: Int): Int = if (button in 1 until Int.SIZE_BITS) 1 shl button else 0

        const val SHADOW_HEIGHT = 4
        const val SHADOW_ALPHA = 48
    }
}

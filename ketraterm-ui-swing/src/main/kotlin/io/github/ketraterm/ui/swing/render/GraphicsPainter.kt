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

import io.github.ketraterm.render.cache.TerminalRenderImages
import io.github.ketraterm.ui.swing.settings.SwingMetrics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.Shape
import kotlin.math.ceil
import kotlin.math.floor

/**
 * EDT-owned compositor for image placements in physical viewport cells.
 *
 * The owner prepares each installed placement set before painting. Pixel copies
 * and raster creation happen during preparation; painting reuses those rasters.
 * Contiguous rows share one draw; prompt dividers split the image without
 * stretching pixels across their bands. Additional clipping is applied only
 * where the image would otherwise cross its placement or viewport bounds.
 */
internal class GraphicsPainter {
    private val rasters = GraphicsRasterCache()
    private val clip = Rectangle()
    private val pixels = TerminalDeviceGeometry()

    /** Installs raster references in the same order as the borrowed placement set. */
    fun prepare(images: TerminalRenderImages) = rasters.prepare(images)

    /** Releases the view's image references when its source is unbound or replaced. */
    fun clear() = rasters.clear()

    /**
     * Paints one composition [band]: 0 below nondefault backgrounds, 1 above
     * backgrounds but below text, or 2 above text and row-level highlights.
     * [GridPainter] owns the surrounding text, cursor and overlay ordering.
     */
    fun paint(
        g: Graphics2D,
        images: TerminalRenderImages,
        metrics: SwingMetrics,
        geometry: TerminalVisualViewportGeometry?,
        band: Int,
        columns: Int,
        rows: Int,
    ) {
        if (images.size == 0) return
        val transform = pixels.update(g)
        val fractionalTransform =
            transform.scaleX % 1.0 != 0.0 ||
                transform.scaleY % 1.0 != 0.0 ||
                transform.translateX % 1.0 != 0.0 ||
                transform.translateY % 1.0 != 0.0
        // SunGraphics2D leaves the supplied rectangle unchanged when no user clip exists.
        clip.setBounds(0, 0, -1, -1)
        val hasClip = g.getClipBounds(clip) != null && clip.width >= 0 && clip.height >= 0
        var originalClip: Shape? = null
        var savedClip = false
        val gridWidth = columns * metrics.cellWidth
        for (i in 0 until images.size) {
            val z = images.zIndex(i)
            val layer =
                if (z < -1073741824) {
                    0
                } else if (z < 0) {
                    1
                } else {
                    2
                }
            if (layer != band) continue
            val x1 = images.column(i) * metrics.cellWidth
            val x2 = (images.column(i) + images.width(i)) * metrics.cellWidth
            if (x2 <= 0 ||
                x1 >= gridWidth ||
                x2 <= x1 ||
                hasClip &&
                (x2 <= clip.x || x1 >= clip.x + clip.width)
            ) {
                continue
            }
            val first = floor(maxOf(images.row(i), images.clipTop(i), 0.0)).toInt()
            val last =
                ceil(
                    minOf(
                        images.row(i) + images.height(i),
                        images.clipBottom(i),
                        rows.toDouble(),
                    ),
                ).toInt()
            var row = first
            while (row < last) {
                val rowTop = geometry?.rowTop(row) ?: (row * metrics.cellHeight)
                val shift = rowTop - row * metrics.cellHeight
                var endRow = last
                if (geometry != null) {
                    endRow = row + 1
                    while (endRow < last && !geometry.hasDividerBefore(endRow)) endRow++
                }
                val logicalTop = maxOf(row.toDouble(), images.row(i), images.clipTop(i))
                val logicalBottom = minOf(endRow.toDouble(), images.row(i) + images.height(i), images.clipBottom(i))
                val top = rowTop + (logicalTop - row) * metrics.cellHeight
                val bottom = rowTop + (logicalBottom - row) * metrics.cellHeight
                if (bottom > top && (!hasClip || bottom > clip.y && top < clip.y + clip.height)) {
                    val imageTop = images.row(i) * metrics.cellHeight + shift
                    val imageBottom = (images.row(i) + images.height(i)) * metrics.cellHeight + shift
                    val needsClip =
                        fractionalTransform ||
                            x1 % 1.0 != 0.0 ||
                            x2 % 1.0 != 0.0 ||
                            imageTop % 1.0 != 0.0 ||
                            imageBottom % 1.0 != 0.0 ||
                            (if (hasClip) maxOf(x1, clip.x.toDouble()) else x1) < 0 ||
                            (if (hasClip) minOf(x2, (clip.x + clip.width).toDouble()) else x2) > gridWidth ||
                            (if (hasClip) maxOf(imageTop, clip.y.toDouble()) else imageTop) < top ||
                            (if (hasClip) minOf(imageBottom, (clip.y + clip.height).toDouble()) else imageBottom) > bottom
                    if (needsClip) {
                        if (!savedClip) {
                            originalClip = pixels.saveClip(g)
                            savedClip = true
                        }
                        val left = maxOf(0.0, x1)
                        val right = minOf(gridWidth.toDouble(), x2)
                        pixels.clipRect(g, left, top, right - left, bottom - top)
                    }
                    try {
                        g.translate(x1, imageTop)
                        g.scale((x2 - x1) / images.sourceWidth(i), (imageBottom - imageTop) / images.sourceHeight(i))
                        g.drawImage(
                            rasters.image(i),
                            0,
                            0,
                            images.sourceWidth(i),
                            images.sourceHeight(i),
                            images.sourceX(i),
                            images.sourceY(i),
                            images.sourceX(i) + images.sourceWidth(i),
                            images.sourceY(i) + images.sourceHeight(i),
                            null,
                        )
                    } finally {
                        g.transform = transform
                        if (needsClip) pixels.restoreClip(g, originalClip)
                    }
                }
                row = endRow
            }
        }
    }
}

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

import io.github.ketraterm.protocol.TerminalGraphicsLimits.IMAGES
import io.github.ketraterm.protocol.TerminalGraphicsLimits.RETAINED_BYTES
import io.github.ketraterm.render.api.TerminalRenderImage
import io.github.ketraterm.render.cache.TerminalRenderImages
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.awt.image.DirectColorModel
import java.util.*

/**
 * EDT-owned rasters keyed by immutable resource identity, shared by all placements
 * of that resource. Frame installation copies a new resource's pixels once; an
 * unchanged resource reuses its raster even when its placement metadata changes.
 * Offscreen resources stay cached until admission needs their pixel or record
 * capacity. Scrolling an unchanged image out of view and back therefore reuses
 * its raster. Both source references and rasters remain bounded and clear on unbind.
 */
internal class GraphicsRasterCache {
    private val slots = IdentityHashMap<TerminalRenderImage, Int>()
    private var resources = arrayOfNulls<TerminalRenderImage>(0)
    private var rasters = arrayOfNulls<BufferedImage>(0)
    private var live = BooleanArray(0)
    private var placements = arrayOfNulls<BufferedImage>(0)
    private var placementCount = 0
    private var bytes = 0L

    /** Releases resources and rasters while retaining primitive/reference-array capacity. */
    fun clear() {
        slots.clear()
        resources.fill(null)
        rasters.fill(null)
        placements.fill(null)
        placementCount = 0
        bytes = 0
    }

    /**
     * Rebuilds the placement-to-raster index for the installed frame, outside paint.
     * Failure releases all raster references; successful preparation is required
     * before [image] can be used again.
     */
    fun prepare(images: TerminalRenderImages) {
        live.fill(false)
        for (i in 0 until images.size) {
            val slot = slots[images.image(i)]
            if (slot != null) live[slot] = true
        }
        placements.fill(null, 0, placementCount)
        if (placements.size < images.size) placements = placements.copyOf(images.size)
        placementCount = images.size
        try {
            for (i in 0 until images.size) {
                val resource = images.image(i)
                var slot = slots[resource]
                if (slot == null) {
                    val size = resource.width.toLong() * resource.height * 4
                    require(
                        resource.width > 0 && resource.height > 0 && size > 0 && size <= RETAINED_BYTES,
                    ) { "Image raster budget exceeded" }
                    evictOffscreen(size)
                    require(bytes + size <= RETAINED_BYTES && slots.size < IMAGES) { "Image raster budget exceeded" }
                    var empty = resources.indexOfFirst { it == null }
                    if (empty < 0) {
                        empty = resources.size
                        val capacity = (resources.size.coerceAtLeast(8) * 2).coerceAtMost(IMAGES)
                        resources = resources.copyOf(capacity)
                        rasters = rasters.copyOf(capacity)
                        live = live.copyOf(capacity)
                    }
                    val raster = prepareRaster(resource)
                    resources[empty] = resource
                    rasters[empty] = raster
                    slots[resource] = empty
                    live[empty] = true
                    bytes += size
                    slot = empty
                }
                placements[i] = rasters[slot]
            }
        } catch (failure: Throwable) {
            clear()
            throw failure
        }
    }

    /** Returns the raster at a placement index from the most recent preparation. */
    fun image(index: Int): BufferedImage = checkNotNull(placements[index])

    /** Reclaims only absent resources, and only enough to admit the new raster. */
    private fun evictOffscreen(requiredBytes: Long) {
        for (i in resources.indices) {
            if (bytes + requiredBytes <= RETAINED_BYTES && slots.size < IMAGES) return
            val resource = resources[i] ?: continue
            if (live[i]) continue
            slots.remove(resource)
            bytes -= resource.width.toLong() * resource.height * 4
            resources[i] = null
            rasters[i] = null
        }
    }

    private fun prepareRaster(resource: TerminalRenderImage): BufferedImage {
        val image = BufferedImage(resource.width, resource.height, BufferedImage.TYPE_INT_ARGB)
        val pixels = (image.raster.dataBuffer as DataBufferInt).data
        resource.copyArgb(pixels)
        for (pixel in pixels) {
            if (pixel ushr 24 != 255) return image
        }
        // The RGB view shares the same storage and enables Java2D's opaque scaling
        // path. Keep alpha-bearing resources unassociated, preserving their samples.
        val rgb = image.raster.createWritableChild(0, 0, image.width, image.height, 0, 0, RGB_BANDS)
        return BufferedImage(RGB_COLOR_MODEL, rgb, false, null)
    }

    private companion object {
        val RGB_COLOR_MODEL = DirectColorModel(24, 0xff0000, 0x00ff00, 0x0000ff)
        val RGB_BANDS = intArrayOf(0, 1, 2)
    }
}

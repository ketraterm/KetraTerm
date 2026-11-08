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

import java.awt.Graphics2D
import java.awt.Shape
import java.awt.geom.AffineTransform
import java.awt.geom.Rectangle2D
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * EDT-owned device-pixel geometry for one painter's current graphics transform.
 *
 * Adjacent spans share rounded absolute edges, matching Swing's fractional-scale damage rounding.
 * Fills and clips use those same half-open bounds; rounding widths independently
 * would leave seams or blend translucent neighbors twice. Stroke thickness is
 * rounded independently so moving a stroke between panes cannot change its weight.
 * Logical coordinates and font advances remain unchanged. Rotated, reflected and
 * sheared graphics retain Java2D's ordinary transformed geometry.
 *
 * Call [update] after changing the transform. The transform snapshot is reused
 * across runs; cell bounds use primitives without rectangle or point objects.
 */
internal class TerminalDeviceGeometry {
    private val logicalBounds = Rectangle2D.Double()
    private var transform = AffineTransform()
    private var scaleX = 1.0
    private var scaleY = 1.0
    private var translateX = 0.0
    private var translateY = 0.0
    var axisAligned: Boolean = true
        private set

    fun update(g: Graphics2D): AffineTransform {
        transform = g.transform
        scaleX = transform.scaleX
        scaleY = transform.scaleY
        translateX = transform.translateX
        translateY = transform.translateY
        axisAligned = transform.shearX == 0.0 && transform.shearY == 0.0 && scaleX > 0.0 && scaleY > 0.0
        return transform
    }

    fun deviceX(x: Int): Int = deviceX(x.toDouble())

    fun deviceX(x: Double): Int = deviceEdge(x * scaleX + translateX)

    fun deviceY(y: Int): Int = deviceY(y.toDouble())

    fun deviceY(y: Double): Int = deviceEdge(y * scaleY + translateY)

    fun deviceWidth(width: Int): Int = (width * scaleX).roundToInt().coerceAtLeast(1)

    fun deviceHeight(height: Int): Int = (height * scaleY).roundToInt().coerceAtLeast(1)

    /** Saves clips in device space so inverse-transform round trips cannot enlarge their edges. */
    fun saveClip(g: Graphics2D): Shape? {
        g.transform = IDENTITY_TRANSFORM
        return try {
            g.clip
        } finally {
            g.transform = transform
        }
    }

    fun restoreClip(
        g: Graphics2D,
        clip: Shape?,
    ) {
        g.transform = IDENTITY_TRANSFORM
        try {
            g.clip = clip
        } finally {
            g.transform = transform
        }
    }

    /** Installs a rectangular repaint clip using Swing's device-pixel ownership. */
    fun alignClip(
        g: Graphics2D,
        clip: Rectangle2D,
    ) {
        if (!axisAligned) return
        val left = deviceX(clip.minX)
        val top = deviceY(clip.minY)
        val right = deviceX(clip.maxX)
        val bottom = deviceY(clip.maxY)
        g.transform = IDENTITY_TRANSFORM
        try {
            g.setClip(left, top, maxOf(0, right - left), maxOf(0, bottom - top))
        } finally {
            g.transform = transform
        }
    }

    fun fillRect(
        g: Graphics2D,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ) {
        if (width <= 0 || height <= 0) return
        if (axisAligned) {
            fillDeviceRect(g, deviceX(x), deviceY(y), deviceX(x + width), deviceY(y + height))
        } else {
            g.fillRect(x, y, width, height)
        }
    }

    /** Intersects the caller's exact clip, including holes and nonrectangular edges. */
    fun clipRect(
        g: Graphics2D,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ) = clipRect(g, x.toDouble(), y.toDouble(), width.toDouble(), height.toDouble())

    fun clipRect(
        g: Graphics2D,
        x: Double,
        y: Double,
        width: Double,
        height: Double,
    ) {
        if (axisAligned) {
            val left = deviceX(x)
            val top = deviceY(y)
            val right = deviceX(x + width)
            val bottom = deviceY(y + height)
            g.transform = IDENTITY_TRANSFORM
            try {
                g.clipRect(left, top, maxOf(0, right - left), maxOf(0, bottom - top))
            } finally {
                g.transform = transform
            }
        } else {
            logicalBounds.setRect(x, y, width, height)
            g.clip(logicalBounds)
        }
    }

    /** Disjoint borders blend each corner once; inactive cursor outlines use a device hairline. */
    fun border(
        g: Graphics2D,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        hairline: Boolean = false,
    ) {
        if (width <= 0 || height <= 0) return
        if (!axisAligned) {
            g.fillRect(x, y, width, 1)
            if (height > 1) g.fillRect(x, y + height - 1, width, 1)
            if (height > 2) {
                g.fillRect(x, y + 1, 1, height - 2)
                if (width > 1) g.fillRect(x + width - 1, y + 1, 1, height - 2)
            }
            return
        }
        val left = deviceX(x)
        val right = deviceX(x + width)
        val top = deviceY(y)
        val bottom = deviceY(y + height)
        val horizontal = minOf(if (hairline) 1 else deviceHeight(1), bottom - top)
        val vertical = minOf(if (hairline) 1 else deviceWidth(1), right - left)
        fillDeviceRect(g, left, top, right, top + horizontal)
        val lower = maxOf(top + horizontal, bottom - horizontal)
        fillDeviceRect(g, left, lower, right, bottom)
        fillDeviceRect(g, left, top + horizontal, left + vertical, lower)
        fillDeviceRect(g, maxOf(left + vertical, right - vertical), top + horizontal, right, lower)
    }

    /** Places bars at the leading edge and underlines at the bottom of the snapped cell. */
    fun cursorLine(
        g: Graphics2D,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        thickness: Int,
        vertical: Boolean,
    ) {
        if (!axisAligned) {
            if (vertical) {
                g.fillRect(x, y, minOf(width, thickness), height)
            } else {
                g.fillRect(x, y + maxOf(0, height - thickness), width, minOf(height, thickness))
            }
            return
        }
        val left = deviceX(x)
        val right = deviceX(x + width)
        val top = deviceY(y)
        val bottom = deviceY(y + height)
        if (vertical) {
            fillDeviceRect(g, left, top, minOf(right, left + deviceWidth(thickness)), bottom)
        } else {
            fillDeviceRect(g, left, maxOf(top, bottom - deviceHeight(thickness)), right, bottom)
        }
    }

    private fun fillDeviceRect(
        g: Graphics2D,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ) {
        if (right <= left || bottom <= top) return
        g.transform = IDENTITY_TRANSFORM
        try {
            g.fillRect(left, top, right - left, bottom - top)
        } finally {
            g.transform = transform
        }
    }

    private companion object {
        private val IDENTITY_TRANSFORM = AffineTransform()

        private fun deviceEdge(position: Double): Int {
            val edge = position - 0.5
            // Retrieving a clip applies an inverse transform. Mapping it back can
            // move an exact half-pixel tie by a few ulps; keep ownership stable
            // across that round trip without rounding other fractional positions.
            val nearest = Math.rint(edge)
            val stable = if (abs(edge - nearest) <= 4 * Math.ulp(position)) nearest else edge
            return ceil(stable).toInt()
        }
    }
}

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
package io.github.ketraterm.ui.swing.host

import io.github.ketraterm.protocol.TerminalProgressState
import io.github.ketraterm.session.TerminalProgress
import java.awt.*
import java.awt.geom.Arc2D
import javax.accessibility.AccessibleIcon
import javax.swing.Icon
import javax.swing.SwingUtilities
import javax.swing.UIManager

/**
 * Reusable thin progress ring. Construct, configure and paint on the EDT.
 * Size is in logical Swing pixels, inclusive 8..128. Hidden progress paints nothing.
 * The host owns session observation, icon replacement, repainting and animation;
 * advance [phase] for indeterminate work, or leave it fixed to disable animation.
 * Colors can be changed by embedders without changing session policy. Painting
 * inherits transform/clip, preserves color/stroke and rendering hints, and reuses
 * shapes/strokes. Java2D/platform rasterization may have its own allocations.
 */
public class SwingTerminalProgressIcon
    @JvmOverloads
    public constructor(
        private val size: Int = 16,
    ) : Icon,
        AccessibleIcon {
        init {
            check(SwingUtilities.isEventDispatchThread()) { "Progress icons require the EDT" }
            require(size in 8..128) { "size must be in 8..128" }
        }

        private var accessibleDescription: String? = null

        /** Immutable presentation snapshot; setting it does not schedule repaint. */
        public var progress: TerminalProgress = TerminalProgress.HIDDEN
            set(value) {
                checkEdt()
                field = value
            }

        /** One clockwise turn, in [0, 1); finite values outside this range are rejected. */
        public var phase: Double = 0.0
            set(value) {
                checkEdt()
                require(value.isFinite() && value >= 0 && value < 1)
                field = value
            }

        /** Theme accent for normal and indeterminate work. */
        public var normalColor: Color = UIManager.getColor("Component.accentColor") ?: Color(0xD98755)
            set(value) {
                checkEdt()
                field = value
                normalTrack = trackColor(value)
            }

        /** Error color; a tooltip should also identify the state. */
        public var errorColor: Color = Color(0xE35D6A)
            set(value) {
                checkEdt()
                field = value
                errorTrack = trackColor(value)
            }

        /** Warning/paused color; a tooltip should also identify the state. */
        public var warningColor: Color = Color(0xD9AA4B)
            set(value) {
                checkEdt()
                field = value
                warningTrack = trackColor(value)
            }

        private val arc = Arc2D.Double()
        private val stroke = BasicStroke(size / 10f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        private var normalTrack = trackColor(normalColor)
        private var errorTrack = trackColor(errorColor)
        private var warningTrack = trackColor(warningColor)

        private fun trackColor(color: Color): Color = Color(color.red, color.green, color.blue, color.alpha / 4)

        /** Host-supplied localized description, updated outside painting. */
        override fun getAccessibleIconDescription(): String? = accessibleDescription

        override fun setAccessibleIconDescription(description: String?) {
            checkEdt()
            accessibleDescription = description
        }

        override fun getAccessibleIconWidth(): Int = size

        override fun getAccessibleIconHeight(): Int = size

        override fun getIconWidth(): Int = size

        override fun getIconHeight(): Int = size

        override fun paintIcon(
            component: Component?,
            graphics: Graphics,
            x: Int,
            y: Int,
        ) {
            checkEdt()
            val state = progress.state
            if (state == TerminalProgressState.HIDDEN) return
            val g = graphics as? Graphics2D ?: return
            val oldColor = g.color
            val oldStroke = g.stroke
            val oldAntialiasing = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING)
            val enableAntialiasing = oldAntialiasing != null && oldAntialiasing != RenderingHints.VALUE_ANTIALIAS_ON
            try {
                if (enableAntialiasing) g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g.stroke = stroke
                val inset = size / 8.0
                val diameter = size - inset * 2
                arc.setArc(x + inset, y + inset, diameter, diameter, 0.0, 360.0, Arc2D.OPEN)
                if (state != TerminalProgressState.INDETERMINATE) {
                    g.color =
                        when (state) {
                            TerminalProgressState.ERROR -> errorTrack
                            TerminalProgressState.WARNING -> warningTrack
                            else -> normalTrack
                        }
                    g.draw(arc)
                }
                g.color =
                    when (state) {
                        TerminalProgressState.ERROR -> errorColor
                        TerminalProgressState.WARNING -> warningColor
                        else -> normalColor
                    }
                val start = if (state == TerminalProgressState.INDETERMINATE) 90.0 - phase * 360.0 else 90.0
                val extent = if (state == TerminalProgressState.INDETERMINATE) -85.0 else -progress.percentage * 3.6
                arc.setArc(x + inset, y + inset, diameter, diameter, start, extent, Arc2D.OPEN)
                g.draw(arc)
            } finally {
                g.color = oldColor
                g.stroke = oldStroke
                if (enableAntialiasing) {
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAntialiasing)
                }
            }
        }

        private fun checkEdt() {
            check(SwingUtilities.isEventDispatchThread()) { "Progress icons require the EDT" }
        }
    }

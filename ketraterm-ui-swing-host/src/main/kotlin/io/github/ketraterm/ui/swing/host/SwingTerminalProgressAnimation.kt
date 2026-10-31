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

import java.awt.event.HierarchyEvent
import java.awt.event.HierarchyListener
import java.util.function.DoubleConsumer
import javax.swing.JComponent
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * One visibility-bound animation clock for a host's progress icons. All calls are
 * EDT-confined. [frame] receives a normalized clockwise phase approximately 30 times per
 * second while [active] and [component] is showing. It should update only visible
 * indeterminate icons and request repaint. The component and sessions are borrowed.
 * Close on owner disposal to stop the clock and detach its hierarchy listener.
 */
public class SwingTerminalProgressAnimation(
    private val component: JComponent,
    private val frame: DoubleConsumer,
) : AutoCloseable {
    private var closed = false
    private var origin = 0L
    private val timer = Timer(33) { advanceFrame(System.nanoTime()) }
    private val visibility =
        HierarchyListener { event ->
            if (event.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L) updateRunning()
        }

    /** Host eligibility, normally true only when a visible icon is indeterminate. */
    public var active: Boolean = false
        set(value) {
            checkEdt()
            if (closed || field == value) return
            field = value
            updateRunning()
        }

    /** Whether the visibility-bound Swing clock is currently running. */
    public val isRunning: Boolean
        get() {
            checkEdt()
            return timer.isRunning
        }

    init {
        checkEdt()
        timer.isCoalesce = true
        component.addHierarchyListener(visibility)
    }

    internal fun advanceFrame(now: Long) {
        if (closed || !active || !component.isShowing) return
        val elapsed = (now - origin).coerceAtLeast(0)
        frame.accept((elapsed % 1_000_000_000L).toDouble() / 1_000_000_000.0)
    }

    private fun updateRunning() {
        if (!closed && active && component.isShowing) {
            if (!timer.isRunning) {
                origin = System.nanoTime()
                timer.start()
            }
        } else {
            timer.stop()
        }
    }

    override fun close() {
        checkEdt()
        if (closed) return
        active = false
        closed = true
        timer.stop()
        component.removeHierarchyListener(visibility)
    }

    private fun checkEdt() {
        check(SwingUtilities.isEventDispatchThread()) { "Progress animation requires the EDT" }
    }
}

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
package io.github.jvterm.ui.swing.viewport

/**
 * Receives repaint requests computed by [TerminalSwingRepaintPlanner].
 *
 * This interface keeps repaint coordinates as JVM primitives and avoids
 * allocating Kotlin function objects for each published render frame.
 */
internal interface TerminalRepaintSink {
    /**
     * Requests repainting the whole component.
     */
    fun requestFullRepaint()

    /**
     * Requests repainting one bounded component region.
     *
     * @param x left edge of the repaint region in component pixels.
     * @param y top edge of the repaint region in component pixels.
     * @param width repaint region width in pixels.
     * @param height repaint region height in pixels.
     */
    fun requestRegionRepaint(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    )
}

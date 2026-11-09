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
package io.github.ketraterm.app.ui

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.awt.Dimension
import java.awt.Insets
import java.awt.Rectangle

class WindowResizeGeometryTest {
    @Test
    fun `initial window keeps its preferred size when it fits inside desktop insets`() {
        assertEquals(
            Rectangle(350, 165, 1200, 700),
            initialWindowBounds(Dimension(1200, 700), Rectangle(0, 0, 1920, 1080), Insets(30, 10, 80, 30)),
        )
    }

    @Test
    fun `initial window shrinks to a scaled monitor work area with a negative origin`() {
        assertEquals(
            Rectangle(-1280, 24, 1280, 656),
            initialWindowBounds(Dimension(1600, 900), Rectangle(-1280, 0, 1280, 720), Insets(24, 0, 40, 0)),
        )
        assertEquals(Rectangle(0, 150, 800, 300), initialWindowBounds(Dimension(1200, 300), Rectangle(0, 0, 800, 600), Insets(0, 0, 0, 0)))
    }

    @Test
    fun `initial window arithmetic cannot wrap for invalid work areas or extreme coordinates`() {
        val bounds =
            initialWindowBounds(
                Dimension(Int.MAX_VALUE, Int.MAX_VALUE),
                Rectangle(Int.MAX_VALUE, Int.MIN_VALUE, 1920, 1080),
                Insets(Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE),
            )
        assertEquals(Rectangle(Int.MAX_VALUE, -1, 1, 1), bounds)
    }

    private val geometry =
        WindowResizeGeometry(
            cellWidth = 8,
            cellHeight = 16,
            windowExtraWidth = 20,
            windowExtraHeight = 40,
            primaryInsetWidth = 12,
            primaryInsetHeight = 4,
            alternateInsetWidth = 0,
            alternateInsetHeight = 0,
            minimumWidth = 200,
            minimumHeight = 100,
            availableWidth = 1600,
            availableHeight = 1000,
            windowX = 0,
            windowY = 0,
            availableX = 0,
            availableY = 0,
        )

    @Test
    fun `column switches preserve rows and account for active screen chrome`() {
        assertEquals(Rectangle(0, 0, 1088, 428), geometry.targetBounds(132, 24, alternate = false))
        assertEquals(Rectangle(0, 0, 672, 428), geometry.targetBounds(80, 24, alternate = false))
        assertEquals(Rectangle(0, 0, 1076, 424), geometry.targetBounds(132, 24, alternate = true))
    }

    @Test
    fun `growth moves only the edges that would exceed the work area`() {
        val nearRightEdge = geometry.copy(windowX = 700, windowY = 100)
        assertEquals(Rectangle(700, 100, 672, 428), nearRightEdge.targetBounds(80, 24, false))
        assertEquals(Rectangle(512, 100, 1088, 428), nearRightEdge.targetBounds(132, 24, false))
        assertEquals(
            Rectangle(512, 572, 1088, 428),
            nearRightEdge.copy(windowY = 800).targetBounds(132, 24, false),
        )
    }

    @Test
    fun `work area offsets support reserved desktop space and negative monitor coordinates`() {
        val secondaryMonitor =
            geometry.copy(
                windowX = -700,
                windowY = 900,
                availableX = -1912,
                availableY = 32,
                availableWidth = 1904,
                availableHeight = 1000,
            )
        assertEquals(Rectangle(-1096, 604, 1088, 428), secondaryMonitor.targetBounds(132, 24, false))
        assertEquals(
            Rectangle(-1912, 32, 672, 428),
            secondaryMonitor.copy(windowX = -2000, windowY = 0).targetBounds(80, 24, false),
        )
    }

    @Test
    fun `work area edge arithmetic does not wrap at integer limits`() {
        val extremeOrigin =
            geometry.copy(
                availableX = Int.MAX_VALUE - 100,
                availableY = Int.MIN_VALUE,
                windowX = Int.MAX_VALUE,
                windowY = Int.MIN_VALUE,
            )
        assertEquals(
            Rectangle(Int.MAX_VALUE, Int.MIN_VALUE, 1088, 428),
            extremeOrigin.targetBounds(132, 24, false),
        )
    }

    @Test
    fun `requests cannot overflow or exceed desktop and minimum window bounds`() {
        assertNull(geometry.targetBounds(Int.MAX_VALUE, 24, false))
        assertNull(geometry.targetBounds(80, Int.MAX_VALUE, false))
        assertNull(geometry.targetBounds(0, 24, false))
        assertNull(geometry.targetBounds(80, -1, false))
        assertNull(geometry.targetBounds(1, 1, false))
        assertNull(geometry.copy(availableWidth = 1087).targetBounds(132, 24, false))
        assertNotNull(geometry.copy(availableWidth = 1088).targetBounds(132, 24, false))
    }

    @Test
    fun `window resize rejects a one column grid even when its pixels fit`() {
        val small = geometry.copy(minimumWidth = 1, minimumHeight = 1)

        assertNull(small.targetBounds(1, 1, alternate = false))
        assertNull(small.targetBounds(1, 1, alternate = true))
        assertEquals(Rectangle(0, 0, 48, 60), small.targetBounds(2, 1, alternate = false))
        assertEquals(Rectangle(0, 0, 36, 56), small.targetBounds(2, 1, alternate = true))
    }
}

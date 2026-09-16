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

class WindowResizeGeometryTest {
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
            windowOriginFits = true,
        )

    @Test
    fun `column switches preserve rows and account for active screen chrome`() {
        assertEquals(Dimension(1088, 428), geometry.targetSize(132, 24, alternate = false))
        assertEquals(Dimension(672, 428), geometry.targetSize(80, 24, alternate = false))
        assertEquals(Dimension(1076, 424), geometry.targetSize(132, 24, alternate = true))
    }

    @Test
    fun `requests cannot overflow or exceed desktop and minimum window bounds`() {
        assertNull(geometry.targetSize(Int.MAX_VALUE, 24, false))
        assertNull(geometry.targetSize(80, Int.MAX_VALUE, false))
        assertNull(geometry.targetSize(0, 24, false))
        assertNull(geometry.targetSize(80, -1, false))
        assertNull(geometry.targetSize(1, 1, false))
        assertNull(geometry.copy(availableWidth = 1087).targetSize(132, 24, false))
        assertNotNull(geometry.copy(availableWidth = 1088).targetSize(132, 24, false))
        assertNull(geometry.copy(windowOriginFits = false).targetSize(80, 24, false))
    }
}

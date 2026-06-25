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
package io.github.ketraterm.ui.swing.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TerminalVisualBellControllerTest {
    @Test
    fun `trigger starts at configured alpha and fades to zero`() {
        var repaints = 0
        val controller = TerminalVisualBellController { repaints++ }
        controller.configure(
            enabled = true,
            colorArgb = 0x664DA3FF,
            durationMillis = 240,
            edgeThicknessPixels = 18,
        )

        controller.triggerAt(1_000L)

        assertEquals(0x66, controller.alphaAt(1_000L))
        assertTrue(controller.alphaAt(1_120L) in 1 until 0x66)
        assertEquals(0, controller.alphaAt(1_240L))
        assertTrue(repaints > 0)

        controller.stop()
    }

    @Test
    fun `disabled controller ignores trigger`() {
        var repaints = 0
        val controller = TerminalVisualBellController { repaints++ }
        controller.configure(
            enabled = false,
            colorArgb = 0x664DA3FF,
            durationMillis = 240,
            edgeThicknessPixels = 18,
        )
        repaints = 0

        controller.triggerAt(1_000L)

        assertEquals(0, controller.alphaAt(1_000L))
        assertEquals(0, repaints)
    }
}

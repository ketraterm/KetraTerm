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

import io.github.ketraterm.host.HostControlPolicy
import io.github.ketraterm.host.HostPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.awt.Cursor

class SwingTerminalPointerShapeTest {
    private val deniedWindowPolicy = HostPolicy(windowManipulationPolicy = HostControlPolicy.DENY)

    @Test
    fun `all supported shapes update a stationary pointer and invalid requests preserve it`() {
        val shapes =
            listOf(
                "default" to Cursor.DEFAULT_CURSOR,
                "crosshair" to Cursor.CROSSHAIR_CURSOR,
                "text" to Cursor.TEXT_CURSOR,
                "wait" to Cursor.WAIT_CURSOR,
                "sw-resize" to Cursor.SW_RESIZE_CURSOR,
                "se-resize" to Cursor.SE_RESIZE_CURSOR,
                "nw-resize" to Cursor.NW_RESIZE_CURSOR,
                "ne-resize" to Cursor.NE_RESIZE_CURSOR,
                "n-resize" to Cursor.N_RESIZE_CURSOR,
                "s-resize" to Cursor.S_RESIZE_CURSOR,
                "w-resize" to Cursor.W_RESIZE_CURSOR,
                "e-resize" to Cursor.E_RESIZE_CURSOR,
                "pointer" to Cursor.HAND_CURSOR,
                "move" to Cursor.MOVE_CURSOR,
            )
        SwingTerminalHyperlinkLifecycleFixture(hostPolicy = deniedWindowPolicy).use { fixture ->
            fixture.replaceOutput("plain")
            fixture.movePointer(1)
            for ((name, type) in shapes) {
                fixture.setPointerShape(name)
                assertEquals(type, fixture.cursorType(), name)
                fixture.setPointerShape("unsupported")
                assertEquals(type, fixture.cursorType(), "invalid request after $name")
            }
            fixture.setPointerShape("")
            assertEquals(Cursor.DEFAULT_CURSOR, fixture.cursorType())
        }
    }

    @Test
    fun `hyperlinks override application pointer and leaving restores the application request`() {
        SwingTerminalHyperlinkLifecycleFixture(hostPolicy = deniedWindowPolicy).use { fixture ->
            fixture.awaitHyperlink()
            fixture.movePointer(1)
            fixture.setPointerShape("wait")
            assertEquals(Cursor.HAND_CURSOR, fixture.cursorType())
            fixture.movePointer(1000)
            assertEquals(Cursor.WAIT_CURSOR, fixture.cursorType())
            fixture.movePointer(1)
            assertEquals(Cursor.HAND_CURSOR, fixture.cursorType())
            fixture.setMouseReporting(true)
            assertEquals(Cursor.WAIT_CURSOR, fixture.cursorType())
            fixture.setMouseReporting(false)
            assertEquals(Cursor.HAND_CURSOR, fixture.cursorType())
        }
    }

    @Test
    fun `pointer requests and resets remain independent of window permission changes`() {
        SwingTerminalHyperlinkLifecycleFixture(hostPolicy = deniedWindowPolicy).use { fixture ->
            fixture.replaceOutput("plain")
            fixture.movePointer(1)
            fixture.setPointerShape("wait")
            assertEquals(Cursor.WAIT_CURSOR, fixture.cursorType())
            fixture.allowWindowManipulation(true)
            assertEquals(Cursor.WAIT_CURSOR, fixture.cursorType())
            fixture.allowWindowManipulation(false)
            assertEquals(Cursor.WAIT_CURSOR, fixture.cursorType())
            fixture.setPointerShape("text")
            assertEquals(Cursor.TEXT_CURSOR, fixture.cursorType())
            fixture.setPointerShape("")
            assertEquals(Cursor.DEFAULT_CURSOR, fixture.cursorType())
            fixture.setPointerShape("move")
            assertEquals(Cursor.MOVE_CURSOR, fixture.cursorType())
        }
    }

    @Test
    fun `unbind rebind and close respect session ownership of pointer state`() {
        SwingTerminalHyperlinkLifecycleFixture(hostPolicy = deniedWindowPolicy).use { fixture ->
            fixture.replaceOutput("plain")
            fixture.setPointerShape("text")
            assertEquals(Cursor.TEXT_CURSOR, fixture.cursorType())
            fixture.unbind()
            assertEquals(Cursor.DEFAULT_CURSOR, fixture.cursorType())
            fixture.rebind()
            fixture.settle()
            assertEquals(Cursor.TEXT_CURSOR, fixture.cursorType())
            fixture.closeSession()
            assertEquals(Cursor.DEFAULT_CURSOR, fixture.cursorType())
        }
    }
}

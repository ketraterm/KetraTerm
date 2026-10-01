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

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.awt.Cursor

class SwingTerminalHyperlinkLifecycleTest {
    @Test
    fun `published detected link exposes its full target through the host menu`() {
        SwingTerminalHyperlinkLifecycleFixture().use { fixture ->
            fixture.awaitHyperlink()

            assertTrue(fixture.observation().hasHyperlink)
            assertTrue(fixture.openHyperlink())
            assertEquals(listOf(SwingTerminalHyperlinkLifecycleFixture.URL), fixture.openedTargets())
        }
    }

    @Test
    fun `focus round trip preserves detected activation without terminal output`() {
        SwingTerminalHyperlinkLifecycleFixture().use { fixture ->
            fixture.awaitHyperlink()
            val initial = fixture.observation()
            assertEquals(Cursor.HAND_CURSOR, fixture.cursorType())

            fixture.focus(false)
            assertEquals(Cursor.DEFAULT_CURSOR, fixture.cursorType())
            fixture.focus(true)

            val returned = fixture.observation()
            assertEquals(initial.contentGeneration, returned.contentGeneration)
            assertTrue(returned.hasHyperlink)
            assertEquals(initial.detectorCalls, returned.detectorCalls)
            assertEquals(Cursor.HAND_CURSOR, fixture.cursorType())
            assertTrue(fixture.openHyperlink())
            assertEquals(listOf(SwingTerminalHyperlinkLifecycleFixture.URL), fixture.openedTargets())
        }
    }

    @Test
    fun `reattachment restores prepared links and stationary hover without another frame`() {
        SwingTerminalHyperlinkLifecycleFixture().use { fixture ->
            fixture.awaitHyperlink()
            val initial = fixture.observation()

            fixture.detach()
            assertFalse(fixture.observation().displayable)
            assertTrue(fixture.observation().bound)
            fixture.attach()

            val attached = fixture.observation()
            assertTrue(attached.displayable)
            assertTrue(attached.bound)
            assertEquals(initial.contentGeneration, attached.contentGeneration)
            assertEquals(SwingTerminalHyperlinkLifecycleFixture.URL, fixture.copyAllText())

            assertTrue(attached.hasHyperlink)
            assertEquals(initial.detectorCalls, attached.detectorCalls)
            assertEquals(Cursor.HAND_CURSOR, fixture.cursorType())
            assertTrue(fixture.openHyperlink())
            assertEquals(listOf(SwingTerminalHyperlinkLifecycleFixture.URL), fixture.openedTargets())
        }
    }

    @Test
    fun `hiding preserves pending discovery and showing reconciles the stationary pointer`() {
        SwingTerminalHyperlinkLifecycleFixture().use { fixture ->
            fixture.show(false)
            fixture.settle()
            val hidden = fixture.observation()
            assertTrue(hidden.hasHyperlink)
            fixture.show(true)
            assertEquals(hidden.detectorCalls, fixture.observation().detectorCalls)
            assertEquals(Cursor.HAND_CURSOR, fixture.cursorType())
            assertTrue(fixture.openHyperlink())
        }
    }

    @Test
    fun `returning with the pointer outside removes stale hover`() {
        SwingTerminalHyperlinkLifecycleFixture().use { fixture ->
            fixture.awaitHyperlink()
            fixture.focus(false)
            fixture.pointerOutside()
            fixture.focus(true)
            assertEquals(Cursor.DEFAULT_CURSOR, fixture.cursorType())
            assertTrue(fixture.observation().hasHyperlink)
        }
    }

    @Test
    fun `unbind before worker dispatch releases the slot and rebinding still discovers`() {
        SwingTerminalHyperlinkLifecycleFixture().use { fixture ->
            fixture.unbind()
            fixture.settle()
            assertEquals(0, fixture.observation().detectorCalls)
            fixture.rebind()
            fixture.awaitHyperlink()
            assertTrue(fixture.openHyperlink())
        }
    }

    companion object {
        /** Diagnostic trace only: missing links are observations, never expected regression-test behavior. */
        @JvmStatic
        fun main(args: Array<String>) {
            SwingTerminalHyperlinkLifecycleFixture().use { fixture ->
                fixture.awaitHyperlink()
                println("initial: ${fixture.observation()}")
                fixture.focus(false)
                fixture.focus(true)
                println("focus-return-without-frame: ${fixture.observation()}")
                fixture.detach()
                println("detached: ${fixture.observation()}")
                fixture.attach()
                println("reattached-before-frame: ${fixture.observation()}")
                fixture.requestFrame()
                fixture.awaitHyperlink()
                println("after-explicit-frame: ${fixture.observation()}")
            }
        }
    }
}

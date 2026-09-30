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

            fixture.focus(false)
            fixture.focus(true)

            val returned = fixture.observation()
            assertEquals(initial.contentGeneration, returned.contentGeneration)
            assertTrue(returned.hasHyperlink)
            assertTrue(fixture.openHyperlink())
            assertEquals(listOf(SwingTerminalHyperlinkLifecycleFixture.URL), fixture.openedTargets())
        }
    }

    @Test
    fun `reattachment retains terminal text and frame publication supports activation`() {
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

            fixture.requestFrame()
            fixture.awaitHyperlink()
            assertTrue(fixture.openHyperlink())
            assertEquals(listOf(SwingTerminalHyperlinkLifecycleFixture.URL), fixture.openedTargets())
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

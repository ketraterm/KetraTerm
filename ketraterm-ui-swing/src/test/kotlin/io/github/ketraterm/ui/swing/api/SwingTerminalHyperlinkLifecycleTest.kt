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

import io.github.ketraterm.render.api.TerminalRenderUnderline
import io.github.ketraterm.ui.swing.input.hyperlinkNavigationModifierMask
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.awt.Cursor
import java.awt.event.InputEvent
import java.awt.event.KeyEvent

class SwingTerminalHyperlinkLifecycleTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `consecutive mouse moves within a prepared link preserve the hand cursor`(osc8: Boolean) {
        SwingTerminalHyperlinkLifecycleFixture().use { fixture ->
            fixture.awaitHyperlink()
            if (osc8) {
                fixture.replaceOutput("\u001b]8;id=cursor;https://example.com/osc8\u0007OSC8 link\u001b]8;;\u0007")
            }
            fixture.movePointer(400)
            assertEquals(Cursor.DEFAULT_CURSOR, fixture.cursorType())
            for (modifiers in listOf(0, hyperlinkNavigationModifierMask)) {
                for (x in 1..4) {
                    fixture.movePointer(x, modifiers = modifiers)
                    assertEquals(Cursor.HAND_CURSOR, fixture.cursorType(), "Move $x within the same prepared occurrence")
                }
            }
            fixture.movePointer(400)
            assertEquals(Cursor.DEFAULT_CURSOR, fixture.cursorType())
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `mouse reporting reconciles stationary links and preserves Shift override`(osc8: Boolean) {
        SwingTerminalHyperlinkLifecycleFixture().use { fixture ->
            fixture.awaitHyperlink()
            if (osc8) fixture.replaceOutput("\u001b]8;id=cursor;https://example.com/osc8\u0007OSC8 link\u001b]8;;\u0007")
            fixture.movePointer(1)
            assertEquals(Cursor.HAND_CURSOR, fixture.cursorType())
            fixture.setMouseReporting(true)
            assertEquals(Cursor.DEFAULT_CURSOR, fixture.cursorType(), "Mode changes suppress a stationary hyperlink")
            fixture.focus(false)
            fixture.focus(true)
            assertEquals(Cursor.DEFAULT_CURSOR, fixture.cursorType(), "Focus return respects application mouse reporting")
            fixture.keyModifier(KeyEvent.VK_SHIFT, pressed = true, modifiers = InputEvent.SHIFT_DOWN_MASK)
            assertEquals(Cursor.HAND_CURSOR, fixture.cursorType(), "Shift restores UI interaction without pointer motion")
            fixture.movePointer(2, modifiers = InputEvent.SHIFT_DOWN_MASK)
            fixture.requestFrame()
            fixture.settle()
            assertEquals(Cursor.HAND_CURSOR, fixture.cursorType(), "Frames preserve the remembered Shift override")
            fixture.keyModifier(KeyEvent.VK_SHIFT, pressed = false, modifiers = 0)
            assertEquals(Cursor.DEFAULT_CURSOR, fixture.cursorType())
            fixture.movePointer(3)
            assertEquals(Cursor.DEFAULT_CURSOR, fixture.cursorType())
            fixture.setMouseReporting(false)
            assertEquals(Cursor.HAND_CURSOR, fixture.cursorType(), "Disabling reporting restores the latest pointer position")
        }
    }

    @Test
    fun `implicit detected link paints ordinary hover and modifier activation across its whole occurrence`() {
        val hoverColor = 0xff668877.toInt()
        val activeColor = 0xffff00ff.toInt()
        val presentation =
            SwingHyperlinkPresentation(
                normal = SwingHyperlinkStyle(underlineStyle = TerminalRenderUnderline.NONE),
                hovered = SwingHyperlinkStyle(underlineArgb = hoverColor, underlineStyle = TerminalRenderUnderline.SINGLE),
                active = SwingHyperlinkStyle(underlineArgb = activeColor, underlineStyle = TerminalRenderUnderline.SINGLE),
            )
        SwingTerminalHyperlinkLifecycleFixture(presentation = presentation, activation = SwingHyperlinkActivation.MODIFIER).use { fixture ->
            fixture.awaitHyperlink()
            fixture.movePointer(400)
            val outside = fixture.firstRowUnderlinePixels()
            assertEquals(0, outside.count { it == activeColor })
            for (x in 1..4) {
                fixture.movePointer(x)
                assertEquals(Cursor.DEFAULT_CURSOR, fixture.cursorType())
            }
            val hovered = fixture.firstRowUnderlinePixels()
            assertTrue(hovered.all { it == hoverColor }, "Ordinary hover must underline the complete published occurrence")
            for (x in 1..4) {
                fixture.movePointer(x, modifiers = hyperlinkNavigationModifierMask)
                assertEquals(Cursor.HAND_CURSOR, fixture.cursorType())
            }
            val active = fixture.firstRowUnderlinePixels()
            assertTrue(active.all { it == activeColor }, "The complete published occurrence must render its active underline")
            val navigationKey = if (hyperlinkNavigationModifierMask == InputEvent.META_DOWN_MASK) KeyEvent.VK_META else KeyEvent.VK_CONTROL
            fixture.keyModifier(navigationKey, pressed = false, modifiers = 0)
            assertEquals(Cursor.DEFAULT_CURSOR, fixture.cursorType())
            assertArrayEquals(
                hovered,
                fixture.firstRowUnderlinePixels(),
                "Releasing the modifier restores ordinary hover without mouse movement",
            )
            fixture.movePointer(400)
            assertArrayEquals(outside, fixture.firstRowUnderlinePixels(), "Leaving the link removes hover decoration")
        }
    }

    @Test
    fun `prompt marker and hyperlink cursor ownership survives entry exit and unchanged marker rows`() {
        val gutter = 16
        SwingTerminalHyperlinkLifecycleFixture(gutterWidth = gutter).use { fixture ->
            fixture.awaitHyperlink()
            fixture.markFirstRowAsPrompt()
            fixture.movePointer(gutter + 1)
            assertEquals(Cursor.HAND_CURSOR, fixture.cursorType())
            fixture.movePointer(gutter + 2)
            assertEquals(Cursor.HAND_CURSOR, fixture.cursorType())
            fixture.movePointer(1)
            assertEquals(Cursor.HAND_CURSOR, fixture.cursorType())
            fixture.movePointer(2)
            assertEquals(Cursor.HAND_CURSOR, fixture.cursorType())
            fixture.setMouseReporting(true)
            assertEquals(Cursor.HAND_CURSOR, fixture.cursorType(), "Prompt gutter retains priority over application mouse reporting")
            fixture.setMouseReporting(false)
            fixture.focus(false)
            assertEquals(Cursor.DEFAULT_CURSOR, fixture.cursorType())
            fixture.focus(true)
            assertEquals(Cursor.HAND_CURSOR, fixture.cursorType())
            fixture.movePointer(gutter + 1)
            assertEquals(Cursor.HAND_CURSOR, fixture.cursorType())
            fixture.movePointer(gutter + 2)
            assertEquals(Cursor.HAND_CURSOR, fixture.cursorType())
            fixture.movePointer(400)
            assertEquals(Cursor.DEFAULT_CURSOR, fixture.cursorType())
        }
    }

    @Test
    fun `detected menu keeps action and copy URI after output replacement and rebinding`() {
        SwingTerminalHyperlinkLifecycleFixture().use { f ->
            f.awaitHyperlink()
            val captured = f.captureHyperlink()
            f.replaceOutput("plain")
            assertFalse(f.observation().hasHyperlink)
            f.unbind()
            f.rebind()
            f.settle()
            assertTrue(f.openCaptured(captured))
            assertEquals(SwingTerminalHyperlinkLifecycleFixture.URL, f.copyCaptured(captured))
            assertEquals(listOf(SwingTerminalHyperlinkLifecycleFixture.URL), f.openedTargets())
        }
    }

    @Test
    fun `OSC8 menu keeps original target after registry reset and replacement`() {
        SwingTerminalHyperlinkLifecycleFixture().use { f ->
            f.awaitHyperlink()
            val original = "https://example.com/original"
            f.replaceOutput("\u001b]8;id=menu;$original\u0007old\u001b]8;;\u0007")
            val captured = f.captureHyperlink()
            f.replaceOutput("\u001bc\u001b]8;id=menu;https://example.com/replaced\u0007new\u001b]8;;\u0007")
            assertTrue(f.openCaptured(captured))
            assertEquals(original, f.copyCaptured(captured))
            assertEquals(listOf(original), f.openedTargets())
        }
    }

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

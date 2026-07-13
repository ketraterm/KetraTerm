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
package io.github.ketraterm.ui.swing.input

import io.github.ketraterm.input.event.TerminalKey
import io.github.ketraterm.input.event.TerminalModifiers
import java.awt.Canvas
import java.awt.event.KeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SwingKeyMapperTest {
    private val source = Canvas()
    private val mapper = SwingKeyMapper()

    @Test
    fun mapsPrintableTypedCharacter() {
        val mapped = mapper.keyTyped(typed('a'))

        assertEquals('a'.code, mapped?.codepoint)
        assertEquals(TerminalModifiers.NONE, mapped?.modifiers)
    }

    @Test
    fun ignoresTypedControlCharacter() {
        assertNull(mapper.keyTyped(typed('\u0003')))
    }

    @Test
    fun mapsTypedSupplementaryCharacterFromSurrogatePair() {
        val chars = Character.toChars(0x1F680)

        assertNull(mapper.keyTyped(typed(chars[0], modifiers = KeyEvent.SHIFT_DOWN_MASK)))
        val mapped = mapper.keyTyped(typed(chars[1]))

        assertEquals(0x1F680, mapped?.codepoint)
        assertEquals(TerminalModifiers.SHIFT, mapped?.modifiers)
    }

    @Test
    fun ignoresTypedLowSurrogateWithoutHighSurrogate() {
        val low = Character.toChars(0x1F680)[1]

        assertNull(mapper.keyTyped(typed(low)))
    }

    @Test
    fun dropsPendingHighSurrogateWhenNextTypedCharacterIsNotLowSurrogate() {
        val high = Character.toChars(0x1F680)[0]

        assertNull(mapper.keyTyped(typed(high)))
        val mapped = mapper.keyTyped(typed('x'))

        assertEquals('x'.code, mapped?.codepoint)
    }

    @Test
    fun mapsArrowPressedKey() {
        val mapped = mapper.keyPressed(pressed(KeyEvent.VK_UP))

        assertEquals(TerminalKey.UP, mapped?.key)
        assertEquals(TerminalModifiers.NONE, mapped?.modifiers)
    }

    @Test
    fun mapsExtendedFunctionPressedKey() {
        val mapped = mapper.keyPressed(pressed(KeyEvent.VK_F24))

        assertEquals(TerminalKey.F24, mapped?.key)
    }

    @Test
    fun mapsNumpadPressedKeyAndSuppressesFollowingTypedCharacter() {
        val mapped = mapper.keyPressed(pressed(KeyEvent.VK_NUMPAD1))

        assertEquals(TerminalKey.NUMPAD_1, mapped?.key)
        assertEquals(TerminalModifiers.NONE, mapped?.modifiers)
        assertNull(mapper.keyTyped(typed('1')))
    }

    @Test
    fun suppressesNumpadTypedCharacterWhenTypedCharacterDiffersFromDefaultEncoding() {
        val mapped = mapper.keyPressed(pressed(KeyEvent.VK_DECIMAL))

        assertEquals(TerminalKey.NUMPAD_DECIMAL, mapped?.key)
        assertNull(mapper.keyTyped(typed(',')))
    }

    @Test
    fun clearsPendingNumpadTypedSuppressionWhenAnotherKeyIsPressedFirst() {
        assertEquals(TerminalKey.NUMPAD_1, mapper.keyPressed(pressed(KeyEvent.VK_NUMPAD1))?.key)
        assertNull(mapper.keyPressed(pressed(KeyEvent.VK_A)))

        val mapped = mapper.keyTyped(typed('a'))

        assertEquals('a'.code, mapped?.codepoint)
    }

    @Test
    fun mapsCtrlLetterAsPrintableShortcut() {
        val mapped =
            mapper.keyPressed(
                pressed(KeyEvent.VK_C, modifiers = KeyEvent.CTRL_DOWN_MASK),
            )

        assertEquals('c'.code, mapped?.codepoint)
        assertEquals(TerminalModifiers.CTRL, mapped?.modifiers)
    }

    @Test
    fun leavesPlainPrintablePressedKeyForTypedEvent() {
        assertNull(mapper.keyPressed(pressed(KeyEvent.VK_A)))
    }

    @Test
    fun ignoresAltGrKeyPressedForPrintableCharacters() {
        val modifiers = KeyEvent.CTRL_DOWN_MASK or KeyEvent.ALT_DOWN_MASK or KeyEvent.ALT_GRAPH_DOWN_MASK
        val mapped = mapper.keyPressed(pressed(KeyEvent.VK_0, modifiers = modifiers))
        assertNull(mapped)
    }

    @Test
    fun mapsAltGrKeyTypedCharacterWithoutCtrlAltModifiers() {
        val modifiers = KeyEvent.CTRL_DOWN_MASK or KeyEvent.ALT_DOWN_MASK or KeyEvent.ALT_GRAPH_DOWN_MASK
        val mapped = mapper.keyTyped(typed('@', modifiers = modifiers))

        assertEquals('@'.code, mapped?.codepoint)
        assertEquals(TerminalModifiers.NONE, mapped?.modifiers)
    }

    @Test
    fun mapsShiftAltGrKeyTypedCharacterWithShiftModifier() {
        val modifiers = KeyEvent.CTRL_DOWN_MASK or KeyEvent.ALT_DOWN_MASK or KeyEvent.ALT_GRAPH_DOWN_MASK or KeyEvent.SHIFT_DOWN_MASK
        val mapped = mapper.keyTyped(typed('Ω', modifiers = modifiers))

        assertEquals('Ω'.code, mapped?.codepoint)
        assertEquals(TerminalModifiers.SHIFT, mapped?.modifiers)
    }

    private fun typed(
        char: Char,
        modifiers: Int = 0,
    ): KeyEvent =
        KeyEvent(
            source,
            KeyEvent.KEY_TYPED,
            System.currentTimeMillis(),
            modifiers,
            KeyEvent.VK_UNDEFINED,
            char,
        )

    private fun pressed(
        keyCode: Int,
        modifiers: Int = 0,
    ): KeyEvent =
        KeyEvent(
            source,
            KeyEvent.KEY_PRESSED,
            System.currentTimeMillis(),
            modifiers,
            keyCode,
            KeyEvent.CHAR_UNDEFINED,
        )
}

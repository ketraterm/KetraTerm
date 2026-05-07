package com.gagik.terminal.ui.swing.input

import com.gagik.terminal.input.event.TerminalKey
import com.gagik.terminal.input.event.TerminalModifiers
import java.awt.Canvas
import java.awt.event.KeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TerminalSwingKeyMapperTest {
    private val source = Canvas()

    @Test
    fun mapsPrintableTypedCharacter() {
        val mapped = TerminalSwingKeyMapper.keyTyped(typed('a'))

        assertEquals('a'.code, mapped?.codepoint)
        assertEquals(TerminalModifiers.NONE, mapped?.modifiers)
    }

    @Test
    fun ignoresTypedControlCharacter() {
        assertNull(TerminalSwingKeyMapper.keyTyped(typed('\u0003')))
    }

    @Test
    fun mapsArrowPressedKey() {
        val mapped = TerminalSwingKeyMapper.keyPressed(pressed(KeyEvent.VK_UP))

        assertEquals(TerminalKey.UP, mapped?.key)
        assertEquals(TerminalModifiers.NONE, mapped?.modifiers)
    }

    @Test
    fun mapsCtrlLetterAsPrintableShortcut() {
        val mapped = TerminalSwingKeyMapper.keyPressed(
            pressed(KeyEvent.VK_C, modifiers = KeyEvent.CTRL_DOWN_MASK),
        )

        assertEquals('c'.code, mapped?.codepoint)
        assertEquals(TerminalModifiers.CTRL, mapped?.modifiers)
    }

    @Test
    fun leavesPlainPrintablePressedKeyForTypedEvent() {
        assertNull(TerminalSwingKeyMapper.keyPressed(pressed(KeyEvent.VK_A)))
    }

    private fun typed(char: Char): KeyEvent {
        return KeyEvent(
            source,
            KeyEvent.KEY_TYPED,
            System.currentTimeMillis(),
            0,
            KeyEvent.VK_UNDEFINED,
            char,
        )
    }

    private fun pressed(keyCode: Int, modifiers: Int = 0): KeyEvent {
        return KeyEvent(
            source,
            KeyEvent.KEY_PRESSED,
            System.currentTimeMillis(),
            modifiers,
            keyCode,
            KeyEvent.CHAR_UNDEFINED,
        )
    }
}

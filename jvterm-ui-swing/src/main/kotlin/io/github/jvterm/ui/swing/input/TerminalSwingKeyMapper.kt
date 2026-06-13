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
package io.github.jvterm.ui.swing.input

import io.github.jvterm.input.event.TerminalKey
import io.github.jvterm.input.event.TerminalKeyEvent
import io.github.jvterm.input.event.TerminalModifiers
import java.awt.event.KeyEvent

/**
 * Maps AWT keyboard events to terminal input events.
 *
 * Printable text is emitted from `KEY_TYPED` events. Navigation, function, and
 * control/meta modified printable keys are emitted from `KEY_PRESSED` events.
 * Numeric keypad keys are emitted from `KEY_PRESSED` so application-keypad mode
 * can be applied by the input encoder; their follow-up `KEY_TYPED` event is
 * suppressed to avoid emitting the normal-mode character twice.
 */
internal class TerminalSwingKeyMapper {
    private var pendingHighSurrogate: Char = NO_PENDING_SURROGATE
    private var pendingHighSurrogateModifiers: Int = TerminalModifiers.NONE
    private var suppressNextKeypadTyped: Boolean = false

    /**
     * Converts a `KEY_PRESSED` event to terminal input when the event should
     * not be handled by the later `KEY_TYPED` event.
     *
     * @param event Swing key event.
     * @return terminal key event, or `null` when input should come from
     * `KEY_TYPED`.
     */
    fun keyPressed(event: KeyEvent): TerminalKeyEvent? {
        val modifiers = modifiers(event)
        val key = terminalKey(event.keyCode)
        if (key != null) {
            suppressNextKeypadTyped = isPrintableKeypadKey(key)
            return TerminalKeyEvent.key(key, modifiers)
        }

        suppressNextKeypadTyped = false
        if (!TerminalModifiers.hasCtrl(modifiers) && !TerminalModifiers.hasMeta(modifiers)) {
            return null
        }

        val codepoint = controlShortcutCodepoint(event) ?: return null
        return TerminalKeyEvent.codepoint(codepoint, modifiers)
    }

    /**
     * Converts a `KEY_TYPED` event to printable terminal input.
     *
     * @param event Swing key event.
     * @return printable terminal key event, or `null` for control and undefined
     * characters.
     */
    fun keyTyped(event: KeyEvent): TerminalKeyEvent? {
        val char = event.keyChar
        if (char == KeyEvent.CHAR_UNDEFINED) return null

        val eventModifiers = modifiers(event)
        val codepoint = typedCodepoint(char, eventModifiers) ?: return null
        if (suppressNextKeypadTyped) {
            suppressNextKeypadTyped = false
            pendingHighSurrogate = NO_PENDING_SURROGATE
            pendingHighSurrogateModifiers = TerminalModifiers.NONE
            return null
        }
        if (codepoint in 0x00..0x1f || codepoint == 0x7f) return null
        return TerminalKeyEvent.codepoint(codepoint, eventModifiersFor(codepoint, eventModifiers))
    }

    private fun typedCodepoint(
        char: Char,
        eventModifiers: Int,
    ): Int? {
        if (char.isHighSurrogate()) {
            pendingHighSurrogate = char
            pendingHighSurrogateModifiers = eventModifiers
            return null
        }

        if (char.isLowSurrogate()) {
            val high = pendingHighSurrogate
            if (high == NO_PENDING_SURROGATE) return null

            pendingHighSurrogate = NO_PENDING_SURROGATE
            return Character.toCodePoint(high, char)
        }

        pendingHighSurrogate = NO_PENDING_SURROGATE
        return char.code
    }

    private fun eventModifiersFor(
        codepoint: Int,
        currentModifiers: Int,
    ): Int {
        if (codepoint <= 0xFFFF) return currentModifiers

        val modifiers = pendingHighSurrogateModifiers
        pendingHighSurrogateModifiers = TerminalModifiers.NONE
        return modifiers
    }

    private fun modifiers(event: KeyEvent): Int {
        var modifiers = TerminalModifiers.NONE
        if (event.isShiftDown) modifiers = modifiers or TerminalModifiers.SHIFT
        if (event.isAltDown || event.isAltGraphDown) modifiers = modifiers or TerminalModifiers.ALT
        if (event.isControlDown) modifiers = modifiers or TerminalModifiers.CTRL
        if (event.isMetaDown) modifiers = modifiers or TerminalModifiers.META
        return modifiers
    }

    private fun terminalKey(keyCode: Int): TerminalKey? =
        when (keyCode) {
            KeyEvent.VK_UP -> TerminalKey.UP
            KeyEvent.VK_DOWN -> TerminalKey.DOWN
            KeyEvent.VK_LEFT -> TerminalKey.LEFT
            KeyEvent.VK_RIGHT -> TerminalKey.RIGHT
            KeyEvent.VK_HOME -> TerminalKey.HOME
            KeyEvent.VK_END -> TerminalKey.END
            KeyEvent.VK_PAGE_UP -> TerminalKey.PAGE_UP
            KeyEvent.VK_PAGE_DOWN -> TerminalKey.PAGE_DOWN
            KeyEvent.VK_INSERT -> TerminalKey.INSERT
            KeyEvent.VK_DELETE -> TerminalKey.DELETE
            KeyEvent.VK_BACK_SPACE -> TerminalKey.BACKSPACE
            KeyEvent.VK_ENTER -> TerminalKey.ENTER
            KeyEvent.VK_TAB -> TerminalKey.TAB
            KeyEvent.VK_ESCAPE -> TerminalKey.ESCAPE
            KeyEvent.VK_F1 -> TerminalKey.F1
            KeyEvent.VK_F2 -> TerminalKey.F2
            KeyEvent.VK_F3 -> TerminalKey.F3
            KeyEvent.VK_F4 -> TerminalKey.F4
            KeyEvent.VK_F5 -> TerminalKey.F5
            KeyEvent.VK_F6 -> TerminalKey.F6
            KeyEvent.VK_F7 -> TerminalKey.F7
            KeyEvent.VK_F8 -> TerminalKey.F8
            KeyEvent.VK_F9 -> TerminalKey.F9
            KeyEvent.VK_F10 -> TerminalKey.F10
            KeyEvent.VK_F11 -> TerminalKey.F11
            KeyEvent.VK_F12 -> TerminalKey.F12
            KeyEvent.VK_NUMPAD0 -> TerminalKey.NUMPAD_0
            KeyEvent.VK_NUMPAD1 -> TerminalKey.NUMPAD_1
            KeyEvent.VK_NUMPAD2 -> TerminalKey.NUMPAD_2
            KeyEvent.VK_NUMPAD3 -> TerminalKey.NUMPAD_3
            KeyEvent.VK_NUMPAD4 -> TerminalKey.NUMPAD_4
            KeyEvent.VK_NUMPAD5 -> TerminalKey.NUMPAD_5
            KeyEvent.VK_NUMPAD6 -> TerminalKey.NUMPAD_6
            KeyEvent.VK_NUMPAD7 -> TerminalKey.NUMPAD_7
            KeyEvent.VK_NUMPAD8 -> TerminalKey.NUMPAD_8
            KeyEvent.VK_NUMPAD9 -> TerminalKey.NUMPAD_9
            KeyEvent.VK_DECIMAL -> TerminalKey.NUMPAD_DECIMAL
            KeyEvent.VK_DIVIDE -> TerminalKey.NUMPAD_DIVIDE
            KeyEvent.VK_MULTIPLY -> TerminalKey.NUMPAD_MULTIPLY
            KeyEvent.VK_SUBTRACT -> TerminalKey.NUMPAD_SUBTRACT
            KeyEvent.VK_ADD -> TerminalKey.NUMPAD_ADD
            else -> null
        }

    private fun isPrintableKeypadKey(key: TerminalKey): Boolean =
        when (key) {
            TerminalKey.NUMPAD_SPACE,
            TerminalKey.NUMPAD_TAB,
            TerminalKey.NUMPAD_DIVIDE,
            TerminalKey.NUMPAD_MULTIPLY,
            TerminalKey.NUMPAD_SUBTRACT,
            TerminalKey.NUMPAD_ADD,
            TerminalKey.NUMPAD_COMMA,
            TerminalKey.NUMPAD_SEPARATOR,
            TerminalKey.NUMPAD_EQUALS,
            TerminalKey.NUMPAD_BEGIN,
            TerminalKey.NUMPAD_DECIMAL,
            TerminalKey.NUMPAD_0,
            TerminalKey.NUMPAD_1,
            TerminalKey.NUMPAD_2,
            TerminalKey.NUMPAD_3,
            TerminalKey.NUMPAD_4,
            TerminalKey.NUMPAD_5,
            TerminalKey.NUMPAD_6,
            TerminalKey.NUMPAD_7,
            TerminalKey.NUMPAD_8,
            TerminalKey.NUMPAD_9,
            -> true

            else -> false
        }

    private fun controlShortcutCodepoint(event: KeyEvent): Int? {
        val keyCode = event.keyCode
        if (keyCode in KeyEvent.VK_A..KeyEvent.VK_Z) {
            return 'a'.code + keyCode - KeyEvent.VK_A
        }
        if (keyCode in KeyEvent.VK_0..KeyEvent.VK_9 && keyCode != KeyEvent.VK_6) {
            return '0'.code + keyCode - KeyEvent.VK_0
        }

        return when (keyCode) {
            KeyEvent.VK_SPACE -> ' '.code
            KeyEvent.VK_OPEN_BRACKET -> '['.code
            KeyEvent.VK_BACK_SLASH -> '\\'.code
            KeyEvent.VK_CLOSE_BRACKET -> ']'.code
            KeyEvent.VK_6 -> '^'.code
            KeyEvent.VK_MINUS -> '_'.code
            KeyEvent.VK_SLASH -> '?'.code
            else -> null
        }
    }

    private companion object {
        private const val NO_PENDING_SURROGATE: Char = '\u0000'
    }
}

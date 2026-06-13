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
package io.github.jvterm.input.impl

import io.github.jvterm.core.api.TerminalModeBits
import io.github.jvterm.input.event.TerminalKey
import io.github.jvterm.input.event.TerminalKeyEvent
import io.github.jvterm.input.event.TerminalModifiers
import io.github.jvterm.input.impl.keyboard.KeyboardEncoder
import io.github.jvterm.input.policy.*
import io.github.jvterm.protocol.host.TerminalHostOutput
import io.github.jvterm.protocol.keyboard.FormatOtherKeysMode
import io.github.jvterm.protocol.keyboard.KittyKeyboardProgressiveFlag
import io.github.jvterm.protocol.keyboard.ModifyOtherKeysMode
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class KeyboardEncoderTest {
    @Test
    fun `encodes printable UTF-8 codepoints`() {
        assertBytes(bytes(0x61), TerminalKeyEvent.codepoint('a'.code))
        assertBytes(bytes(0xc3, 0xa9), TerminalKeyEvent.codepoint(0x00e9))
        assertBytes(bytes(0xf0, 0x9f, 0x98, 0x80), TerminalKeyEvent.codepoint(0x1f600))
    }

    @Test
    fun `encodes UTF-8 boundary printable codepoints`() {
        assertBytes(bytes(0xc2, 0x80), TerminalKeyEvent.codepoint(0x0080))
        assertBytes(bytes(0xdf, 0xbf), TerminalKeyEvent.codepoint(0x07ff))
        assertBytes(bytes(0xe0, 0xa0, 0x80), TerminalKeyEvent.codepoint(0x0800))
        assertBytes(bytes(0xef, 0xbf, 0xbf), TerminalKeyEvent.codepoint(0xffff))
        assertBytes(bytes(0xf0, 0x90, 0x80, 0x80), TerminalKeyEvent.codepoint(0x10000))
        assertBytes(bytes(0xf4, 0x8f, 0xbf, 0xbf), TerminalKeyEvent.codepoint(0x10ffff))
    }

    @Test
    fun `encodes Meta printable input by policy`() {
        assertBytes(bytes(0x1b, 0x61), TerminalKeyEvent.codepoint('a'.code, TerminalModifiers.META))
        assertBytes(
            expected = bytes(0x61),
            event = TerminalKeyEvent.codepoint('a'.code, TerminalModifiers.META),
            policy = TerminalInputPolicy(metaKeyPolicy = MetaKeyPolicy.IGNORE_META),
        )
        assertBytes(
            expected = bytes(),
            event = TerminalKeyEvent.codepoint('a'.code, TerminalModifiers.META),
            policy = TerminalInputPolicy(metaKeyPolicy = MetaKeyPolicy.SUPPRESS_EVENT),
        )
        assertBytes(
            expected = bytes(0x1b, 0x61),
            event = TerminalKeyEvent.codepoint('a'.code, TerminalModifiers.ALT),
        )
        assertBytes(
            expected = bytes(0x1b, 0x61),
            event =
                TerminalKeyEvent.codepoint(
                    'a'.code,
                    TerminalModifiers.ALT or TerminalModifiers.META,
                ),
        )
    }

    @Test
    fun `encodes Ctrl printable combinations only when mappable`() {
        assertBytes(bytes(0x01), TerminalKeyEvent.codepoint('a'.code, TerminalModifiers.CTRL))
        assertBytes(bytes(0x1a), TerminalKeyEvent.codepoint('z'.code, TerminalModifiers.CTRL))
        assertBytes(bytes(0x00), TerminalKeyEvent.codepoint('@'.code, TerminalModifiers.CTRL))
        assertBytes(bytes(0x00), TerminalKeyEvent.codepoint(' '.code, TerminalModifiers.CTRL))
        assertBytes(bytes(0x1b), TerminalKeyEvent.codepoint('['.code, TerminalModifiers.CTRL))
        assertBytes(bytes(0x1c), TerminalKeyEvent.codepoint('\\'.code, TerminalModifiers.CTRL))
        assertBytes(bytes(0x1d), TerminalKeyEvent.codepoint(']'.code, TerminalModifiers.CTRL))
        assertBytes(bytes(0x1e), TerminalKeyEvent.codepoint('^'.code, TerminalModifiers.CTRL))
        assertBytes(bytes(0x1f), TerminalKeyEvent.codepoint('_'.code, TerminalModifiers.CTRL))
        assertBytes(bytes(0x7f), TerminalKeyEvent.codepoint('?'.code, TerminalModifiers.CTRL))
        assertBytes(
            bytes(0x1b, 0x01),
            TerminalKeyEvent.codepoint('a'.code, TerminalModifiers.CTRL or TerminalModifiers.ALT),
        )
    }

    @Test
    fun `applies unsupported modified printable policy`() {
        assertBytes(bytes(), TerminalKeyEvent.codepoint(0x00e9, TerminalModifiers.CTRL))
        assertBytes(
            expected = bytes(0xc3, 0xa9),
            event = TerminalKeyEvent.codepoint(0x00e9, TerminalModifiers.CTRL),
            policy =
                TerminalInputPolicy(
                    unsupportedModifiedKeyPolicy = UnsupportedModifiedKeyPolicy.EMIT_UNMODIFIED,
                ),
        )
        assertBytes(
            expected = bytes(0x1b, 0xc3, 0xa9),
            event =
                TerminalKeyEvent.codepoint(
                    0x00e9,
                    TerminalModifiers.CTRL or TerminalModifiers.ALT,
                ),
            policy =
                TerminalInputPolicy(
                    unsupportedModifiedKeyPolicy = UnsupportedModifiedKeyPolicy.EMIT_UNMODIFIED,
                ),
        )
    }

    @Test
    fun `modifyOtherKeys mode 1 encodes ordinary keys legacy cannot represent`() {
        val bits = modifyOtherKeysBits(ModifyOtherKeysMode.MODE_1)

        assertBytes(
            expected = esc("[27;5;233~"),
            event = TerminalKeyEvent.codepoint(0x00e9, TerminalModifiers.CTRL),
            modeBits = bits,
        )
        assertBytes(
            expected = esc("[27;4;97~"),
            event =
                TerminalKeyEvent.codepoint(
                    'a'.code,
                    TerminalModifiers.SHIFT or TerminalModifiers.ALT,
                ),
            modeBits = bits,
        )
        assertBytes(
            expected = bytes(0x09),
            event = TerminalKeyEvent.codepoint('i'.code, TerminalModifiers.CTRL),
            modeBits = bits,
        )
        assertBytes(
            expected = esc("[27;3;233~"),
            event = TerminalKeyEvent.codepoint(0x00e9, TerminalModifiers.ALT),
            modeBits = bits,
        )
    }

    @Test
    fun `modifyOtherKeys mode 2 encodes all modified ordinary keys`() {
        val bits = modifyOtherKeysBits(ModifyOtherKeysMode.MODE_2)

        assertBytes(
            expected = bytes(0x61),
            event = TerminalKeyEvent.codepoint('a'.code),
            modeBits = bits,
        )
        assertBytes(
            expected = esc("[27;3;233~"),
            event = TerminalKeyEvent.codepoint(0x00e9, TerminalModifiers.ALT),
            modeBits = bits,
        )
        assertBytes(
            expected = esc("[27;5;233~"),
            event = TerminalKeyEvent.codepoint(0x00e9, TerminalModifiers.CTRL),
            modeBits = bits,
        )
        assertBytes(
            expected = esc("[27;4;97~"),
            event =
                TerminalKeyEvent.codepoint(
                    'a'.code,
                    TerminalModifiers.SHIFT or TerminalModifiers.ALT,
                ),
            modeBits = bits,
        )
        assertBytes(
            expected = esc("[27;9;97~"),
            event = TerminalKeyEvent.codepoint('a'.code, TerminalModifiers.META),
            modeBits = bits,
            policy = TerminalInputPolicy(metaKeyPolicy = MetaKeyPolicy.SUPPRESS_EVENT),
        )
    }

    @Test
    fun `modifyOtherKeys mode 2 distinguishes control-equivalent ordinary keys`() {
        val bits = modifyOtherKeysBits(ModifyOtherKeysMode.MODE_2)

        assertBytes(
            expected = esc("[27;5;105~"),
            event = TerminalKeyEvent.codepoint('i'.code, TerminalModifiers.CTRL),
            modeBits = bits,
        )
        assertBytes(
            expected = esc("[27;5;109~"),
            event = TerminalKeyEvent.codepoint('m'.code, TerminalModifiers.CTRL),
            modeBits = bits,
        )
    }

    @Test
    fun `encodes Backspace by policy`() {
        assertBytes(bytes(0x7f), TerminalKeyEvent.key(TerminalKey.BACKSPACE))
        assertBytes(
            expected = bytes(0x08),
            event = TerminalKeyEvent.key(TerminalKey.BACKSPACE),
            policy = TerminalInputPolicy(backspacePolicy = BackspacePolicy.BACKSPACE),
        )
        assertBytes(bytes(0x1b, 0x7f), TerminalKeyEvent.key(TerminalKey.BACKSPACE, TerminalModifiers.ALT))
        assertBytes(bytes(0x08), TerminalKeyEvent.key(TerminalKey.BACKSPACE, TerminalModifiers.CTRL))
        assertBytes(
            expected = bytes(0x7f),
            event = TerminalKeyEvent.key(TerminalKey.BACKSPACE, TerminalModifiers.CTRL),
            policy = TerminalInputPolicy(backspacePolicy = BackspacePolicy.BACKSPACE),
        )
        assertBytes(
            expected = bytes(),
            event = TerminalKeyEvent.key(TerminalKey.BACKSPACE, TerminalModifiers.META),
            policy = TerminalInputPolicy(metaKeyPolicy = MetaKeyPolicy.SUPPRESS_EVENT),
        )
    }

    @Test
    fun `encodes Enter by mode and modifier policy`() {
        assertBytes(bytes(0x0d), TerminalKeyEvent.key(TerminalKey.ENTER))
        assertBytes(bytes(0x0d, 0x0a), TerminalKeyEvent.key(TerminalKey.ENTER), TerminalModeBits.NEW_LINE_MODE)
        assertBytes(
            expected = bytes(0x0d),
            event = TerminalKeyEvent.key(TerminalKey.ENTER),
            modeBits = TerminalModeBits.NEW_LINE_MODE,
            policy = TerminalInputPolicy(enterNewLineModePolicy = EnterNewLineModePolicy.SEND_CR),
        )
        assertBytes(bytes(0x1b, 0x0d), TerminalKeyEvent.key(TerminalKey.ENTER, TerminalModifiers.ALT))
        assertBytes(bytes(), TerminalKeyEvent.key(TerminalKey.ENTER, TerminalModifiers.CTRL))
        assertBytes(
            expected = bytes(0x0d),
            event = TerminalKeyEvent.key(TerminalKey.ENTER, TerminalModifiers.CTRL),
            policy =
                TerminalInputPolicy(
                    unsupportedModifiedKeyPolicy = UnsupportedModifiedKeyPolicy.EMIT_UNMODIFIED,
                ),
        )
    }

    @Test
    fun `modifyOtherKeys mode 2 encodes modified Enter`() {
        val bits = modifyOtherKeysBits(ModifyOtherKeysMode.MODE_2)

        assertBytes(
            expected = esc("[27;5;13~"),
            event = TerminalKeyEvent.key(TerminalKey.ENTER, TerminalModifiers.CTRL),
            modeBits = bits,
        )
        assertBytes(
            expected = esc("[27;2;13~"),
            event = TerminalKeyEvent.key(TerminalKey.ENTER, TerminalModifiers.SHIFT),
            modeBits = bits,
        )
    }

    @Test
    fun `modifyOtherKeys mode 1 encodes Alt control-equivalent keys`() {
        val bits = modifyOtherKeysBits(ModifyOtherKeysMode.MODE_1)

        assertBytes(
            expected = esc("[27;3;9~"),
            event = TerminalKeyEvent.key(TerminalKey.TAB, TerminalModifiers.ALT),
            modeBits = bits,
        )
        assertBytes(
            expected = esc("[27;3;13~"),
            event = TerminalKeyEvent.key(TerminalKey.ENTER, TerminalModifiers.ALT),
            modeBits = bits,
        )
        assertBytes(
            expected = esc("[27;3;8~"),
            event = TerminalKeyEvent.key(TerminalKey.BACKSPACE, TerminalModifiers.ALT),
            modeBits = bits,
        )
        assertBytes(
            expected = esc("[27;3;27~"),
            event = TerminalKeyEvent.key(TerminalKey.ESCAPE, TerminalModifiers.ALT),
            modeBits = bits,
        )
    }

    @Test
    fun `encodes Escape by modifier policy`() {
        assertBytes(bytes(0x1b), TerminalKeyEvent.key(TerminalKey.ESCAPE))
        assertBytes(bytes(0x1b, 0x1b), TerminalKeyEvent.key(TerminalKey.ESCAPE, TerminalModifiers.ALT))
        assertBytes(bytes(), TerminalKeyEvent.key(TerminalKey.ESCAPE, TerminalModifiers.SHIFT))
        assertBytes(
            expected = bytes(0x1b),
            event = TerminalKeyEvent.key(TerminalKey.ESCAPE, TerminalModifiers.SHIFT),
            policy =
                TerminalInputPolicy(
                    unsupportedModifiedKeyPolicy = UnsupportedModifiedKeyPolicy.EMIT_UNMODIFIED,
                ),
        )
    }

    @Test
    fun `encodes Tab variants explicitly`() {
        assertBytes(bytes(0x09), TerminalKeyEvent.key(TerminalKey.TAB))
        assertBytes(esc("[Z"), TerminalKeyEvent.key(TerminalKey.TAB, TerminalModifiers.SHIFT))
        assertBytes(esc("[1;5Z"), TerminalKeyEvent.key(TerminalKey.TAB, TerminalModifiers.CTRL))
        assertBytes(esc("[1;9Z"), TerminalKeyEvent.key(TerminalKey.TAB, TerminalModifiers.META))
    }

    @Test
    fun `modifyOtherKeys mode 2 encodes modified Tab`() {
        val bits = modifyOtherKeysBits(ModifyOtherKeysMode.MODE_2)

        assertBytes(
            expected = esc("[27;5;9~"),
            event = TerminalKeyEvent.key(TerminalKey.TAB, TerminalModifiers.CTRL),
            modeBits = bits,
        )
        assertBytes(
            expected = esc("[27;2;9~"),
            event = TerminalKeyEvent.key(TerminalKey.TAB, TerminalModifiers.SHIFT),
            modeBits = bits,
        )
    }

    @Test
    fun `modifyOtherKeys mode 2 encodes modified Backspace and Escape`() {
        val bits = modifyOtherKeysBits(ModifyOtherKeysMode.MODE_2)

        assertBytes(
            expected = esc("[27;5;8~"),
            event = TerminalKeyEvent.key(TerminalKey.BACKSPACE, TerminalModifiers.CTRL),
            modeBits = bits,
        )
        assertBytes(
            expected = esc("[27;2;27~"),
            event = TerminalKeyEvent.key(TerminalKey.ESCAPE, TerminalModifiers.SHIFT),
            modeBits = bits,
        )
    }

    @Test
    fun `formatOtherKeys CSI-u changes modifyOtherKeys wire shape`() {
        val bits =
            modifyOtherKeysBits(ModifyOtherKeysMode.MODE_2) or
                formatOtherKeysBits(FormatOtherKeysMode.CSI_U)

        assertBytes(
            expected = esc("[233;5u"),
            event = TerminalKeyEvent.codepoint(0x00e9, TerminalModifiers.CTRL),
            modeBits = bits,
        )
        assertBytes(
            expected = esc("[9;3u"),
            event = TerminalKeyEvent.key(TerminalKey.TAB, TerminalModifiers.ALT),
            modeBits = bits,
        )
    }

    @Test
    fun `modifyOtherKeys mode 3 encodes ordinary keys without modifiers`() {
        val legacyBits = modifyOtherKeysBits(ModifyOtherKeysMode.MODE_3)
        val csiUBits = legacyBits or formatOtherKeysBits(FormatOtherKeysMode.CSI_U)

        assertBytes(
            expected = esc("[27;1;32~"),
            event = TerminalKeyEvent.codepoint(' '.code),
            modeBits = legacyBits,
        )
        assertBytes(
            expected = esc("[32;1u"),
            event = TerminalKeyEvent.codepoint(' '.code),
            modeBits = csiUBits,
        )
        assertBytes(
            expected = esc("[13;1u"),
            event = TerminalKeyEvent.key(TerminalKey.ENTER),
            modeBits = csiUBits,
        )
    }

    @Test
    fun `encodes cursor keys in normal and application modes`() {
        assertBytes(esc("[A"), TerminalKeyEvent.key(TerminalKey.UP))
        assertBytes(esc("[B"), TerminalKeyEvent.key(TerminalKey.DOWN))
        assertBytes(esc("[C"), TerminalKeyEvent.key(TerminalKey.RIGHT))
        assertBytes(esc("[D"), TerminalKeyEvent.key(TerminalKey.LEFT))

        assertBytes(esc("OA"), TerminalKeyEvent.key(TerminalKey.UP), TerminalModeBits.APPLICATION_CURSOR_KEYS)
        assertBytes(esc("OB"), TerminalKeyEvent.key(TerminalKey.DOWN), TerminalModeBits.APPLICATION_CURSOR_KEYS)
        assertBytes(esc("OC"), TerminalKeyEvent.key(TerminalKey.RIGHT), TerminalModeBits.APPLICATION_CURSOR_KEYS)
        assertBytes(esc("OD"), TerminalKeyEvent.key(TerminalKey.LEFT), TerminalModeBits.APPLICATION_CURSOR_KEYS)
    }

    @Test
    fun `encodes modified cursor keys as CSI modifier finals`() {
        assertBytes(esc("[1;5A"), TerminalKeyEvent.key(TerminalKey.UP, TerminalModifiers.CTRL))
        assertBytes(esc("[1;4A"), TerminalKeyEvent.key(TerminalKey.UP, TerminalModifiers.SHIFT or TerminalModifiers.ALT))
        assertBytes(esc("[1;9A"), TerminalKeyEvent.key(TerminalKey.UP, TerminalModifiers.META))
        assertBytes(esc("[1;10A"), TerminalKeyEvent.key(TerminalKey.UP, TerminalModifiers.SHIFT or TerminalModifiers.META))
    }

    @Test
    fun `encodes home and end in normal and application modes`() {
        assertBytes(esc("[H"), TerminalKeyEvent.key(TerminalKey.HOME))
        assertBytes(esc("[F"), TerminalKeyEvent.key(TerminalKey.END))
        assertBytes(esc("OH"), TerminalKeyEvent.key(TerminalKey.HOME), TerminalModeBits.APPLICATION_CURSOR_KEYS)
        assertBytes(esc("OF"), TerminalKeyEvent.key(TerminalKey.END), TerminalModeBits.APPLICATION_CURSOR_KEYS)
    }

    @Test
    fun `encodes modified home and end keys as CSI modifier finals`() {
        assertBytes(esc("[1;5H"), TerminalKeyEvent.key(TerminalKey.HOME, TerminalModifiers.CTRL))
        assertBytes(esc("[1;4F"), TerminalKeyEvent.key(TerminalKey.END, TerminalModifiers.SHIFT or TerminalModifiers.ALT))
        assertBytes(esc("[1;9H"), TerminalKeyEvent.key(TerminalKey.HOME, TerminalModifiers.META))
    }

    @Test
    fun `encodes tilde navigation keys with optional modifiers`() {
        assertBytes(esc("[2~"), TerminalKeyEvent.key(TerminalKey.INSERT))
        assertBytes(esc("[3~"), TerminalKeyEvent.key(TerminalKey.DELETE))
        assertBytes(esc("[5~"), TerminalKeyEvent.key(TerminalKey.PAGE_UP))
        assertBytes(esc("[6~"), TerminalKeyEvent.key(TerminalKey.PAGE_DOWN))
        assertBytes(esc("[3;5~"), TerminalKeyEvent.key(TerminalKey.DELETE, TerminalModifiers.CTRL))
    }

    @Test
    fun `encodes function keys`() {
        assertBytes(esc("OP"), TerminalKeyEvent.key(TerminalKey.F1))
        assertBytes(esc("OQ"), TerminalKeyEvent.key(TerminalKey.F2))
        assertBytes(esc("OR"), TerminalKeyEvent.key(TerminalKey.F3))
        assertBytes(esc("OS"), TerminalKeyEvent.key(TerminalKey.F4))
        assertBytes(esc("[15~"), TerminalKeyEvent.key(TerminalKey.F5))
        assertBytes(esc("[17~"), TerminalKeyEvent.key(TerminalKey.F6))
        assertBytes(esc("[18~"), TerminalKeyEvent.key(TerminalKey.F7))
        assertBytes(esc("[19~"), TerminalKeyEvent.key(TerminalKey.F8))
        assertBytes(esc("[20~"), TerminalKeyEvent.key(TerminalKey.F9))
        assertBytes(esc("[21~"), TerminalKeyEvent.key(TerminalKey.F10))
        assertBytes(esc("[23~"), TerminalKeyEvent.key(TerminalKey.F11))
        assertBytes(esc("[24~"), TerminalKeyEvent.key(TerminalKey.F12))
        assertBytes(esc("[1;5P"), TerminalKeyEvent.key(TerminalKey.F1, TerminalModifiers.CTRL))
        assertBytes(esc("[15;5~"), TerminalKeyEvent.key(TerminalKey.F5, TerminalModifiers.CTRL))
        assertBytes(esc("[24;9~"), TerminalKeyEvent.key(TerminalKey.F12, TerminalModifiers.META))
    }

    @Test
    fun `encodes PF keypad keys independently from physical function keys`() {
        assertBytes(esc("OP"), TerminalKeyEvent.key(TerminalKey.PF1))
        assertBytes(esc("OQ"), TerminalKeyEvent.key(TerminalKey.PF2))
        assertBytes(esc("OR"), TerminalKeyEvent.key(TerminalKey.PF3))
        assertBytes(esc("OS"), TerminalKeyEvent.key(TerminalKey.PF4))
        assertBytes(esc("[1;5P"), TerminalKeyEvent.key(TerminalKey.PF1, TerminalModifiers.CTRL))
    }

    @Test
    fun `encodes each normal keypad key`() {
        assertBytes(ascii(" "), TerminalKeyEvent.key(TerminalKey.NUMPAD_SPACE))
        assertBytes(bytes(0x09), TerminalKeyEvent.key(TerminalKey.NUMPAD_TAB))
        assertBytes(ascii("0"), TerminalKeyEvent.key(TerminalKey.NUMPAD_0))
        assertBytes(ascii("1"), TerminalKeyEvent.key(TerminalKey.NUMPAD_1))
        assertBytes(ascii("2"), TerminalKeyEvent.key(TerminalKey.NUMPAD_2))
        assertBytes(ascii("3"), TerminalKeyEvent.key(TerminalKey.NUMPAD_3))
        assertBytes(ascii("4"), TerminalKeyEvent.key(TerminalKey.NUMPAD_4))
        assertBytes(ascii("5"), TerminalKeyEvent.key(TerminalKey.NUMPAD_5))
        assertBytes(ascii("6"), TerminalKeyEvent.key(TerminalKey.NUMPAD_6))
        assertBytes(ascii("7"), TerminalKeyEvent.key(TerminalKey.NUMPAD_7))
        assertBytes(ascii("8"), TerminalKeyEvent.key(TerminalKey.NUMPAD_8))
        assertBytes(ascii("9"), TerminalKeyEvent.key(TerminalKey.NUMPAD_9))
        assertBytes(ascii("."), TerminalKeyEvent.key(TerminalKey.NUMPAD_DECIMAL))
        assertBytes(ascii("/"), TerminalKeyEvent.key(TerminalKey.NUMPAD_DIVIDE))
        assertBytes(ascii("*"), TerminalKeyEvent.key(TerminalKey.NUMPAD_MULTIPLY))
        assertBytes(ascii("-"), TerminalKeyEvent.key(TerminalKey.NUMPAD_SUBTRACT))
        assertBytes(ascii("+"), TerminalKeyEvent.key(TerminalKey.NUMPAD_ADD))
        assertBytes(ascii(","), TerminalKeyEvent.key(TerminalKey.NUMPAD_COMMA))
        assertBytes(ascii(","), TerminalKeyEvent.key(TerminalKey.NUMPAD_SEPARATOR))
        assertBytes(ascii("="), TerminalKeyEvent.key(TerminalKey.NUMPAD_EQUALS))
        assertBytes(ascii("5"), TerminalKeyEvent.key(TerminalKey.NUMPAD_BEGIN))
        assertBytes(bytes(0x0d), TerminalKeyEvent.key(TerminalKey.NUMPAD_ENTER))
        assertBytes(bytes(0x0d, 0x0a), TerminalKeyEvent.key(TerminalKey.NUMPAD_ENTER), TerminalModeBits.NEW_LINE_MODE)
    }

    @Test
    fun `encodes application keypad keys`() {
        val bits = TerminalModeBits.APPLICATION_KEYPAD

        assertBytes(esc("O "), TerminalKeyEvent.key(TerminalKey.NUMPAD_SPACE), bits)
        assertBytes(esc("OI"), TerminalKeyEvent.key(TerminalKey.NUMPAD_TAB), bits)
        assertBytes(esc("Op"), TerminalKeyEvent.key(TerminalKey.NUMPAD_0), bits)
        assertBytes(esc("Oq"), TerminalKeyEvent.key(TerminalKey.NUMPAD_1), bits)
        assertBytes(esc("Or"), TerminalKeyEvent.key(TerminalKey.NUMPAD_2), bits)
        assertBytes(esc("Os"), TerminalKeyEvent.key(TerminalKey.NUMPAD_3), bits)
        assertBytes(esc("Ot"), TerminalKeyEvent.key(TerminalKey.NUMPAD_4), bits)
        assertBytes(esc("Ou"), TerminalKeyEvent.key(TerminalKey.NUMPAD_5), bits)
        assertBytes(esc("Ov"), TerminalKeyEvent.key(TerminalKey.NUMPAD_6), bits)
        assertBytes(esc("Ow"), TerminalKeyEvent.key(TerminalKey.NUMPAD_7), bits)
        assertBytes(esc("Ox"), TerminalKeyEvent.key(TerminalKey.NUMPAD_8), bits)
        assertBytes(esc("Oy"), TerminalKeyEvent.key(TerminalKey.NUMPAD_9), bits)
        assertBytes(esc("On"), TerminalKeyEvent.key(TerminalKey.NUMPAD_DECIMAL), bits)
        assertBytes(esc("Oo"), TerminalKeyEvent.key(TerminalKey.NUMPAD_DIVIDE), bits)
        assertBytes(esc("Oj"), TerminalKeyEvent.key(TerminalKey.NUMPAD_MULTIPLY), bits)
        assertBytes(esc("Om"), TerminalKeyEvent.key(TerminalKey.NUMPAD_SUBTRACT), bits)
        assertBytes(esc("Ok"), TerminalKeyEvent.key(TerminalKey.NUMPAD_ADD), bits)
        assertBytes(esc("Ol"), TerminalKeyEvent.key(TerminalKey.NUMPAD_COMMA), bits)
        assertBytes(esc("Ol"), TerminalKeyEvent.key(TerminalKey.NUMPAD_SEPARATOR), bits)
        assertBytes(esc("OX"), TerminalKeyEvent.key(TerminalKey.NUMPAD_EQUALS), bits)
        assertBytes(esc("[E"), TerminalKeyEvent.key(TerminalKey.NUMPAD_BEGIN), bits)
        assertBytes(esc("OM"), TerminalKeyEvent.key(TerminalKey.NUMPAD_ENTER), bits)
    }

    @Test
    fun `applies keypad modifier policy without silent modifier loss`() {
        assertBytes(bytes(0x1b, 0x31), TerminalKeyEvent.key(TerminalKey.NUMPAD_1, TerminalModifiers.ALT))
        assertBytes(bytes(), TerminalKeyEvent.key(TerminalKey.NUMPAD_1, TerminalModifiers.CTRL))
        assertBytes(
            expected = ascii("1"),
            event = TerminalKeyEvent.key(TerminalKey.NUMPAD_1, TerminalModifiers.CTRL),
            policy =
                TerminalInputPolicy(
                    unsupportedModifiedKeyPolicy = UnsupportedModifiedKeyPolicy.EMIT_UNMODIFIED,
                ),
        )
        assertBytes(
            expected = bytes(),
            event = TerminalKeyEvent.key(TerminalKey.NUMPAD_1, TerminalModifiers.META),
            policy = TerminalInputPolicy(metaKeyPolicy = MetaKeyPolicy.SUPPRESS_EVENT),
        )
        assertBytes(
            expected = esc("\u001bOq"),
            event = TerminalKeyEvent.key(TerminalKey.NUMPAD_1, TerminalModifiers.ALT),
            modeBits = TerminalModeBits.APPLICATION_KEYPAD,
        )
    }

    @Test
    fun `Kitty keyboard encodes printable keys with and without modifiers`() {
        val bitsDisambiguate = kittyKeyboardBits(KittyKeyboardProgressiveFlag.DISAMBIGUATE_ESCAPE_CODES)
        val bitsReportAll = kittyKeyboardBits(KittyKeyboardProgressiveFlag.REPORT_ALL_KEYS_AS_ESCAPE_CODES)

        // 1. Under DISAMBIGUATE_ESCAPE_CODES:
        // Unmodified printable -> raw text
        assertBytes(bytes(0x61), TerminalKeyEvent.codepoint('a'.code), bitsDisambiguate)
        // Shift alone on printable -> shifted raw text
        assertBytes(bytes(0x41), TerminalKeyEvent.codepoint('A'.code, TerminalModifiers.SHIFT), bitsDisambiguate)
        // Ctrl + printable -> CSI-u
        assertBytes(esc("[97;5u"), TerminalKeyEvent.codepoint('a'.code, TerminalModifiers.CTRL), bitsDisambiguate)
        // Alt + printable -> CSI-u
        assertBytes(esc("[97;3u"), TerminalKeyEvent.codepoint('a'.code, TerminalModifiers.ALT), bitsDisambiguate)
        // Ctrl+Shift+a -> CSI-u
        assertBytes(
            esc("[97;6u"),
            TerminalKeyEvent.codepoint('a'.code, TerminalModifiers.CTRL or TerminalModifiers.SHIFT),
            bitsDisambiguate,
        )

        // 2. Under REPORT_ALL_KEYS_AS_ESCAPE_CODES:
        // Unmodified printable -> CSI-u
        assertBytes(esc("[97u"), TerminalKeyEvent.codepoint('a'.code), bitsReportAll)
        // Shift + printable -> CSI-u with modifier parameter 2
        assertBytes(esc("[97;2u"), TerminalKeyEvent.codepoint('a'.code, TerminalModifiers.SHIFT), bitsReportAll)
        // Ctrl + printable -> CSI-u
        assertBytes(esc("[97;5u"), TerminalKeyEvent.codepoint('a'.code, TerminalModifiers.CTRL), bitsReportAll)
    }

    @Test
    fun `Kitty keyboard encodes Enter Tab Escape Backspace`() {
        val bitsDisambiguate = kittyKeyboardBits(KittyKeyboardProgressiveFlag.DISAMBIGUATE_ESCAPE_CODES)
        val bitsReportAll = kittyKeyboardBits(KittyKeyboardProgressiveFlag.REPORT_ALL_KEYS_AS_ESCAPE_CODES)
        val bitsOther = kittyKeyboardBits(KittyKeyboardProgressiveFlag.REPORT_EVENT_TYPES) // neither 1 nor 8

        // 1. Under DISAMBIGUATE_ESCAPE_CODES (unmodified -> CSI-u):
        assertBytes(esc("[13u"), TerminalKeyEvent.key(TerminalKey.ENTER), bitsDisambiguate)
        assertBytes(esc("[9u"), TerminalKeyEvent.key(TerminalKey.TAB), bitsDisambiguate)
        assertBytes(esc("[27u"), TerminalKeyEvent.key(TerminalKey.ESCAPE), bitsDisambiguate)
        assertBytes(esc("[127u"), TerminalKeyEvent.key(TerminalKey.BACKSPACE), bitsDisambiguate)

        // 2. Under REPORT_ALL_KEYS_AS_ESCAPE_CODES (unmodified -> CSI-u):
        assertBytes(esc("[13u"), TerminalKeyEvent.key(TerminalKey.ENTER), bitsReportAll)
        assertBytes(esc("[9u"), TerminalKeyEvent.key(TerminalKey.TAB), bitsReportAll)
        assertBytes(esc("[27u"), TerminalKeyEvent.key(TerminalKey.ESCAPE), bitsReportAll)
        assertBytes(esc("[127u"), TerminalKeyEvent.key(TerminalKey.BACKSPACE), bitsReportAll)

        // 3. With modifiers (always CSI-u):
        assertBytes(esc("[13;2u"), TerminalKeyEvent.key(TerminalKey.ENTER, TerminalModifiers.SHIFT), bitsOther)
        assertBytes(esc("[9;5u"), TerminalKeyEvent.key(TerminalKey.TAB, TerminalModifiers.CTRL), bitsOther)
        assertBytes(esc("[27;3u"), TerminalKeyEvent.key(TerminalKey.ESCAPE, TerminalModifiers.ALT), bitsOther)
        assertBytes(esc("[127;5u"), TerminalKeyEvent.key(TerminalKey.BACKSPACE, TerminalModifiers.CTRL), bitsOther)

        // 4. Unmodified without disambiguation/report-all (fallback to legacy):
        assertBytes(bytes(0x0d), TerminalKeyEvent.key(TerminalKey.ENTER), bitsOther)
        assertBytes(bytes(0x0d, 0x0a), TerminalKeyEvent.key(TerminalKey.ENTER), bitsOther or TerminalModeBits.NEW_LINE_MODE)
        assertBytes(
            expected = bytes(0x0d),
            event = TerminalKeyEvent.key(TerminalKey.ENTER),
            modeBits = bitsOther or TerminalModeBits.NEW_LINE_MODE,
            policy = TerminalInputPolicy(enterNewLineModePolicy = EnterNewLineModePolicy.SEND_CR),
        )
        assertBytes(bytes(0x09), TerminalKeyEvent.key(TerminalKey.TAB), bitsOther)
        assertBytes(bytes(0x1b), TerminalKeyEvent.key(TerminalKey.ESCAPE), bitsOther)
        assertBytes(bytes(0x7f), TerminalKeyEvent.key(TerminalKey.BACKSPACE), bitsOther)
    }

    @Test
    fun `Kitty keyboard encodes arrows and navigation keys under protocol`() {
        val bits = kittyKeyboardBits(KittyKeyboardProgressiveFlag.DISAMBIGUATE_ESCAPE_CODES)
        val appCursorBits = bits or TerminalModeBits.APPLICATION_CURSOR_KEYS

        // Unmodified arrows (should always use CSI, bypassing APPLICATION_CURSOR_KEYS)
        assertBytes(esc("[A"), TerminalKeyEvent.key(TerminalKey.UP), bits)
        assertBytes(esc("[B"), TerminalKeyEvent.key(TerminalKey.DOWN), bits)
        assertBytes(esc("[C"), TerminalKeyEvent.key(TerminalKey.RIGHT), bits)
        assertBytes(esc("[D"), TerminalKeyEvent.key(TerminalKey.LEFT), bits)

        assertBytes(esc("[A"), TerminalKeyEvent.key(TerminalKey.UP), appCursorBits)
        assertBytes(esc("[B"), TerminalKeyEvent.key(TerminalKey.DOWN), appCursorBits)

        // Modified arrows
        assertBytes(esc("[1;5A"), TerminalKeyEvent.key(TerminalKey.UP, TerminalModifiers.CTRL), bits)
        assertBytes(esc("[1;2D"), TerminalKeyEvent.key(TerminalKey.LEFT, TerminalModifiers.SHIFT), bits)

        // Navigation keys (Home/End use letter final, others use tilde)
        assertBytes(esc("[H"), TerminalKeyEvent.key(TerminalKey.HOME), bits)
        assertBytes(esc("[F"), TerminalKeyEvent.key(TerminalKey.END), bits)
        assertBytes(esc("[H"), TerminalKeyEvent.key(TerminalKey.HOME), appCursorBits)
        assertBytes(esc("[F"), TerminalKeyEvent.key(TerminalKey.END), appCursorBits)

        assertBytes(esc("[1;5H"), TerminalKeyEvent.key(TerminalKey.HOME, TerminalModifiers.CTRL), bits)
        assertBytes(esc("[1;5F"), TerminalKeyEvent.key(TerminalKey.END, TerminalModifiers.CTRL), bits)

        assertBytes(esc("[2~"), TerminalKeyEvent.key(TerminalKey.INSERT), bits)
        assertBytes(esc("[3~"), TerminalKeyEvent.key(TerminalKey.DELETE), bits)
        assertBytes(esc("[5~"), TerminalKeyEvent.key(TerminalKey.PAGE_UP), bits)
        assertBytes(esc("[6~"), TerminalKeyEvent.key(TerminalKey.PAGE_DOWN), bits)

        assertBytes(esc("[2;5~"), TerminalKeyEvent.key(TerminalKey.INSERT, TerminalModifiers.CTRL), bits)
        assertBytes(esc("[3;5~"), TerminalKeyEvent.key(TerminalKey.DELETE, TerminalModifiers.CTRL), bits)
    }

    @Test
    fun `Kitty keyboard encodes function keys under protocol`() {
        val bits = kittyKeyboardBits(KittyKeyboardProgressiveFlag.DISAMBIGUATE_ESCAPE_CODES)

        // F1-F4 (F1, F2, F4 use CSI final letter; F3 uses tilde format 13~)
        assertBytes(esc("[P"), TerminalKeyEvent.key(TerminalKey.F1), bits)
        assertBytes(esc("[Q"), TerminalKeyEvent.key(TerminalKey.F2), bits)
        assertBytes(esc("[13~"), TerminalKeyEvent.key(TerminalKey.F3), bits)
        assertBytes(esc("[S"), TerminalKeyEvent.key(TerminalKey.F4), bits)

        assertBytes(esc("[1;5P"), TerminalKeyEvent.key(TerminalKey.F1, TerminalModifiers.CTRL), bits)
        assertBytes(esc("[1;5Q"), TerminalKeyEvent.key(TerminalKey.F2, TerminalModifiers.CTRL), bits)
        assertBytes(esc("[13;5~"), TerminalKeyEvent.key(TerminalKey.F3, TerminalModifiers.CTRL), bits)
        assertBytes(esc("[1;5S"), TerminalKeyEvent.key(TerminalKey.F4, TerminalModifiers.CTRL), bits)

        // F5-F12
        assertBytes(esc("[15~"), TerminalKeyEvent.key(TerminalKey.F5), bits)
        assertBytes(esc("[24~"), TerminalKeyEvent.key(TerminalKey.F12), bits)
        assertBytes(esc("[15;5~"), TerminalKeyEvent.key(TerminalKey.F5, TerminalModifiers.CTRL), bits)
        assertBytes(esc("[24;5~"), TerminalKeyEvent.key(TerminalKey.F12, TerminalModifiers.CTRL), bits)

        // PF1-PF4 (behave like F1-F4)
        assertBytes(esc("[P"), TerminalKeyEvent.key(TerminalKey.PF1), bits)
        assertBytes(esc("[Q"), TerminalKeyEvent.key(TerminalKey.PF2), bits)
        assertBytes(esc("[13~"), TerminalKeyEvent.key(TerminalKey.PF3), bits)
        assertBytes(esc("[S"), TerminalKeyEvent.key(TerminalKey.PF4), bits)
    }

    @Test
    fun `Kitty keyboard encodes keypad keys under protocol`() {
        val bits = kittyKeyboardBits(KittyKeyboardProgressiveFlag.DISAMBIGUATE_ESCAPE_CODES)
        val appKeypadBits = bits or TerminalModeBits.APPLICATION_KEYPAD

        // Keypad digits map to PUA codepoints under CSI-u (regardless of APPLICATION_KEYPAD mode)
        assertBytes(esc("[57399u"), TerminalKeyEvent.key(TerminalKey.NUMPAD_0), bits)
        assertBytes(esc("[57408u"), TerminalKeyEvent.key(TerminalKey.NUMPAD_9), bits)
        assertBytes(esc("[57399u"), TerminalKeyEvent.key(TerminalKey.NUMPAD_0), appKeypadBits)
        assertBytes(esc("[57408u"), TerminalKeyEvent.key(TerminalKey.NUMPAD_9), appKeypadBits)

        assertBytes(esc("[57399;5u"), TerminalKeyEvent.key(TerminalKey.NUMPAD_0, TerminalModifiers.CTRL), bits)

        // Keypad operators and functions
        assertBytes(esc("[57409u"), TerminalKeyEvent.key(TerminalKey.NUMPAD_DECIMAL), bits)
        assertBytes(esc("[57410u"), TerminalKeyEvent.key(TerminalKey.NUMPAD_DIVIDE), bits)
        assertBytes(esc("[57411u"), TerminalKeyEvent.key(TerminalKey.NUMPAD_MULTIPLY), bits)
        assertBytes(esc("[57412u"), TerminalKeyEvent.key(TerminalKey.NUMPAD_SUBTRACT), bits)
        assertBytes(esc("[57413u"), TerminalKeyEvent.key(TerminalKey.NUMPAD_ADD), bits)
        assertBytes(esc("[57414u"), TerminalKeyEvent.key(TerminalKey.NUMPAD_ENTER), bits)
        assertBytes(esc("[57415u"), TerminalKeyEvent.key(TerminalKey.NUMPAD_EQUALS), bits)
        assertBytes(esc("[57416u"), TerminalKeyEvent.key(TerminalKey.NUMPAD_COMMA), bits)
        assertBytes(esc("[57416u"), TerminalKeyEvent.key(TerminalKey.NUMPAD_SEPARATOR), bits)
        assertBytes(esc("[57427u"), TerminalKeyEvent.key(TerminalKey.NUMPAD_BEGIN), bits)

        // Keypad space & tab
        assertBytes(esc("[32u"), TerminalKeyEvent.key(TerminalKey.NUMPAD_SPACE), bits)
        assertBytes(esc("[9u"), TerminalKeyEvent.key(TerminalKey.NUMPAD_TAB), bits)
        assertBytes(esc("[32;5u"), TerminalKeyEvent.key(TerminalKey.NUMPAD_SPACE, TerminalModifiers.CTRL), bits)
    }

    @Test
    fun `unmodified special keys write static sequence arrays`() {
        val output = ReferenceRecordingHostOutput()
        val encoder = KeyboardEncoder(output, InputScratchBuffer())

        encoder.encode(TerminalKeyEvent.key(TerminalKey.UP), 0L)
        encoder.encode(TerminalKeyEvent.key(TerminalKey.F5), 0L)

        assertSame(TerminalSequences.CURSOR_UP_NORMAL, output.writeBytesCalls[0])
        assertSame(TerminalSequences.F5, output.writeBytesCalls[1])
    }

    private fun assertBytes(
        expected: ByteArray,
        event: TerminalKeyEvent,
        modeBits: Long = 0L,
        policy: TerminalInputPolicy = TerminalInputPolicy(),
    ) {
        val output = RecordingHostOutput()
        val encoder = KeyboardEncoder(output, InputScratchBuffer(), policy)

        encoder.encode(event, modeBits)

        assertArrayEquals(expected, output.bytes)
    }

    private fun esc(textAfterEsc: String): ByteArray = bytes(0x1b) + ascii(textAfterEsc)

    private fun modifyOtherKeysBits(mode: Int): Long =
        TerminalModeBits.withPackedValue(
            bits = 0L,
            mask = TerminalModeBits.MODIFY_OTHER_KEYS_MASK,
            shift = TerminalModeBits.MODIFY_OTHER_KEYS_SHIFT,
            value = mode,
        )

    private fun formatOtherKeysBits(mode: Int): Long =
        TerminalModeBits.withPackedValue(
            bits = 0L,
            mask = TerminalModeBits.FORMAT_OTHER_KEYS_MASK,
            shift = TerminalModeBits.FORMAT_OTHER_KEYS_SHIFT,
            value = mode,
        )

    private fun kittyKeyboardBits(flags: Int): Long =
        TerminalModeBits.withPackedValue(
            bits = 0L,
            mask = TerminalModeBits.KITTY_KEYBOARD_FLAGS_MASK,
            shift = TerminalModeBits.KITTY_KEYBOARD_FLAGS_SHIFT,
            value = flags,
        )

    private fun ascii(text: String): ByteArray {
        val bytes = ByteArray(text.length)
        var i = 0
        while (i < text.length) {
            bytes[i] = text[i].code.toByte()
            i++
        }
        return bytes
    }

    private fun bytes(vararg values: Int): ByteArray {
        val bytes = ByteArray(values.size)
        var i = 0
        while (i < values.size) {
            bytes[i] = values[i].toByte()
            i++
        }
        return bytes
    }

    private open class RecordingHostOutput : TerminalHostOutput {
        var bytes: ByteArray = ByteArray(0)
            private set

        override fun writeByte(byte: Int) {
            bytes += byte.toByte()
        }

        override fun writeBytes(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) {
            this.bytes += bytes.copyOfRange(offset, offset + length)
        }

        override fun writeAscii(text: String) {
            bytes += text.encodeToByteArray()
        }

        override fun writeUtf8(text: String) {
            bytes += text.encodeToByteArray()
        }
    }

    private class ReferenceRecordingHostOutput : RecordingHostOutput() {
        val writeBytesCalls: MutableList<ByteArray> = mutableListOf()

        override fun writeBytes(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) {
            writeBytesCalls += bytes
            super.writeBytes(bytes, offset, length)
        }
    }
}

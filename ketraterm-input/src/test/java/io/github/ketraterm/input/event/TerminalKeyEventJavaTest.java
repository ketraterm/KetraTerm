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
package io.github.ketraterm.input.event;

import io.github.ketraterm.core.TerminalBuffers;
import io.github.ketraterm.input.TerminalInputEncoders;
import io.github.ketraterm.protocol.host.TerminalHostOutput;
import io.github.ketraterm.protocol.keyboard.KittyKeyboardProgressiveFlag;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class TerminalKeyEventJavaTest {
    @Test
    void staticFactoriesSupplyDefaultModifiersPhaseAndUnknownKeyMetadata() {
        var key = TerminalKeyEvent.key(TerminalKey.UP);
        var codepoint = TerminalKeyEvent.codepoint(0x1f642);
        var text = TerminalKeyEvent.text("e\u0301");

        assertEquals(TerminalKey.UP, key.getKey());
        assertEquals(TerminalKeyEvent.NO_CODEPOINT, key.getCodepoint());
        assertNull(key.getAssociatedText());
        assertNull(codepoint.getKey());
        assertEquals(0x1f642, codepoint.getCodepoint());
        assertNull(codepoint.getAssociatedText());
        assertNull(text.getKey());
        assertEquals(TerminalKeyEvent.TEXT_ONLY_CODEPOINT, text.getCodepoint());
        assertEquals("e\u0301", text.getAssociatedText());
        for (var event : new TerminalKeyEvent[] {key, codepoint, text}) {
            assertEquals(TerminalModifiers.NONE, event.getModifiers());
            assertEquals(TerminalKeyEventType.PRESS, event.getType());
            assertEquals(TerminalKeyEvent.NO_CODEPOINT, event.getUnshiftedCodepoint());
            assertEquals(TerminalKeyEvent.NO_CODEPOINT, event.getShiftedCodepoint());
            assertEquals(TerminalKeyEvent.NO_CODEPOINT, event.getBaseLayoutCodepoint());
        }
    }

    @Test
    void generatedPrefixOverloadsPreserveTrailingDefaults() {
        var key = TerminalKeyEvent.key(TerminalKey.UP, TerminalModifiers.CTRL);
        var text = TerminalKeyEvent.text("A", TerminalModifiers.SHIFT);
        assertEquals(TerminalModifiers.CTRL, key.getModifiers());
        assertEquals(TerminalKeyEventType.PRESS, key.getType());
        assertEquals(TerminalModifiers.SHIFT, text.getModifiers());
        assertEquals(TerminalKeyEventType.PRESS, text.getType());

        assertEquals(TerminalKeyEvent.NO_CODEPOINT,
                TerminalKeyEvent.codepoint('A', TerminalModifiers.SHIFT).getUnshiftedCodepoint());
        assertEquals(TerminalKeyEvent.NO_CODEPOINT,
                TerminalKeyEvent.codepoint('A', TerminalModifiers.SHIFT, 'a').getShiftedCodepoint());
        assertEquals(TerminalKeyEvent.NO_CODEPOINT,
                TerminalKeyEvent.codepoint('A', TerminalModifiers.SHIFT, 'a', 'A').getBaseLayoutCodepoint());
        assertNull(TerminalKeyEvent.codepoint('A', TerminalModifiers.SHIFT, 'a', 'A', 'q').getAssociatedText());
        assertEquals(TerminalKeyEventType.PRESS,
                TerminalKeyEvent.codepoint('A', TerminalModifiers.SHIFT, 'a', 'A', 'q', "A").getType());
    }

    @Test
    void fullFactoriesPreserveExplicitLifecycleAndRichMetadata() {
        var key = TerminalKeyEvent.key(TerminalKey.UP, TerminalModifiers.CTRL, TerminalKeyEventType.RELEASE);
        var text = TerminalKeyEvent.text("e\u0301", TerminalModifiers.ALT, TerminalKeyEventType.REPEAT);
        var codepoint = TerminalKeyEvent.codepoint(
                '+', TerminalModifiers.SHIFT | TerminalModifiers.CTRL, '=', '+', 'q', "+\u0301", TerminalKeyEventType.REPEAT);

        assertEquals(TerminalKey.UP, key.getKey());
        assertEquals(TerminalModifiers.CTRL, key.getModifiers());
        assertEquals(TerminalKeyEventType.RELEASE, key.getType());
        assertEquals(TerminalKeyEvent.TEXT_ONLY_CODEPOINT, text.getCodepoint());
        assertEquals("e\u0301", text.getAssociatedText());
        assertEquals(TerminalModifiers.ALT, text.getModifiers());
        assertEquals(TerminalKeyEventType.REPEAT, text.getType());
        assertNull(codepoint.getKey());
        assertEquals('+', codepoint.getCodepoint());
        assertEquals('=', codepoint.getUnshiftedCodepoint());
        assertEquals('+', codepoint.getShiftedCodepoint());
        assertEquals('q', codepoint.getBaseLayoutCodepoint());
        assertEquals("+\u0301", codepoint.getAssociatedText());
        assertEquals(TerminalModifiers.SHIFT | TerminalModifiers.CTRL, codepoint.getModifiers());
        assertEquals(TerminalKeyEventType.REPEAT, codepoint.getType());
    }

    @Test
    void javaFactoriesRejectMalformedScalarsModifiersAndCommittedText() {
        assertThrows(IllegalArgumentException.class, () -> TerminalKeyEvent.codepoint(0xd800));
        assertThrows(IllegalArgumentException.class, () -> TerminalKeyEvent.key(TerminalKey.UP, 1 << 8));
        assertThrows(IllegalArgumentException.class, () -> TerminalKeyEvent.text(""));
        assertThrows(IllegalArgumentException.class, () -> TerminalKeyEvent.text("\uD800"));
        assertThrows(IllegalArgumentException.class, () -> TerminalKeyEvent.text("a\n"));
    }

    @Test
    void javaFactoryEventsEncodeExactBytesThroughThePublicEncoderFactory() {
        var terminal = TerminalBuffers.create(4, 2);
        var output = new RecordingOutput();
        var encoder = TerminalInputEncoders.create(terminal, output);

        encoder.encodeKey(TerminalKeyEvent.key(TerminalKey.UP));
        encoder.encodeKey(TerminalKeyEvent.codepoint(0x1f642));
        terminal.setBracketedPasteEnabled(true);
        encoder.encodeKey(TerminalKeyEvent.text("e\u0301", TerminalModifiers.CTRL | TerminalModifiers.ALT));
        terminal.setApplicationCursorKeys(true);
        encoder.encodeKey(TerminalKeyEvent.key(TerminalKey.UP, TerminalModifiers.NONE, TerminalKeyEventType.REPEAT));
        encoder.encodeKey(TerminalKeyEvent.key(TerminalKey.UP, TerminalModifiers.NONE, TerminalKeyEventType.RELEASE));
        encoder.encodeKey(TerminalKeyEvent.text("ignored", TerminalModifiers.NONE, TerminalKeyEventType.RELEASE));

        assertArrayEquals("\u001b[A\uD83D\uDE42e\u0301\u001bOA".getBytes(StandardCharsets.UTF_8), output.bytes.toByteArray());
    }

    @Test
    void richJavaFactoryEventUsesCoreKittyModeWithoutLosingMetadata() {
        var terminal = TerminalBuffers.create(4, 2);
        terminal.setKittyKeyboardFlags(
                KittyKeyboardProgressiveFlag.REPORT_ALL_KEYS_AS_ESCAPE_CODES
                        | KittyKeyboardProgressiveFlag.REPORT_EVENT_TYPES
                        | KittyKeyboardProgressiveFlag.REPORT_ALTERNATE_KEYS
                        | KittyKeyboardProgressiveFlag.REPORT_ASSOCIATED_TEXT);
        var output = new RecordingOutput();
        var encoder = TerminalInputEncoders.create(terminal, output);

        encoder.encodeKey(TerminalKeyEvent.codepoint(
                '+', TerminalModifiers.SHIFT | TerminalModifiers.CTRL, '=', '+', 'q', "+\u0301", TerminalKeyEventType.REPEAT));

        assertArrayEquals("\u001b[61:43:113;6:2;43:769u".getBytes(StandardCharsets.US_ASCII), output.bytes.toByteArray());
    }

    private static final class RecordingOutput implements TerminalHostOutput {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        @Override
        public void writeByte(int value) {
            bytes.write(value);
        }

        @Override
        public void writeBytes(byte @NonNull [] source, int offset, int length) {
            bytes.write(source, offset, length);
        }

        @Override
        public void writeAscii(String text) {
            bytes.writeBytes(text.getBytes(StandardCharsets.US_ASCII));
        }

        @Override
        public void writeUtf8(String text) {
            bytes.writeBytes(text.getBytes(StandardCharsets.UTF_8));
        }
    }
}

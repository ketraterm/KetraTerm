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
package io.github.ketraterm.host;

import io.github.ketraterm.core.TerminalBuffers;
import io.github.ketraterm.core.api.TerminalBuffer;
import io.github.ketraterm.core.api.TerminalInputState;
import io.github.ketraterm.parser.api.TerminalOutputParser;
import io.github.ketraterm.parser.api.TerminalParsers;
import io.github.ketraterm.protocol.NotificationLevel;
import io.github.ketraterm.protocol.TerminalHostModeCapability;
import io.github.ketraterm.protocol.keyboard.KittyKeyboardProgressiveFlag;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HostCommandAdapterJavaTest {
    @Test
    void terminalOnlyUsesConservativeDefaults() {
        var terminal = TerminalBuffers.create(12, 2);
        var adapter = new HostCommandAdapter(terminal);
        var parser = TerminalParsers.create(adapter);

        accept(parser, "Java\u001B]2;title\u0007\u001B[=31u\u001B[?u\u001B[?67$p\u001B[?1042$p");
        parser.endOfInput();

        assertEquals("Java", terminal.getLineAsString(0));
        assertEquals("title", terminal.getWindowTitle());
        assertEquals(new HostPolicy(), adapter.getCurrentPolicy());
        assertEquals(9, TerminalInputState.kittyKeyboardFlags(terminal.getInputModeBits()));
        assertEquals("\u001B[?9u\u001B[?67;2$y\u001B[?1042;0$y", drain(terminal));
    }

    @Test
    void suppliedEventsReceiveDefaultAllowedControls() {
        var terminal = TerminalBuffers.create(12, 2);
        var events = new RecordingEvents();
        var adapter = new HostCommandAdapter(terminal, events);
        var parser = TerminalParsers.create(adapter);

        accept(parser, "\u001B]2;title\u0007\u0007\u001B[8;4;20t");

        assertEquals(List.of("title:title", "bell", "resize:4:20"), events.events);
        assertEquals("title", terminal.getWindowTitle());
    }

    @Test
    void suppliedPolicyGatesTitlesAndRepliesWithoutSuppressingModes() {
        var terminal = TerminalBuffers.create(12, 2);
        var events = new RecordingEvents();
        var policy = deniedTitleAndResponsePolicy();
        var adapter = new HostCommandAdapter(terminal, events, policy);
        var parser = TerminalParsers.create(adapter);

        accept(parser, "\u001B]2;blocked\u0007\u001B[?2004h\u001B[?2004$p");

        assertSame(policy, adapter.getCurrentPolicy());
        assertEquals("", terminal.getWindowTitle());
        assertTrue(events.events.isEmpty());
        assertTrue(TerminalInputState.isBracketedPasteEnabled(terminal.getInputModeBits()));
        assertEquals("", drain(terminal));
    }

    @Test
    void suppliedKeyboardMaskKeepsRemainingDefaults() {
        var terminal = TerminalBuffers.create(12, 2);
        var adapter = new HostCommandAdapter(
                terminal, HostEventSink.NONE, new HostPolicy(),
                KittyKeyboardProgressiveFlag.DISAMBIGUATE_ESCAPE_CODES);
        var parser = TerminalParsers.create(adapter);

        accept(parser, "\u001B[=31u\u001B[?u\u001B[?67$p\u001B[?1042$p");

        assertEquals(1, TerminalInputState.kittyKeyboardFlags(terminal.getInputModeBits()));
        assertEquals("\u001B[?1u\u001B[?67;2$y\u001B[?1042;0$y", drain(terminal));
    }

    @Test
    void suppliedBellCapabilitiesKeepDefaultBackarrowSelection() {
        var terminal = TerminalBuffers.create(12, 2);
        var adapter = new HostCommandAdapter(
                terminal, HostEventSink.NONE, new HostPolicy(),
                KittyKeyboardProgressiveFlag.DEFAULT_HOST_SUPPORTED_MASK,
                TerminalHostModeCapability.URGENT_BELL);
        var parser = TerminalParsers.create(adapter);

        accept(parser, "\u001B[?1042$p\u001B[?1043$p\u001B[?67$p");

        assertEquals("\u001B[?1042;2$y\u001B[?1043;0$y\u001B[?67;2$y", drain(terminal));
    }

    @Test
    void fullConstructorPreservesExplicitCapabilitiesAndBackarrowDefault() {
        var terminal = TerminalBuffers.create(12, 2);
        var adapter = new HostCommandAdapter(
                terminal, HostEventSink.NONE, new HostPolicy(),
                KittyKeyboardProgressiveFlag.REPORT_EVENT_TYPES,
                TerminalHostModeCapability.POP_ON_BELL, true);
        var parser = TerminalParsers.create(adapter);

        accept(parser, "\u001B[=31u\u001B[?u\u001B[?1042$p\u001B[?1043$p\u001B[?67$p");
        assertEquals("\u001B[?2u\u001B[?1042;0$y\u001B[?1043;2$y\u001B[?67;1$y", drain(terminal));

        accept(parser, "\u001B[?67l\u001B[?67$p\u001Bc\u001B[?67$p");
        assertEquals("\u001B[?67;2$y\u001B[?67;1$y", drain(terminal));
    }

    private static HostPolicy deniedTitleAndResponsePolicy() {
        var defaults = new HostPolicy();
        return new HostPolicy(
                new TerminalTitlePolicy(TerminalTitlePermission.DENY,
                        TerminalTitleOverflowPolicy.CLAMP, TerminalTitlePolicy.DEFAULT_MAX_LENGTH),
                defaults.getHyperlinkPolicy(),
                defaults.getCurrentWorkingDirectoryPolicy(),
                defaults.getNotificationPolicy(),
                defaults.getWindowManipulationPolicy(),
                defaults.getPalettePolicy(),
                HostControlPolicy.DENY,
                defaults.getClipboardPolicy(),
                defaults.getMaxHyperlinkEntries(),
                defaults.getMaxHyperlinkUriLength(),
                defaults.getMaxHyperlinkIdLength(),
                defaults.getMaxNotificationTitleLength(),
                defaults.getMaxNotificationBodyLength(),
                defaults.getMaxCurrentWorkingDirectoryUriLength());
    }

    private static void accept(TerminalOutputParser parser, String stream) {
        var bytes = stream.getBytes(StandardCharsets.UTF_8);
        parser.accept(bytes, 0, bytes.length);
    }

    private static String drain(TerminalBuffer terminal) {
        var bytes = new byte[terminal.getPendingResponseBytes()];
        var length = terminal.readResponseBytes(bytes, 0, bytes.length);
        return new String(bytes, 0, length, StandardCharsets.UTF_8);
    }

    private static final class RecordingEvents implements HostEventSink {
        private final List<String> events = new ArrayList<>();

        @Override
        public void bell() {
            events.add("bell");
        }

        @Override
        public void iconTitleChanged(@NonNull String title) {
            events.add("icon:" + title);
        }

        @Override
        public void windowTitleChanged(@NonNull String title) {
            events.add("title:" + title);
        }

        @Override
        public void resizeWindow(int rows, int columns) {
            events.add("resize:" + rows + ":" + columns);
        }

        @Override
        public void showNotification(@NonNull String title, @NonNull String body, @NonNull NotificationLevel level) {
            events.add("notification:" + title + ":" + body + ":" + level);
        }
    }
}

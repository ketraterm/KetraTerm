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
package consumer;

import io.github.ketraterm.core.TerminalBuffers;
import io.github.ketraterm.input.TerminalInputEncoders;
import io.github.ketraterm.input.api.TerminalInputEncoderFactory;
import io.github.ketraterm.parser.api.TerminalOutputParserFactory;
import io.github.ketraterm.parser.api.TerminalParsers;
import io.github.ketraterm.render.api.TerminalRenderFrameReader;
import io.github.ketraterm.session.TerminalSession;
import io.github.ketraterm.session.TerminalCommandEditContext;
import io.github.ketraterm.session.TerminalInputAdmission;
import io.github.ketraterm.input.event.TerminalPasteEvent;
import io.github.ketraterm.transport.TerminalConnector;
import io.github.ketraterm.host.TerminalClipboardReadRequest;
import io.github.ketraterm.host.TerminalClipboardReadAuditEvent;
import io.github.ketraterm.input.TerminalClipboardReply;
import io.github.ketraterm.protocol.host.TerminalHostOutput;
import io.github.ketraterm.ui.swing.api.SwingHostServices;
import io.github.ketraterm.ui.swing.api.SwingHyperlinkAction;
import io.github.ketraterm.ui.swing.api.SwingTerminal;
import io.github.ketraterm.ui.swing.api.TerminalUiDispatcher;
import io.github.ketraterm.ui.swing.settings.SwingSettings;
import io.github.ketraterm.ui.swing.settings.TerminalClipboardHandler;
import java.awt.event.MouseEvent;
import java.awt.Rectangle;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.SwingUtilities;

public final class JavaConsumer {
    public static void verifySelection(SwingTerminal terminal) {
        var events = new AtomicInteger();
        io.github.ketraterm.ui.swing.api.TerminalSelectionListener listener = (previous, current) -> {
            if (!SwingUtilities.isEventDispatchThread()) throw new AssertionError("Selection callback requires EDT");
            events.incrementAndGet();
        };
        terminal.addSelectionListener(listener);
        try {
            var range = terminal.createSelectionRange(0, 0L, 5, 0L);
            if (range == null || range.isEmpty() || !terminal.setSelection(range))
                throw new AssertionError("Java range assignment failed");
            var saved = terminal.currentSelectionRange();
            if (saved == null || saved.getCaretColumn() != 5 || saved.getAnchorAbsoluteRow() != 0L)
                throw new AssertionError("Java selection snapshot differs");
            terminal.clearSelection();
            if (!terminal.setSelection(saved)) throw new AssertionError("Java selection restoration failed");
            terminal.clearSelection();
            if (events.get() != 4) throw new AssertionError("Java selection events differ");
        } finally {
            terminal.removeSelectionListener(listener);
        }
    }

    public static void verifyConditionalAdmission(TerminalSession session, TerminalCommandEditContext expected) {
        if (!expected.getCommandLine().getCommandText().equals("help") || expected.isCancelled()
            || session.submitInput(expected, java.util.List.of()) != TerminalInputAdmission.ACCEPTED)
            throw new AssertionError("Java conditional admission must validate the captured command");
    }

    public static TerminalInputEncoderFactory inputEncoderFactory() {
        return TerminalInputEncoders::create;
    }

    public static TerminalOutputParserFactory parserFactory() {
        return TerminalParsers::create;
    }

    public static void verifySessionConstruction(TerminalConnector connector) {
        var buffer = TerminalBuffers.create(80, 3);
        TerminalRenderFrameReader reader = buffer;
        try (var session = TerminalSession.create(buffer, reader, connector)) {
            byte[] trigger = "\033[24~e".getBytes(StandardCharsets.US_ASCII);
            if (session.submitBytes(trigger) != TerminalInputAdmission.NOT_RUNNING
                || session.submitBytes(trigger, 1) != TerminalInputAdmission.NOT_RUNNING
                || session.submitBytes(trigger, 0, trigger.length) != TerminalInputAdmission.NOT_RUNNING
                || session.submitInput(new TerminalPasteEvent("x")) != TerminalInputAdmission.NOT_RUNNING
                || session.submitInput(java.util.List.of(new TerminalPasteEvent("x"))) != TerminalInputAdmission.NOT_RUNNING)
                throw new AssertionError("Java admission overloads must reject before startup");
            session.readRenderFrame(frame -> {
                if (frame.getColumns() != 80 || frame.getRows() != 3) throw new AssertionError("Java render capability");
            });
        }
    }

    public static void verify() throws Exception {
        var resolver = new io.github.ketraterm.ui.swing.api.TerminalFontResolver() {
            public java.awt.Font resolveFallbackFont(int codePoint, int style, float size) { return null; }
            public java.awt.Font resolveFallbackFont(String text, int style, float size) { return null; }
        };
        var custom = SwingHostServices.create(b -> b.setFontResolver(resolver));
        var cleared = custom.copy(b -> b.setFontResolver(null));
        if (custom.getFontResolver() != resolver || cleared.getFontResolver() != null)
            throw new AssertionError("Selective host service construction and immutable clearing");
        var settings = SwingSettings.create(b -> b.setLineHeight(1.25f));
        try {
            settings.getFallbackFonts().clear();
            throw new AssertionError("Font snapshot is mutable from Java");
        } catch (UnsupportedOperationException expected) {
            // The public Java view is immutable too.
        }
        var settingsDraft = settings.toBuilder();
        settingsDraft.setColumns(120);
        var resized = settingsDraft.build();
        settingsDraft.setColumns(90);
        if (settings.getColumns() != 80 || resized.getColumns() != 120 || resized.getLineHeight() != 1.25f)
            throw new AssertionError("Immutable settings snapshots");
        verifyClipboardReply();
        try (var metadata = SwingTerminal.class.getResourceAsStream("/META-INF/io.github.ketraterm_ketraterm-ui-swing.kotlin_module")) {
            if (metadata == null || metadata.readAllBytes().length == 0) throw new AssertionError("Missing Kotlin metadata");
        }
        var opened = new AtomicInteger();
        SwingHyperlinkAction action = () -> {
            if (!SwingUtilities.isEventDispatchThread()) throw new AssertionError("Action must run on the EDT");
            opened.incrementAndGet();
            return true;
        };
        var services = SwingHostServices.create(draft -> {
draft.setUiDispatcher(TerminalUiDispatcher.SWING);
draft.setClipboardHandler(TerminalClipboardHandler.SYSTEM);
draft.setHyperlinkHandler(uri -> uri.equals("https://example.test/java") && action.open());
});
        SwingUtilities.invokeAndWait(() -> {
            new SwingTerminal().dispose();
            var terminal = new SwingTerminal(SwingSettings::new, services);
            try {
                terminal.setShellSuggestionFailureHandler((request, failure) -> {
                    if (!SwingUtilities.isEventDispatchThread()) throw new AssertionError("Diagnostics must run on EDT");
                });
                var bounds = new Rectangle(1, 2, 3, 4);
                if (terminal.copyCellBounds(0, 0, bounds) || !bounds.isEmpty()) {
                    throw new AssertionError("Unbound geometry must clear caller bounds");
                }
                terminal.setShellSuggestionFailureHandler(null);
                if (!services.getHyperlinkHandler().openHyperlink("https://example.test/java")) {
                    throw new AssertionError("Host navigation callback was not invoked");
                }
                var event = new MouseEvent(terminal, MouseEvent.MOUSE_RELEASED, 0L, 0, 1, 1, 1, 1, 1, false, MouseEvent.BUTTON1);
                if (!action.open(event)) throw new AssertionError("Event activation must use the functional action");
            } finally {
                terminal.dispose();
            }
        });
        if (opened.get() != 2) throw new AssertionError("Expected host and event activation");
    }

    public static void verifyClipboardCallbacks(TerminalClipboardReadRequest request, TerminalClipboardReadAuditEvent audit) {
        if (!request.getSelectionValue().equals("cp") || !audit.getSelectionValue().equals("cp")) {
            throw new AssertionError("Java clipboard selector access");
        }
    }

    private static void verifyClipboardReply() {
        var bytes = new ByteArrayOutputStream();
        TerminalHostOutput output = new TerminalHostOutput() {
            @Override public void writeByte(int value) { bytes.write(value); }
            @Override public void writeBytes(byte[] value, int offset, int length) { bytes.write(value, offset, length); }
            @Override public void writeAscii(String text) { bytes.writeBytes(text.getBytes(StandardCharsets.US_ASCII)); }
            @Override public void writeUtf8(String text) { bytes.writeBytes(text.getBytes(StandardCharsets.UTF_8)); }
        };
        try (var reply = TerminalClipboardReply.prepare("cc", "hello", 16, 64)) {
            if (reply == null) throw new AssertionError("Valid clipboard reply rejected");
            reply.writeTo(output);
            var expected = "\u001b]52;c;aGVsbG8=\u001b\\";
            if (reply.getByteCount() != bytes.size() || !bytes.toString(StandardCharsets.US_ASCII).equals(expected)) {
                throw new AssertionError("Java clipboard reply bytes");
            }
        }
        if (TerminalClipboardReply.prepare("c;bad", "hello", 16, 64) != null) {
            throw new AssertionError("Malformed clipboard selectors accepted");
        }
    }
}

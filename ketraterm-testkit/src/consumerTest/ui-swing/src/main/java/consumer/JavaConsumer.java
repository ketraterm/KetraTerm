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
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.SwingUtilities;

public final class JavaConsumer {
    public static void verify() throws Exception {
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
        var services = new SwingHostServices(
            TerminalUiDispatcher.SWING,
            TerminalClipboardHandler.SYSTEM,
            uri -> uri.equals("https://example.test/java") && action.open()
        );
        SwingUtilities.invokeAndWait(() -> {
            new SwingTerminal().dispose();
            var terminal = new SwingTerminal(SwingSettings::new, services);
            try {
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

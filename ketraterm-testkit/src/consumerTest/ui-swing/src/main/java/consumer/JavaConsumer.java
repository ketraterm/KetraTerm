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

import io.github.ketraterm.ui.swing.api.SwingHostServices;
import io.github.ketraterm.ui.swing.api.SwingHyperlinkAction;
import io.github.ketraterm.ui.swing.api.SwingTerminal;
import io.github.ketraterm.ui.swing.api.TerminalUiDispatcher;
import io.github.ketraterm.ui.swing.settings.SwingSettings;
import io.github.ketraterm.ui.swing.settings.TerminalClipboardHandler;
import java.awt.event.MouseEvent;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.SwingUtilities;

public final class JavaConsumer {
    public static void verify() throws Exception {
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
}

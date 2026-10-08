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

import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionInteraction;
import io.github.ketraterm.ui.swing.host.SwingDialogRequest;
import io.github.ketraterm.ui.swing.host.SwingShellSuggestionTarget;
import io.github.ketraterm.ui.swing.host.SwingTerminalSearchColors;
import io.github.ketraterm.ui.swing.host.SwingCompletionSuggestionProvider;
import io.github.ketraterm.ui.swing.host.SwingCompletionContext;
import io.github.ketraterm.ui.swing.host.SwingHostMessages;
import io.github.ketraterm.completion.api.TerminalCompletionEngine;
import java.awt.Color;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.ListResourceBundle;

public final class JavaConsumer {
    public static SwingHostMessages hostMessages() {
        return SwingHostMessages.forLocale(Locale.FRENCH, new ListResourceBundle() {
            @Override protected Object[][] getContents() {
                return new Object[][] {
                    {"search.placeholder", "Rechercher"},
                    {"search.matchCounter", "{1,number,0} résultats ; actif {0,number,0}"}
                };
            }
        });
    }
    public static SwingCompletionSuggestionProvider labeledProvider(TerminalCompletionEngine engine) {
        return new SwingCompletionSuggestionProvider(engine, () -> SwingCompletionContext.EMPTY, Map.of("host", "Product source"));
    }

    public static SwingTerminalSearchColors lightSearchColors() {
        var original = SwingTerminalSearchColors.create(b -> b.setForeground(Color.BLACK));
        var light = original.copy(b -> b.setPanelBackground(Color.WHITE));
        if (!light.getForeground().equals(Color.BLACK) || original.getPanelBackground().equals(Color.WHITE)) {
            throw new AssertionError("Immutable host colors");
        }
        return light;
    }

    public static final class NativeTarget implements SwingShellSuggestionTarget {
        public SwingShellSuggestionInteraction requested;
        public int hides;
        @Override public void requestSuggestions(SwingShellSuggestionInteraction interaction) { requested = interaction; }
        @Override public void hideSuggestions() { requested = null; hides++; }
    }
    public static void verify() {
        var messages = hostMessages();
        if (!messages.message("search.placeholder").equals("Rechercher")
            || !messages.message("search.matchCounter", 2, 4).equals("4 résultats ; actif 2")
            || !messages.message("menu.copy").equals("Copy")) throw new AssertionError("Message bundle contract");
        var request = new SwingDialogRequest("Title", "<hello>", SwingDialogRequest.Severity.WARNING, List.of("Allow", "Deny"), 1);
        if (request.getDefaultOption() != 1 || !request.htmlMessage().contains("&lt;hello&gt;")) throw new AssertionError("Dialog contract");
        try { request.getOptions().set(0, "Changed"); throw new AssertionError("Mutable options"); }
        catch (UnsupportedOperationException expected) { }
    }
}


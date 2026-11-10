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
import io.github.ketraterm.ui.swing.api.SwingTerminalMiddleClickPasteHandler;
import io.github.ketraterm.ui.swing.api.SwingTerminalMiddleClickPasteRequest;
import io.github.ketraterm.ui.swing.api.TerminalUiDispatcher;
import io.github.ketraterm.ui.swing.settings.SwingSettings;
import io.github.ketraterm.ui.swing.settings.SwingShellSuggestionStyle;
import io.github.ketraterm.ui.swing.settings.SwingPasteSource;
import io.github.ketraterm.ui.swing.settings.TerminalClipboardHandler;
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestion;
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionAcceptanceResult;
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionFeedback;
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionFeedbackKind;
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionRequest;
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionTrigger;
import java.awt.event.MouseEvent;
import java.awt.Rectangle;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.ArrayList;
import java.util.List;
import javax.swing.SwingUtilities;

public final class JavaConsumer {
    public static boolean showSuffix(SwingTerminal view, TerminalCommandEditContext expected, String suffix) {
        return view.presentInlineShellSuggestion(expected, suffix);
    }
    public static boolean showColoredSuffix(SwingTerminal view, TerminalCommandEditContext expected, String suffix, int color) {
        return view.presentInlineShellSuggestion(expected, suffix, color);
    }
    public static void clearInline(SwingTerminal view) { view.clearInlineShellSuggestion(); }
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

    public static void verifyMiddleClickPasteRequest(SwingTerminalMiddleClickPasteRequest request, SwingTerminal terminal) {
        if (request.getTerminal() != terminal || request.getSource() != SwingPasteSource.PRIMARY_SELECTION
            || request.getX() != 1 || request.getY() != 1 || request.getForcedByShift())
            throw new AssertionError("Java middle-click request must snapshot the local primary-selection gesture");
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
            session.start();
            session.readRenderFrame(frame -> {
                if (frame.getColumns() != 80 || frame.getRows() != 3) throw new AssertionError("Java render capability");
            });
        }
    }

    public static void verify() throws Exception {
        if (!new SwingSettings().getAlternateScreenWheelToArrowEnabled())
            throw new AssertionError("Java alternate-screen wheel input must be enabled by default");
        var interactionSettings = SwingSettings.create(draft -> {
            draft.setSmoothScrolling(false);
            draft.setMouseReportingEnabled(false);
            draft.setAlternateScreenWheelToArrowEnabled(false);
            draft.setCopyOnSelection(true);
            draft.setMiddleClickPaste(true);
            draft.setMiddleClickPasteSource(SwingPasteSource.PRIMARY_SELECTION);
            draft.setShellSuggestionStyle(new SwingShellSuggestionStyle(
                0xFF202124, 0xFFE8EAED, 0xFF334455, 0xFFFFFFFF,
                0xFF82AFFC, 0xFFBBDDFF, 0xFF202124, 0xFF334455,
                0xFF666B73, 0xFFADB2BC, 0xFFABCDEF, 0xFFFEDCBA));
            draft.setColumnSpacing(-1);
            draft.setSelectionStyle(new io.github.ketraterm.ui.swing.settings.SwingSelectionStyle(0x804B6EAF, 0xFFFFFFFF));
            draft.setInactiveSelectionStyle(new io.github.ketraterm.ui.swing.settings.SwingSelectionStyle(0, null, true));
            draft.setSearchMatchStyle(new io.github.ketraterm.ui.swing.settings.SwingSearchMatchStyle(0x554B6EAF));
            draft.setSearchActiveMatchStyle(new io.github.ketraterm.ui.swing.settings.SwingSearchMatchStyle(0xFFFFC857, 0xFF161616, 0xFFFFFFFF));
            draft.setMinimumContrastRatio(4.5);
            draft.setFaintOpacity(0.75);
            draft.setShellIntegrationPromptDotHoverColor(0xFFFFFFFF);
            draft.setShellIntegrationFailedPromptDotHoverColor(0xFFFF8A8A);
            draft.setShellIntegrationPromptDotHoverHaloColor(0x404B6EAF);
            draft.setShellIntegrationFailedPromptDotHoverHaloColor(0x40E74856);
            draft.setShellIntegrationPromptDividerColor(0x304B6EAF);
            draft.setShellIntegrationPromptDividerHoverColor(0xFF4B6EAF);
        });
        var copiedSettings = interactionSettings.toBuilder().build();
        if (!new SwingSettings().getSmoothScrolling() || copiedSettings.getSmoothScrolling())
            throw new AssertionError("Java smooth scrolling settings did not preserve defaults or copying");
        if (copiedSettings.getShellSuggestionStyle().getArgumentHintForeground() != 0xFFABCDEF
            || copiedSettings.getShellSuggestionStyle().getSelectionArgumentHintForeground() != 0xFFFEDCBA
            || copiedSettings.getShellSuggestionStyle().getMatchForeground() != 0xFF82AFFC)
            throw new AssertionError("Java suggestion style did not survive copying");
        if (copiedSettings.getShellIntegrationPromptDotHoverColor() != 0xFFFFFFFF
            || copiedSettings.getShellIntegrationFailedPromptDotHoverColor() != 0xFFFF8A8A
            || copiedSettings.getShellIntegrationPromptDotHoverHaloColor() != 0x404B6EAF
            || copiedSettings.getShellIntegrationFailedPromptDotHoverHaloColor() != 0x40E74856
            || copiedSettings.getShellIntegrationPromptDividerColor() != 0x304B6EAF
            || copiedSettings.getShellIntegrationPromptDividerHoverColor() != 0xFF4B6EAF)
            throw new AssertionError("Java prompt colors did not survive copying");
        var automaticPromptColors = copiedSettings.copy(draft -> {
            draft.setShellIntegrationPromptDividerColor(null);
            draft.setShellIntegrationPromptDotHoverHaloColor(0);
        });
        if (automaticPromptColors.getShellIntegrationPromptDividerColor() != null
            || automaticPromptColors.getShellIntegrationPromptDotHoverHaloColor() != 0)
            throw new AssertionError("Java prompt colors lost null or transparent values");
        if (copiedSettings.getMinimumContrastRatio() != 4.5 || copiedSettings.getFaintOpacity() != 0.75
            || !copiedSettings.getInactiveSelectionStyle().getReverseVideo()
            || copiedSettings.getSelectionBackground() != copiedSettings.getSelectionStyle().getBackground())
            throw new AssertionError("Java color policies did not survive copying");
        var parameterSettings = copiedSettings.copy(draft -> draft.setShellSuggestionAutomaticMode(
                io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionAutomaticMode.PARAMETERS_ONLY));
        if (parameterSettings.getShellSuggestionAutomaticMode()
                != io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionAutomaticMode.PARAMETERS_ONLY
                || new io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionRequest("git", 3, false).getIncludeCommandNames())
            throw new AssertionError("Java automatic completion mode was lost");
        if (new SwingSettings().getMinimumContrastRatio() != 3.0)
            throw new AssertionError("Java default contrast policy changed");
        var paletteSelection = copiedSettings.copy(draft -> draft.resetSelectionStyle());
        if (paletteSelection.equals(copiedSettings)
            || !paletteSelection.equals(paletteSelection.copy(draft -> draft.resetSelectionStyle())))
            throw new AssertionError("Java selection reset did not preserve the default policy");
        if (copiedSettings.getSearchMatchBackground() != copiedSettings.getSearchMatchStyle().getBackground()
            || copiedSettings.getSearchActiveMatchStyle().getForeground() != 0xFF161616
            || copiedSettings.getSearchActiveMatchStyle().getBorder() != 0xFFFFFFFF)
            throw new AssertionError("Java search match colors did not survive copying");
        var searchSettings = copiedSettings.copy(draft -> draft.setSearchActiveMatchBackground(0));
        if (searchSettings.getSearchActiveMatchBackground() != 0
            || !searchSettings.getSearchActiveMatchStyle().getForeground().equals(copiedSettings.getSearchActiveMatchStyle().getForeground())
            || !searchSettings.getSearchActiveMatchStyle().getBorder().equals(copiedSettings.getSearchActiveMatchStyle().getBorder()))
            throw new AssertionError("Java search background accessor discarded text or border colors");
        if (copiedSettings.getMouseReportingEnabled() || !copiedSettings.getCopyOnSelection()
            || copiedSettings.getAlternateScreenWheelToArrowEnabled()
            || !copiedSettings.getMiddleClickPaste() || copiedSettings.getColumnSpacing() != -1
            || copiedSettings.getMiddleClickPasteSource() != SwingPasteSource.PRIMARY_SELECTION
            || !interactionSettings.equals(copiedSettings)
            || interactionSettings.hashCode() != copiedSettings.hashCode())
            throw new AssertionError("Java interaction settings did not survive copying");
        var arrowSettings = interactionSettings.copy(draft -> draft.setAlternateScreenWheelToArrowEnabled(true));
        if (!arrowSettings.getAlternateScreenWheelToArrowEnabled()
            || interactionSettings.getAlternateScreenWheelToArrowEnabled()
            || interactionSettings.toBuilder().getAlternateScreenWheelToArrowEnabled()
            || arrowSettings.equals(interactionSettings))
            throw new AssertionError("Java alternate-screen wheel input copy changed the original snapshot");
        var condensedSettings = interactionSettings.copy(draft -> draft.setColumnSpacing(-2));
        if (condensedSettings.getColumnSpacing() != -2 || interactionSettings.getColumnSpacing() != -1)
            throw new AssertionError("Java condensed spacing copy changed the original snapshot");
        var ordinaryPaste = interactionSettings.copy(draft -> draft.setMiddleClickPasteSource(SwingPasteSource.CLIPBOARD));
        if (ordinaryPaste.getMiddleClickPasteSource() != SwingPasteSource.CLIPBOARD
            || interactionSettings.getMiddleClickPasteSource() != SwingPasteSource.PRIMARY_SELECTION)
            throw new AssertionError("Java paste source copy changed the original snapshot");
        var resolver = new io.github.ketraterm.ui.swing.api.TerminalFontResolver() {
            public java.awt.Font resolveFallbackFont(int codePoint, int style, float size) { return null; }
            public java.awt.Font resolveFallbackFont(String text, int style, float size) { return null; }
        };
        SwingTerminalMiddleClickPasteHandler pasteHandler = request -> request.cancel();
        var custom = SwingHostServices.create(b -> {
            b.setFontResolver(resolver);
            b.setMiddleClickPasteHandler(pasteHandler);
        });
        var customDraft = custom.toBuilder();
        customDraft.setMiddleClickPasteHandler(null);
        var cleared = custom.copy(b -> {
            b.setFontResolver(null);
            b.setMiddleClickPasteHandler(null);
        });
        if (custom.getFontResolver() != resolver || cleared.getFontResolver() != null
            || custom.getMiddleClickPasteHandler() != pasteHandler || cleared.getMiddleClickPasteHandler() != null
            || customDraft.build().getMiddleClickPasteHandler() != null)
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
        var suggestionAttempts = new AtomicInteger();
        var suggestionFeedback = new ArrayList<SwingShellSuggestionFeedback>();
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
            var terminal = new SwingTerminal(() -> SwingSettings.create(draft -> {
                draft.setSmartSuggestionsEnabled(true);
                draft.setColumnSpacing(-1);
            }), services);
            try {
                var interaction = terminal.beginShellSuggestionInteraction(
                    new SwingShellSuggestionRequest("git st", 6),
                    SwingShellSuggestionTrigger.EXPLICIT,
                    suggestionFeedback::add,
                    request -> acceptance -> {
                        suggestionAttempts.incrementAndGet();
                        return SwingShellSuggestionAcceptanceResult.STALE_CONTEXT;
                    });
                if (interaction == null) throw new AssertionError("Custom editing target was not captured");
                var candidate = new SwingShellSuggestion("status", 4, 6, "native", "SUBCOMMAND", "status", "",
                    io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionAccentRole.COMMAND,
                    io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionMatchRanges.EMPTY,
                    "Native", null, null, "[path]", false);
                if (candidate.getBackslashPathSeparator()) throw new AssertionError("Literal backslash presentation was lost");
                if (!"git status".equals(io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionKt.commandTextAfterReplacement(
                    candidate, new SwingShellSuggestionRequest("git st", 6)))) {
                    throw new AssertionError("Argument hint entered command text");
                }
                interaction.publish(List.of(candidate));
                var publication = interaction.getSnapshot();
                if (interaction.tryAccept(publication, 0) != SwingShellSuggestionAcceptanceResult.STALE_CONTEXT
                    || interaction.tryAccept(publication, 0) != SwingShellSuggestionAcceptanceResult.STALE_CONTEXT
                    || suggestionAttempts.get() != 1 || suggestionFeedback.size() != 1) {
                    throw new AssertionError("Detached custom admission must run exactly once");
                }
                var rejected = suggestionFeedback.getFirst();
                if (rejected.getKind() != SwingShellSuggestionFeedbackKind.REJECTED
                    || rejected.getAcceptanceResult() != SwingShellSuggestionAcceptanceResult.STALE_CONTEXT
                    || rejected.getSuggestion() != candidate) {
                    throw new AssertionError("Rejection feedback must report the captured admission result");
                }
                terminal.setShellSuggestionFailureHandler((request, failure) -> {
                    if (!SwingUtilities.isEventDispatchThread()) throw new AssertionError("Diagnostics must run on EDT");
                });
                var bounds = new Rectangle(1, 2, 3, 4);
                if (terminal.copyCellBounds(0, 0, bounds) || !bounds.isEmpty()) {
                    throw new AssertionError("Unbound geometry must clear caller bounds");
                }
                var cell = new java.awt.Point(7, 8);
                if (terminal.copyCellPositionAt(0, 0, cell) || cell.x != -1 || cell.y != -1) {
                    throw new AssertionError("Unbound hit testing must clear caller coordinates");
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

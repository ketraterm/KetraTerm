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
package io.github.ketraterm.ui.swing.api

import io.github.ketraterm.ui.swing.api.TerminalUiDispatcher.Companion.SWING
import io.github.ketraterm.ui.swing.settings.TerminalClipboardHandler
import io.github.ketraterm.ui.swing.settings.TerminalHyperlinkHandler
import io.github.ketraterm.ui.swing.suggestion.*
import java.awt.event.KeyEvent
import javax.swing.SwingUtilities

/**
 * Host scheduler used by the reusable Swing terminal for UI-thread work.
 *
 * Implementations must eventually run submitted [Runnable] instances on the
 * Swing Event Dispatch Thread. Standalone hosts normally use [SWING]; IntelliJ
 * hosts can route through the platform application dispatcher while preserving
 * EDT ownership of Swing component state.
 */
public fun interface TerminalUiDispatcher {
    /**
     * Schedules [runnable] for execution on the UI thread.
     *
     * @param runnable work that mutates Swing component state.
     */
    public fun dispatch(runnable: Runnable)

    public companion object {
        /**
         * Dispatcher backed by Swing's standard event queue.
         */
        @JvmField
        public val SWING: TerminalUiDispatcher =
            TerminalUiDispatcher { runnable ->
                SwingUtilities.invokeLater(runnable)
            }
    }
}

/**
 * Host-owned keyboard action hook for [SwingTerminal].
 *
 * The reusable terminal asks this hook before encoding key presses for the
 * shell. Returning `true` means the host handled the key as application policy
 * and the terminal must not send it to the session. Returning `false` passes
 * the key through to terminal input handling.
 */
public fun interface SwingTerminalHostKeyHandler {
    /**
     * Handles a key press before terminal input encoding.
     *
     * @param event Swing key event owned by the EDT.
     * @return `true` when the host handled and consumed the key.
     */
    public fun handleKeyPressed(event: KeyEvent): Boolean

    public companion object {
        /**
         * Handler that never claims keys.
         */
        @JvmField
        public val NONE: SwingTerminalHostKeyHandler = SwingTerminalHostKeyHandler { false }
    }
}

/**
 * Host-provided non-render services for [SwingTerminal].
 *
 * These services are intentionally kept out of row painters. Rendering consumes
 * immutable settings and render-cache snapshots, while host integrations supply
 * scheduling, clipboard, and explicit hyperlink activation policy here.
 * Use [create] and [copy] for selective construction and immutable updates.
 * Service references remain host-owned; building or copying never starts or closes them.
 * View-lifetime services such as suggestion diagnostics use EDT methods on [SwingTerminal].
 *
 * @property uiDispatcher scheduler for UI-thread component work.
 * @property clipboardHandler host clipboard adapter for copy and paste actions.
 * @property hyperlinkHandler host handler for terminal-authored hyperlink activation,
 * following the gesture policy in Swing settings.
 * @property hyperlinkDetector host detector for links discovered from the
 * retained terminal content. Binding-owned detection runs outside paint and mouse
 * movement, continues while temporarily hidden, and invokes actions only after explicit activation.
 * @property viewportListener host scrollbar adapter notified when the terminal
 * scrollback viewport changes.
 * @property scrollbarOverlayEnabled whether the reusable component should draw
 * and handle its own overlay scrollbar. Hosts that install a native external
 * scrollbar should set this to `false`.
 * @property shellSuggestionProvider host provider queried for bounded
 * command-line suggestion snapshots.
 * @property shellSuggestionHandler host callback invoked after the user accepts
 * a shell suggestion from the reusable popup.
 * @property shellSuggestionFeedbackHandler host callback invoked when the user
 * accepts or explicitly dismisses a shell suggestion.
 * @property shellSuggestionKeymap host-owned mapping from Swing key events to
 * semantic suggestion actions. Standalone hosts may retain the standard map;
 * platform integrations should resolve their active application keymap.
 * @property shellSuggestionViewFactory host-owned suggestion presentation
 * factory. It changes visuals only; the reusable controller retains navigation,
 * acceptance, dismissal, and feedback semantics.
 * @property hostKeyHandler host-owned keyboard action policy evaluated before
 * terminal input encoding.
 * @property contextMenuHandler host-owned right-click popup policy. The
 * reusable terminal invokes it only when terminal UI owns the right-click
 * gesture; application mouse reporting takes precedence unless Shift is held.
 * @property fontResolver custom host font resolver policy.
 */
public class SwingHostServices private constructor(
    builder: Builder,
) {
    /** Creates a validated snapshot with default values. */
    public constructor() : this(Builder())

    public val uiDispatcher: TerminalUiDispatcher = builder.uiDispatcher
    public val clipboardHandler: TerminalClipboardHandler = builder.clipboardHandler
    public val hyperlinkHandler: TerminalHyperlinkHandler = builder.hyperlinkHandler
    public val hyperlinkDetector: SwingHyperlinkDetector = builder.hyperlinkDetector
    public val viewportListener: TerminalViewportListener = builder.viewportListener
    public val scrollbarOverlayEnabled: Boolean = builder.scrollbarOverlayEnabled
    public val shellSuggestionProvider: SwingShellSuggestionProvider = builder.shellSuggestionProvider
    public val shellSuggestionHandler: SwingShellSuggestionHandler = builder.shellSuggestionHandler
    public val shellSuggestionFeedbackHandler: SwingShellSuggestionFeedbackHandler = builder.shellSuggestionFeedbackHandler
    public val shellSuggestionKeymap: SwingShellSuggestionKeymap = builder.shellSuggestionKeymap
    public val shellSuggestionViewFactory: SwingShellSuggestionViewFactory = builder.shellSuggestionViewFactory
    public val hostKeyHandler: SwingTerminalHostKeyHandler = builder.hostKeyHandler
    public val contextMenuHandler: SwingTerminalContextMenuHandler = builder.contextMenuHandler
    public val fontResolver: TerminalFontResolver? = builder.fontResolver

    /** Returns a detached mutable draft. Builders are caller-confined and never retained by snapshots. */
    public fun toBuilder(): Builder = Builder(this)

    /**
     * Configures a fresh draft synchronously and returns a validated immutable snapshot.
     * Exceptions propagate without changing this snapshot. Supplied services remain host-owned.
     */
    public fun copy(configure: java.util.function.Consumer<Builder>): SwingHostServices = toBuilder().also { configure.accept(it) }.build()

    /** Mutable construction draft. Not thread-safe; [build] never retains this draft. */
    public class Builder internal constructor(
        source: SwingHostServices? = null,
    ) {
        /** Draft value for [SwingHostServices.uiDispatcher]; validated when [build] is called. */
        public var uiDispatcher: TerminalUiDispatcher = source?.uiDispatcher ?: SWING

        /** Draft value for [SwingHostServices.clipboardHandler]; validated when [build] is called. */
        public var clipboardHandler: TerminalClipboardHandler = source?.clipboardHandler ?: TerminalClipboardHandler.SYSTEM

        /** Draft value for [SwingHostServices.hyperlinkHandler]; validated when [build] is called. */
        public var hyperlinkHandler: TerminalHyperlinkHandler = source?.hyperlinkHandler ?: TerminalHyperlinkHandler.SYSTEM

        /** Draft value for [SwingHostServices.hyperlinkDetector]; validated when [build] is called. */
        public var hyperlinkDetector: SwingHyperlinkDetector = source?.hyperlinkDetector ?: SwingHyperlinkDetector.NONE

        /** Draft value for [SwingHostServices.viewportListener]; validated when [build] is called. */
        public var viewportListener: TerminalViewportListener = source?.viewportListener ?: TerminalViewportListener.NONE

        /** Draft value for [SwingHostServices.scrollbarOverlayEnabled]; validated when [build] is called. */
        public var scrollbarOverlayEnabled: Boolean = source?.scrollbarOverlayEnabled ?: true

        /** Draft value for [SwingHostServices.shellSuggestionProvider]; validated when [build] is called. */
        public var shellSuggestionProvider: SwingShellSuggestionProvider =
            source?.shellSuggestionProvider ?: SwingShellSuggestionProvider.NONE

        /** Draft value for [SwingHostServices.shellSuggestionHandler]; validated when [build] is called. */
        public var shellSuggestionHandler: SwingShellSuggestionHandler = source?.shellSuggestionHandler ?: SwingShellSuggestionHandler.NONE

        /** Draft value for [SwingHostServices.shellSuggestionFeedbackHandler]; validated when [build] is called. */
        public var shellSuggestionFeedbackHandler: SwingShellSuggestionFeedbackHandler =
            source?.shellSuggestionFeedbackHandler ?: SwingShellSuggestionFeedbackHandler.NONE

        /** Draft value for [SwingHostServices.shellSuggestionKeymap]; validated when [build] is called. */
        public var shellSuggestionKeymap: SwingShellSuggestionKeymap = source?.shellSuggestionKeymap ?: SwingShellSuggestionKeymap.STANDARD

        /** Draft value for [SwingHostServices.shellSuggestionViewFactory]; validated when [build] is called. */
        public var shellSuggestionViewFactory: SwingShellSuggestionViewFactory =
            source?.shellSuggestionViewFactory ?: SwingShellSuggestionViewFactory.DEFAULT

        /** Draft value for [SwingHostServices.hostKeyHandler]; validated when [build] is called. */
        public var hostKeyHandler: SwingTerminalHostKeyHandler = source?.hostKeyHandler ?: SwingTerminalHostKeyHandler.NONE

        /** Draft value for [SwingHostServices.contextMenuHandler]; validated when [build] is called. */
        public var contextMenuHandler: SwingTerminalContextMenuHandler = source?.contextMenuHandler ?: SwingTerminalContextMenuHandler.NONE

        /** Draft value for [SwingHostServices.fontResolver]; validated when [build] is called. */
        public var fontResolver: TerminalFontResolver? = source?.fontResolver

        /** Validates and freezes current values; later draft changes cannot affect the result. */
        public fun build(): SwingHostServices = SwingHostServices(this)
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is SwingHostServices &&
            uiDispatcher == other.uiDispatcher &&
            clipboardHandler == other.clipboardHandler &&
            hyperlinkHandler == other.hyperlinkHandler &&
            hyperlinkDetector == other.hyperlinkDetector &&
            viewportListener == other.viewportListener &&
            scrollbarOverlayEnabled == other.scrollbarOverlayEnabled &&
            shellSuggestionProvider == other.shellSuggestionProvider &&
            shellSuggestionHandler == other.shellSuggestionHandler &&
            shellSuggestionFeedbackHandler == other.shellSuggestionFeedbackHandler &&
            shellSuggestionKeymap == other.shellSuggestionKeymap &&
            shellSuggestionViewFactory == other.shellSuggestionViewFactory &&
            hostKeyHandler == other.hostKeyHandler &&
            contextMenuHandler == other.contextMenuHandler &&
            fontResolver == other.fontResolver

    override fun hashCode(): Int {
        var result = 1
        result = 31 * result + uiDispatcher.hashCode()
        result = 31 * result + clipboardHandler.hashCode()
        result = 31 * result + hyperlinkHandler.hashCode()
        result = 31 * result + hyperlinkDetector.hashCode()
        result = 31 * result + viewportListener.hashCode()
        result = 31 * result + scrollbarOverlayEnabled.hashCode()
        result = 31 * result + shellSuggestionProvider.hashCode()
        result = 31 * result + shellSuggestionHandler.hashCode()
        result = 31 * result + shellSuggestionFeedbackHandler.hashCode()
        result = 31 * result + shellSuggestionKeymap.hashCode()
        result = 31 * result + shellSuggestionViewFactory.hashCode()
        result = 31 * result + hostKeyHandler.hashCode()
        result = 31 * result + contextMenuHandler.hashCode()
        result = 31 * result + fontResolver.hashCode()
        return result
    }

    public companion object {
        /** Creates a fresh caller-confined draft initialized to defaults. */
        @JvmStatic
        public fun builder(): Builder = Builder()

        /** Configures a draft synchronously and returns one validated immutable snapshot. */
        @JvmStatic
        public fun create(configure: java.util.function.Consumer<Builder>): SwingHostServices =
            builder().also { configure.accept(it) }.build()
    }
}

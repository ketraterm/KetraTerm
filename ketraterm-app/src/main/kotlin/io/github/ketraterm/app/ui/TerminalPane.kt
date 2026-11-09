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
package io.github.ketraterm.app.ui

import io.github.ketraterm.app.config.KetraTermConfig
import io.github.ketraterm.app.config.KetraTermSettings
import io.github.ketraterm.ui.swing.api.SwingHostServices
import io.github.ketraterm.ui.swing.api.SwingTerminal
import io.github.ketraterm.ui.swing.api.SwingTerminalContextMenuHandler
import io.github.ketraterm.ui.swing.api.SwingTerminalContextMenuRequest
import io.github.ketraterm.ui.swing.host.SwingClipboardReadPrompt
import io.github.ketraterm.ui.swing.host.SwingMessageDialogs
import io.github.ketraterm.ui.swing.host.SwingTerminalOverlayPane
import io.github.ketraterm.ui.swing.host.SwingTerminalSearchBar
import io.github.ketraterm.ui.swing.settings.SwingSettings
import io.github.ketraterm.ui.swing.settings.TerminalClipboardHandler
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionKeymap
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionProvider
import io.github.ketraterm.workspace.TerminalWorkspaceTab
import java.awt.BorderLayout
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JPanel

/**
 * Owns one terminal pane in the standalone host.
 *
 * A pane binds a workspace tab's session to one reusable Swing terminal
 * component.
 */
internal class TerminalPane private constructor(
    val tab: TerminalWorkspaceTab,
    val terminal: SwingTerminal,
    val component: JPanel,
    private val settings: KetraTermSettings,
    private val searchBar: SwingTerminalSearchBar,
    val clipboardReadPrompt: SwingClipboardReadPrompt,
    private var completionProvider: SwingShellSuggestionProvider?,
    private val exitBanner: TerminalExitBanner,
) : TerminalPaneActionTarget {
    private val closed = AtomicBoolean()
    private var shortcutController: TerminalPaneShortcutController? = null
    private var fontSizeAdjustment = 0

    private fun currentSettings(): SwingSettings {
        val current = settings.current()
        val size =
            (settings.config.fontSize + fontSizeAdjustment)
                .coerceIn(KetraTermConfig.FONT_SIZE_MIN, KetraTermConfig.FONT_SIZE_MAX)
        return if (size == current.font.size) current else current.copy { it.font = current.font.deriveFont(size.toFloat()) }
    }

    /** Applies pane-local zoom; zero restores the configured font size. */
    fun zoomFont(change: Int) {
        val base = settings.config.fontSize
        val current = (base + fontSizeAdjustment).coerceIn(KetraTermConfig.FONT_SIZE_MIN, KetraTermConfig.FONT_SIZE_MAX)
        fontSizeAdjustment =
            if (change == 0) {
                0
            } else {
                (current.toLong() + change)
                    .coerceIn(KetraTermConfig.FONT_SIZE_MIN.toLong(), KetraTermConfig.FONT_SIZE_MAX.toLong())
                    .toInt() - base
            }
        terminal.reloadSettings()
    }

    fun requestFocus() {
        terminal.requestFocusInWindow()
    }

    fun reloadSettings() {
        terminal.reloadSettings()
        component.background = terminal.background
        searchBar.refreshColors(Chrome.searchColors())
        exitBanner.refreshColors()
        if (!tab.session.isClosed) terminal.setHostPolicy(settings.createHostPolicy())
    }

    fun showExitStatus(
        exitCode: Int?,
        failure: Throwable?,
    ) {
        if (closed.get()) return
        setCompletionProvider(null)
        exitBanner.showExitStatus(exitCode, failure)
        component.revalidate()
        component.repaint()
    }

    fun setCompletionProvider(provider: SwingShellSuggestionProvider?) {
        if (closed.get()) return
        completionProvider = provider
        terminal.setShellSuggestionProvider(provider)
    }

    override fun suggestionsEnabled(): Boolean = settings.config.smartSuggestionsEnabled && terminal.hasShellSuggestionProvider

    override fun hasSelection(): Boolean = terminal.currentSelection() != null

    override fun copySelectionToClipboard(): Boolean = terminal.copySelectionToClipboard()

    override fun pasteClipboardText(): Boolean = terminal.pasteClipboardText()

    override fun selectAll(): Boolean = terminal.selectAll()

    override fun clearScreen(): Boolean = terminal.clearScreen()

    override fun requestShellSuggestions(): Boolean {
        if (!suggestionsEnabled()) return false
        val provider = completionProvider ?: return false
        val interaction = terminal.beginActiveShellSuggestionInteraction() ?: return false
        terminal.presentShellSuggestions(interaction)
        terminal.requestShellSuggestions(interaction, provider)
        return true
    }

    override fun openSearch() {
        searchBar.open()
    }

    override fun scrollPageUp() {
        terminal.scrollViewportBy(
            terminal
                .visibleGridSize()
                .height
                .minus(1)
                .coerceAtLeast(1)
                .toDouble(),
        )
    }

    override fun scrollPageDown() {
        terminal.scrollViewportBy(
            -terminal
                .visibleGridSize()
                .height
                .minus(1)
                .coerceAtLeast(1)
                .toDouble(),
        )
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        val shortcut = shortcutController
        shortcutController = null
        var failure: Throwable? = null
        failure = captureCleanupFailure(failure, clipboardReadPrompt::close)
        failure = captureCleanupFailure(failure, searchBar::close)
        failure = captureCleanupFailure(failure) { shortcut?.dispose() }
        failure = captureCleanupFailure(failure, terminal::dispose)
        failure?.let { throw it }
    }

    internal companion object {
        fun create(
            tab: TerminalWorkspaceTab,
            settings: KetraTermSettings,
            completionProvider: SwingShellSuggestionProvider?,
            onBound: (Throwable?) -> Unit,
            onContextMenu: (TerminalPane, SwingTerminalContextMenuRequest) -> Unit,
            onClose: (TerminalPane) -> Unit,
        ): TerminalPane {
            val shortcutControllerRef = arrayOfNulls<TerminalPaneShortcutController>(1)
            val paneRef = arrayOfNulls<TerminalPane>(1)
            var ownedTerminal: SwingTerminal? = null
            var ownedSearchBar: SwingTerminalSearchBar? = null
            var ownedClipboardReadPrompt: SwingClipboardReadPrompt? = null
            var ownedPane: TerminalPane? = null
            return try {
                val terminal =
                    SwingTerminal(
                        settingsProvider = { paneRef[0]?.currentSettings() ?: settings.current() },
                        hostServices =
                            SwingHostServices.create { draft ->
                                draft.clipboardHandler =
                                    TerminalPasteClipboard(TerminalClipboardHandler.SYSTEM) { request ->
                                        val parent = paneRef[0]?.terminal ?: return@TerminalPasteClipboard false
                                        SwingMessageDialogs.show(parent, request) == 0
                                    }
                                draft.shellSuggestionKeymap = SwingShellSuggestionKeymap.STANDARD
                                draft.hostKeyHandler = { event -> shortcutControllerRef[0]?.handleKeyPressed(event) == true }
                                draft.contextMenuHandler =
                                    SwingTerminalContextMenuHandler { request ->
                                        val pane = paneRef[0] ?: return@SwingTerminalContextMenuHandler false
                                        onContextMenu(pane, request)
                                        true
                                    }
                            },
                    )

                ownedTerminal = terminal
                terminal.setShellSuggestionProvider(completionProvider)
                terminal.bind(tab.session, onBound)

                val searchBar =
                    SwingTerminalSearchBar(terminal).apply {
                        refreshColors(Chrome.searchColors())
                        TerminalSearchShortcuts.install(this)
                    }
                ownedSearchBar = searchBar
                val clipboardReadPrompt =
                    SwingClipboardReadPrompt { message, decide ->
                        SwingMessageDialogs.showModeless(terminal, message, decide)
                    }
                ownedClipboardReadPrompt = clipboardReadPrompt
                val exitBanner = TerminalExitBanner { paneRef[0]?.let(onClose) }
                val component =
                    JPanel(BorderLayout()).apply {
                        background = terminal.background
                        add(terminalPanel(terminal, searchBar), BorderLayout.CENTER)
                        add(exitBanner, BorderLayout.SOUTH)
                    }
                val pane =
                    TerminalPane(
                        tab = tab,
                        terminal = terminal,
                        component = component,
                        settings = settings,
                        searchBar = searchBar,
                        clipboardReadPrompt = clipboardReadPrompt,
                        completionProvider = completionProvider,
                        exitBanner = exitBanner,
                    )
                ownedPane = pane
                pane.shortcutController = TerminalPaneShortcutController(pane, settings)
                shortcutControllerRef[0] = pane.shortcutController
                paneRef[0] = pane
                tab.session.requestRender(scrollbackOffset = 0)
                pane
            } catch (failure: Throwable) {
                var cleanupFailure: Throwable? = failure
                val pane = ownedPane
                if (pane != null) {
                    cleanupFailure = captureCleanupFailure(cleanupFailure, pane::close)
                } else {
                    cleanupFailure = captureCleanupFailure(cleanupFailure) { ownedClipboardReadPrompt?.close() }
                    cleanupFailure = captureCleanupFailure(cleanupFailure) { ownedSearchBar?.close() }
                    cleanupFailure = captureCleanupFailure(cleanupFailure) { ownedTerminal?.dispose() }
                }
                throw requireNotNull(cleanupFailure)
            }
        }

        private fun terminalPanel(
            terminal: SwingTerminal,
            searchBar: SwingTerminalSearchBar,
        ): JPanel =
            SwingTerminalOverlayPane(terminal, searchBar.component).apply {
                background = terminal.background
                border = null
                terminal.border = null
            }
    }
}

internal inline fun captureCleanupFailure(
    previous: Throwable?,
    action: () -> Unit,
): Throwable? =
    try {
        action()
        previous
    } catch (failure: Throwable) {
        previous?.apply {
            if (this !== failure) addSuppressed(failure)
        } ?: failure
    }

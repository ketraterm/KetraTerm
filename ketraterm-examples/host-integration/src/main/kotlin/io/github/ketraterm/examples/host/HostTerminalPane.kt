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
@file:OptIn(ExperimentalTerminalCompletionApi::class, ExperimentalShellSuggestionsApi::class)

package io.github.ketraterm.examples.host

import io.github.ketraterm.completion.api.*
import io.github.ketraterm.completion.host.TerminalCompletionSourceEnvironment
import io.github.ketraterm.completion.host.TerminalLocalFileSystemProvider
import io.github.ketraterm.completion.session.SessionShellCompletionProvider
import io.github.ketraterm.render.api.TerminalColorPalette
import io.github.ketraterm.session.TerminalCommandOutputText
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.session.TerminalSessionState
import io.github.ketraterm.ui.swing.api.SwingHostServices
import io.github.ketraterm.ui.swing.api.SwingTerminal
import io.github.ketraterm.ui.swing.api.SwingTerminalHostKeyHandler
import io.github.ketraterm.ui.swing.host.*
import io.github.ketraterm.ui.swing.settings.SwingSettings
import io.github.ketraterm.ui.swing.settings.TerminalClipboardHandler
import io.github.ketraterm.ui.swing.suggestion.ExperimentalShellSuggestionsApi
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionProvider
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionViewFactory
import io.github.ketraterm.workspace.TerminalProfile
import io.github.ketraterm.workspace.TerminalProfileKind
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.*

/** Owns the view and host chrome. Closing this pane leaves the supplied session open. */
internal class HostTerminalPane(
    private val session: TerminalSession,
    profile: TerminalProfile,
    clipboard: TerminalClipboardHandler = TerminalClipboardHandler.SYSTEM,
) : AutoCloseable {
    init {
        check(SwingUtilities.isEventDispatchThread())
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
    private val shortcuts = SwingTerminalHostShortcutMap.platformDefault()
    private var closed = false
    private var outputJob: Job? = null
    private val status = JLabel("Ctrl+Space requests suggestions. Use the toolbar to change host settings.")
    private var settings =
        SwingSettings.create {
            it.columns = 100
            it.rows = 28
            it.smartSuggestionsEnabled = true
            it.shellSuggestionsEnabled = true
        }
    val terminal =
        SwingTerminal(
            settingsProvider = { settings },
            hostServices =
                SwingHostServices.create {
                    it.clipboardHandler = clipboard
                    it.shellSuggestionViewFactory = SwingShellSuggestionViewFactory(::HostSuggestionList)
                    it.hostKeyHandler =
                        SwingTerminalHostKeyHandler { event ->
                            shortcuts.actionFor(event.keyCode, event.modifiersEx)?.let(::performAction) ?: false
                        }
                },
        )
    private val search = SwingTerminalSearchBar(terminal)
    val component = JPanel(BorderLayout())

    init {
        terminal.setShellSuggestionProvider(localCompletions(session, profile))
        scope.launch {
            session.state.collect { state ->
                if (state is TerminalSessionState.Closed) status.text = "Shell closed. The retained output remains available."
            }
        }
        val toolbar = JPanel(FlowLayout(FlowLayout.LEADING, 4, 2))

        fun button(
            label: String,
            action: () -> Unit,
        ) = JButton(label).apply {
            isFocusable = false
            addActionListener { action() }
            toolbar.add(this)
        }
        button("Copy") { performAction(SwingTerminalHostAction.COPY_SELECTION) }
        button("Paste") { performAction(SwingTerminalHostAction.PASTE_CLIPBOARD) }
        button("Search") { performAction(SwingTerminalHostAction.OPEN_SEARCH) }
        button("Suggestions") { performAction(SwingTerminalHostAction.REQUEST_SUGGESTIONS) }
        button("Copy last output") { copyLastOutput() }
        toolbar.add(
            JCheckBox("Light theme").apply {
                isFocusable = false
                addActionListener { updateSettings(lightTheme = isSelected) }
            },
        )
        toolbar.add(
            JSpinner(SpinnerNumberModel(settings.font.size, 8, 32, 1)).apply {
                toolTipText = "Terminal font size"
                accessibleContext.accessibleName = toolTipText
                addChangeListener { updateSettings(fontSize = (value as Number).toInt()) }
            },
        )
        component.add(toolbar, BorderLayout.NORTH)
        component.add(SwingTerminalOverlayPane(terminal, search.component), BorderLayout.CENTER)
        component.add(status, BorderLayout.SOUTH)
    }

    fun performAction(action: SwingTerminalHostAction): Boolean {
        check(SwingUtilities.isEventDispatchThread())
        if (closed) return false
        return when (action) {
            SwingTerminalHostAction.COPY_SELECTION -> terminal.copySelectionToClipboard()
            SwingTerminalHostAction.PASTE_CLIPBOARD -> terminal.pasteClipboardText()
            SwingTerminalHostAction.OPEN_SEARCH -> {
                search.open()
                true
            }
            SwingTerminalHostAction.REQUEST_SUGGESTIONS -> {
                terminal.requestActiveShellSuggestions()
                true
            }
            SwingTerminalHostAction.SELECT_ALL -> terminal.selectAll()
            SwingTerminalHostAction.SCROLL_PAGE_UP -> {
                terminal.scrollViewportBy(terminal.visibleGridSize().height.toDouble())
                true
            }
            SwingTerminalHostAction.SCROLL_PAGE_DOWN -> {
                terminal.scrollViewportBy(-terminal.visibleGridSize().height.toDouble())
                true
            }
            SwingTerminalHostAction.CLEAR_SCREEN -> false
        }
    }

    fun updateSettings(
        lightTheme: Boolean? = null,
        fontSize: Int? = null,
        onComplete: (Throwable?) -> Unit = ::settingsApplied,
    ) {
        check(SwingUtilities.isEventDispatchThread())
        check(!closed)
        settings =
            settings.copy {
                if (fontSize != null) it.font = Font(Font.MONOSPACED, Font.PLAIN, fontSize)
                if (lightTheme != null) it.palette = if (lightTheme) LIGHT_PALETTE else DARK_PALETTE
            }
        terminal.reloadSettings(onComplete)
        search.refreshColors()
    }

    private fun settingsApplied(failure: Throwable?) {
        status.text = if (failure == null) "Settings applied." else "Settings failed: ${failure.message}"
    }

    suspend fun lastOutput(): TerminalCommandOutputText? =
        withContext(Dispatchers.IO) {
            val id = session.shellIntegrationState.latestCommandRecordId()
            if (id == 0) null else session.commandOutputText(id, maxChars = 8_192, maxRows = 128)
        }

    private fun copyLastOutput() {
        if (outputJob?.isActive == true) return
        outputJob =
            scope.launch {
                try {
                    val output = lastOutput()
                    status.text =
                        when {
                            output == null -> "Completed output is unavailable."
                            !terminal.copyTextToClipboard(output.text) -> "The clipboard request was not accepted."
                            output.truncated || output.historyIncomplete -> "Requested a clipboard copy of the incomplete output tail."
                            else -> "Requested a clipboard copy of the completed output."
                        }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    status.text = "Output read failed: ${failure.message}"
                }
            }
    }

    override fun close() {
        check(SwingUtilities.isEventDispatchThread())
        if (closed) return
        closed = true
        scope.cancel()
        try {
            search.close()
        } finally {
            terminal.dispose()
        }
    }

    private companion object {
        val DARK_PALETTE = TerminalColorPalette()
        val LIGHT_PALETTE =
            TerminalColorPalette(
                defaultForeground = 0xFF202020.toInt(),
                defaultBackground = 0xFFF6F6F6.toInt(),
                cursorForeground = 0xFFFFFFFF.toInt(),
                cursorBackground = 0xFF202020.toInt(),
                isDark = false,
            )
    }
}

private fun localCompletions(
    session: TerminalSession,
    profile: TerminalProfile,
): SwingShellSuggestionProvider {
    val capabilities =
        when (profile.kind) {
            TerminalProfileKind.POWERSHELL -> TerminalShellCapabilities.POWERSHELL
            TerminalProfileKind.GIT_BASH -> TerminalShellCapabilities.POSIX.copy(backslashPathSeparator = true)
            TerminalProfileKind.BASH, TerminalProfileKind.ZSH, TerminalProfileKind.UNIX_SHELL,
            TerminalProfileKind.WSL, TerminalProfileKind.UBUNTU,
            -> TerminalShellCapabilities.POSIX
            else -> TerminalShellCapabilities.PLAIN
        }
    val path = TerminalCompletionSources.path(TerminalLocalFileSystemProvider())
    val engine =
        TerminalCompletionEngines.fromSources(
            sources =
                listOf(
                    TerminalCompletionSourceEntry(
                        TerminalCompletionSource { request, context, limit ->
                            val local =
                                TerminalCompletionSourceEnvironment.resolve(request, context)
                                    ?: return@TerminalCompletionSource emptyList()
                            path.complete(local, context, limit)
                        },
                        TerminalCompletionSourcePrior.DIRECTORY_PATH,
                    ),
                ),
            shellProvider = SessionShellCompletionProvider(session),
        )
    return SwingCompletionSuggestionProvider(engine, contextProvider = {
        SwingCompletionContext(profile.id, session.shellIntegrationState.currentWorkingDirectoryUri(), capabilities)
    })
}

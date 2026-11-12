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
@file:OptIn(
    ExperimentalTerminalCompletionApi::class,
    ExperimentalShellSuggestionsApi::class,
)

package io.github.ketraterm.examples.suggestions

import io.github.ketraterm.completion.api.*
import io.github.ketraterm.completion.host.TerminalCompletionSourceEnvironment
import io.github.ketraterm.completion.host.TerminalLocalFileSystemProvider
import io.github.ketraterm.ui.swing.api.SwingHostServices
import io.github.ketraterm.ui.swing.api.SwingTerminal
import io.github.ketraterm.ui.swing.api.SwingTerminalHostKeyHandler
import io.github.ketraterm.ui.swing.host.SessionShellCompletionProvider
import io.github.ketraterm.ui.swing.host.SwingCompletionContext
import io.github.ketraterm.ui.swing.host.SwingCompletionSuggestionProvider
import io.github.ketraterm.ui.swing.settings.SwingSettings
import io.github.ketraterm.ui.swing.suggestion.ExperimentalShellSuggestionsApi
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionAction
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionKeymap
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionProvider
import io.github.ketraterm.workspace.*
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridLayout
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.util.concurrent.CompletableFuture
import javax.swing.*

/** Owns the window/view on the EDT and the workspace's asynchronous process lifetime. */
internal class SuggestionExampleWindow(
    private val command: List<String>,
) {
    init {
        check(SwingUtilities.isEventDispatchThread())
    }

    private val frame = JFrame("KetraTerm · Independent suggestion providers")
    private val content = JPanel(BorderLayout())
    private val status = JLabel("Starting shell…")
    private val automaticPopup = JCheckBox("Automatic popup", true).apply { isFocusable = false }
    private val automaticInline = JCheckBox("Automatic inline", true).apply { isFocusable = false }
    private var terminal: SwingTerminal? = null
    private var closing = false
    private var settings =
        SwingSettings.create {
            it.columns = 100
            it.rows = 28
            it.smartSuggestionsEnabled = true
            it.shellSuggestionsEnabled = true
            it.inlineShellSuggestionsEnabled = true
            it.shellSuggestionDebounceMillis = 75
            it.inlineShellSuggestionDebounceMillis = 75
        }
    private val workspace =
        TerminalWorkspace(
            object : TerminalWorkspaceListener {
                override fun tabOpened(tab: TerminalWorkspaceTab) {
                    // Workspace startup waits here; the EDT never waits for the workspace or a session lock.
                    val bound = CompletableFuture<Unit>()
                    SwingUtilities.invokeAndWait { attach(tab, bound) }
                    bound.get()
                }

                override fun sessionClosed(
                    tab: TerminalWorkspaceTab,
                    exitCode: Int?,
                    failure: Throwable?,
                ) {
                    SwingUtilities.invokeLater {
                        if (!closing) {
                            terminal?.setShellSuggestionProvider(null)
                            terminal?.setInlineShellSuggestionProvider(null)
                            status.text =
                                if (failure == null) {
                                    "Shell exited (${exitCode ?: "unknown status"})."
                                } else {
                                    "Shell failed: ${failure.message}"
                                }
                        }
                    }
                }
            },
        )

    init {
        frame.defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
        frame.addWindowListener(
            object : WindowAdapter() {
                override fun windowClosing(event: WindowEvent) = close()
            },
        )
        val toolbar =
            JPanel(FlowLayout(FlowLayout.LEADING)).apply {
                add(automaticPopup)
                add(automaticInline)
                add(JLabel("Inline: local simulation, 750 ms"))
            }
        for (toggle in listOf(automaticPopup, automaticInline)) {
            toggle.addActionListener {
                settings =
                    settings.copy {
                        it.shellSuggestionsEnabled = automaticPopup.isSelected
                        it.inlineShellSuggestionsEnabled = automaticInline.isSelected
                    }
                terminal?.reloadSettings()
            }
        }
        val footer =
            JPanel(GridLayout(0, 1)).apply {
                border = BorderFactory.createEmptyBorder(6, 8, 6, 8)
                add(
                    JLabel(
                        "Ctrl+Space: popup · Ctrl+Shift+Space: inline",
                    ),
                )
                add(
                    JLabel(
                        "Tab/→: accept inline · Ctrl+→: next word · ↑/↓: select popup · Escape: dismiss",
                    ),
                )
                add(status)
            }
        frame.add(toolbar, BorderLayout.NORTH)
        frame.add(content, BorderLayout.CENTER)
        frame.add(footer, BorderLayout.SOUTH)
        frame.setSize(1_000, 650)
        frame.setLocationRelativeTo(null)
    }

    fun show() {
        frame.isVisible = true
        val accepted =
            workspace.openTabAsync(
                profile = { TerminalProfileRegistry().initialProfile(command) },
                options =
                    TerminalWorkspaceOpenOptions.create {
                        it.columns = 100
                        it.rows = 28
                        it.maxHistory = 2_000
                    },
            ) { result ->
                SwingUtilities.invokeLater {
                    if (!closing) {
                        result.fold(
                            onSuccess = {
                                status.text = "${it.profile.displayName} · Try echo ext or git st. " +
                                    "Nothing is sent to a network service."
                            },
                            onFailure = { failure ->
                                terminal?.dispose()
                                terminal = null
                                status.text = "Startup failed: ${failure.message}"
                            },
                        )
                    }
                }
            }
        check(accepted) { "Workspace rejected its initial launch" }
    }

    private fun attach(
        tab: TerminalWorkspaceTab,
        bound: CompletableFuture<Unit>,
    ) {
        if (closing) {
            bound.completeExceptionally(IllegalStateException("Window is closing"))
            return
        }
        try {
            val popup = localCompletions(tab)
            val view =
                SwingTerminal(
                    settingsProvider = { settings },
                    hostServices =
                        SwingHostServices.create { draft ->
                            draft.hostKeyHandler =
                                SwingTerminalHostKeyHandler { event ->
                                    val target = event.component as? SwingTerminal ?: return@SwingTerminalHostKeyHandler false
                                    when (KeyStroke.getKeyStrokeForEvent(event)) {
                                        POPUP_SHORTCUT -> requestPopup(target, popup)
                                        INLINE_SHORTCUT -> target.requestActiveInlineShellSuggestions() != null
                                        else -> false
                                    }
                                }
                        },
                )
            terminal = view
            view.setInlineShellSuggestionKeymap { event ->
                if (KeyStroke.getKeyStrokeForEvent(event) == NEXT_WORD_SHORTCUT) {
                    SwingShellSuggestionAction.ACCEPT_NEXT_WORD
                } else {
                    SwingShellSuggestionKeymap.INLINE_STANDARD.actionFor(event)
                }
            }
            view.setShellSuggestionProvider(popup)
            view.setInlineShellSuggestionProvider(
                ExternalInlineSuggestions(tab.session, ::simulateCompletion, shellKind = tab.profile.kind),
            )
            content.add(view, BorderLayout.CENTER)
            content.revalidate()
            content.repaint()
            view.bind(tab.session) { failure ->
                if (failure == null) {
                    bound.complete(Unit)
                    view.requestFocusInWindow()
                } else {
                    bound.completeExceptionally(failure)
                }
            }
        } catch (failure: Exception) {
            bound.completeExceptionally(failure)
        }
    }

    private fun close() {
        if (closing) return
        closing = true
        status.text = "Closing shell…"
        automaticPopup.isEnabled = false
        automaticInline.isEnabled = false
        try {
            terminal?.dispose()
        } finally {
            check(
                workspace.closeAsync { result ->
                    result.exceptionOrNull()?.let {
                        System.getLogger("suggestion-example").log(System.Logger.Level.WARNING, "Cleanup failed", it)
                    }
                    SwingUtilities.invokeLater { frame.dispose() }
                },
            ) { "Workspace shutdown was already requested" }
        }
    }

    private companion object {
        val POPUP_SHORTCUT: KeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, InputEvent.CTRL_DOWN_MASK)
        val INLINE_SHORTCUT: KeyStroke =
            KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK)
        val NEXT_WORD_SHORTCUT: KeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, InputEvent.CTRL_DOWN_MASK)
    }
}

private fun requestPopup(
    view: SwingTerminal,
    provider: SwingShellSuggestionProvider,
): Boolean {
    val interaction = view.beginActiveShellSuggestionInteraction() ?: return false
    view.presentShellSuggestions(interaction)
    view.requestShellSuggestions(interaction, provider)
    return true
}

private fun localCompletions(tab: TerminalWorkspaceTab): SwingShellSuggestionProvider {
    val capabilities =
        when (tab.profile.kind) {
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
            shellProvider = SessionShellCompletionProvider(tab.session),
        )
    return SwingCompletionSuggestionProvider(engine, contextProvider = {
        SwingCompletionContext(tab.profile.id, tab.currentWorkingDirectoryUri, capabilities)
    })
}

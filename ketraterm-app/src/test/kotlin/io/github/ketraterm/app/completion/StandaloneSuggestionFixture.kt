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
package io.github.ketraterm.app.completion

import io.github.ketraterm.app.config.KetraTermConfigManager
import io.github.ketraterm.app.config.KetraTermSettings
import io.github.ketraterm.app.ui.captureCleanupFailure
import io.github.ketraterm.input.event.TerminalKey
import io.github.ketraterm.input.event.TerminalKeyEvent
import io.github.ketraterm.input.event.TerminalTextReplacementEvent
import io.github.ketraterm.session.TerminalInputAdmission
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.session.TerminalSessionState
import io.github.ketraterm.session.TerminalStartupCommand
import io.github.ketraterm.ui.swing.api.SwingHostServices
import io.github.ketraterm.ui.swing.api.SwingTerminal
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionFeedback
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionFeedbackHandler
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionFeedbackKind
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionProvider
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionRequest
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionSource
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionView
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionViewFactory
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionViewSnapshot
import io.github.ketraterm.workspace.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.swing.Swing
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.swing.JComponent
import javax.swing.JFrame
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Real standalone wiring and component input, with bounded event waits and failure-safe ownership. */
internal class StandaloneSuggestionFixture private constructor(
    val tab: TerminalWorkspaceTab,
    val provider: SwingShellSuggestionProvider,
    settings: KetraTermSettings,
) {
    val session get() = tab.session
    private val publications = Channel<Pair<String?, SwingShellSuggestionViewSnapshot>>(Channel.UNLIMITED)
    private val feedbackEvents = Channel<SwingShellSuggestionFeedback>(Channel.UNLIMITED)
    private val bound = CompletableDeferred<Unit>()
    private val focused = CompletableDeferred<Unit>()
    private var popup: JComponent? = null
    private var window: JFrame? = null
    private var invalidationRevision = 0L
    private var activeSnapshotRevision = -1L
    private val terminal =
        SwingTerminal(
            settingsProvider = { settings.current(tab.profile.kind) },
            hostServices =
                SwingHostServices.create { draft ->
                    draft.shellSuggestionViewFactory =
                        SwingShellSuggestionViewFactory { listener ->
                            val view = SwingShellSuggestionViewFactory.DEFAULT.create(listener)
                            popup = view.component
                            object : SwingShellSuggestionView by view {
                                override fun update(snapshot: SwingShellSuggestionViewSnapshot) {
                                    view.update(snapshot)
                                    if (snapshot.visibleSuggestions.isNotEmpty()) {
                                        activeSnapshotRevision = invalidationRevision
                                    } else {
                                        activeSnapshotRevision = -1L
                                    }
                                    publications.trySend(session.activeShellCommandLine()?.commandText to snapshot).getOrThrow()
                                }
                            }
                        }
                },
        )

    private suspend fun start(decorateProvider: (SwingShellSuggestionProvider) -> SwingShellSuggestionProvider) {
        withContext(Dispatchers.Swing) {
            terminal.preferredSize = terminal.preferredGridSize(COLUMNS, ROWS)
            val effectiveProvider = decorateProvider(provider)
            val fixtureProvider =
                object : SwingShellSuggestionProvider {
                    override fun suggestions(request: SwingShellSuggestionRequest) = effectiveProvider.suggestions(request)

                    override fun open(request: SwingShellSuggestionRequest): SwingShellSuggestionSource {
                        val source = effectiveProvider.open(request)
                        val originalFeedback = source.feedbackHandler
                        return SwingShellSuggestionSource(
                            suggestions = source.suggestions,
                            feedbackHandler =
                                SwingShellSuggestionFeedbackHandler { feedback ->
                                    originalFeedback.onSuggestionFeedback(feedback)
                                    feedbackEvents.trySend(feedback)
                                },
                        )
                    }
                }
            terminal.setShellSuggestionProvider(fixtureProvider)
            terminal.addShellSuggestionInvalidationListener {
                invalidationRevision++
            }
            terminal.bind(session) { failure ->
                if (failure == null) bound.complete(Unit) else bound.completeExceptionally(failure)
            }
            terminal.addFocusListener(
                object : FocusAdapter() {
                    override fun focusGained(event: FocusEvent) {
                        focused.complete(Unit)
                    }
                },
            )
            val frame = JFrame("KetraTerm suggestion smoke: ${tab.profile.id}")
            window = frame
            frame.defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
            frame.contentPane.add(terminal)
            frame.pack()
            frame.isVisible = true
            terminal.requestFocusInWindow()
        }
        withTimeout(15.seconds) { bound.await() }
        withTimeout(15.seconds) { focused.await() }
        awaitLine("")
    }

    suspend fun awaitPopup(
        line: String,
        replacement: String,
    ): SwingShellSuggestionViewSnapshot {
        var lastUpdate: Pair<String?, SwingShellSuggestionViewSnapshot>? = null
        try {
            return withTimeout(15.seconds) {
                var snapshot: SwingShellSuggestionViewSnapshot
                do {
                    val update = publications.receive()
                    lastUpdate = update
                    snapshot = update.second
                } while (update.first != line ||
                    snapshot.visibleSuggestions.none {
                        it.replacementText == replacement && it.replacementEndOffset == line.length
                    }
                )
                snapshot
            }
        } catch (failure: TimeoutCancellationException) {
            val state =
                withContext(Dispatchers.Swing) {
                    "focused=${terminal.isFocusOwner}, popup=${terminal.currentShellSuggestionState()}"
                }
            throw AssertionError("Expected '$replacement' for '$line'; $state; last update=$lastUpdate\n${screenText()}", failure)
        }
    }

    suspend fun awaitLine(line: String) {
        try {
            withTimeout(15.seconds) {
                awaitEditableLine(session, line)
            }
        } catch (failure: TimeoutCancellationException) {
            throw AssertionError(
                "Expected '$line', observed ${session.activeShellCommandLine()}, session ${session.state.value}\n${screenText()}",
                failure,
            )
        }
    }

    private fun screenText(): String =
        buildString {
            session.readRenderFrame { frame ->
                appendLine("Cursor: ${frame.cursor.column}, ${frame.cursor.row}")
                val codeWords = IntArray(frame.columns)
                val flags = IntArray(frame.columns)
                for (row in 0 until frame.rows) {
                    frame.copyTextLine(row, codeWords, flags = flags)
                    val text = buildString { codeWords.forEach { append(if (it in 32..126) it.toChar() else ' ') } }.trimEnd()
                    if (text.isNotEmpty()) appendLine("$row: $text")
                }
            }
        }

    suspend fun awaitExecution(line: String) =
        withTimeout(15.seconds) {
            session.shellIntegrationState.revision.first {
                val state = session.shellIntegrationState
                val metadata = state.commandMetadata(state.latestCommandRecordId())
                metadata?.commandText == line && metadata.finishedAtEpochMillis != null
            }
            val state = session.shellIntegrationState
            assertEquals(0, state.commandMetadata(state.latestCommandRecordId())?.exitCode, line)
        }

    suspend fun type(text: String) =
        withContext(Dispatchers.Swing) {
            for (character in text) {
                val event = KeyEvent(terminal, KeyEvent.KEY_TYPED, 0, 0, KeyEvent.VK_UNDEFINED, character)
                terminal.keyListeners.forEach { it.keyTyped(event) }
            }
        }

    suspend fun key(code: Int) = withContext(Dispatchers.Swing) { press(code) }

    suspend fun requestSuggestions() = withContext(Dispatchers.Swing) { terminal.requestActiveShellSuggestions() }

    private fun press(code: Int) {
        terminal.keyListeners.forEach { it.keyPressed(KeyEvent(terminal, KeyEvent.KEY_PRESSED, 0, 0, code, KeyEvent.CHAR_UNDEFINED)) }
        terminal.keyListeners.forEach { it.keyReleased(KeyEvent(terminal, KeyEvent.KEY_RELEASED, 0, 0, code, KeyEvent.CHAR_UNDEFINED)) }
    }

    suspend fun selectAndAccept(
        replacement: String,
        execute: Boolean = false,
    ) = withTimeout(15.seconds) {
        while (withContext(Dispatchers.Swing) {
                invalidationRevision > activeSnapshotRevision ||
                    terminal.currentShellSuggestionState().count == 0
            }
        ) {
            val update = publications.receive()
            if (update.second.visibleSuggestions.any { it.replacementText == replacement }) {
                break
            }
        }
        withContext(Dispatchers.Swing) {
            val count = terminal.currentShellSuggestionState().count
            assertTrue(count > 0)
            var remaining = count
            while (terminal.currentShellSuggestionState().selectedSuggestion?.replacementText != replacement && remaining-- > 0) {
                press(KeyEvent.VK_DOWN)
            }
            assertEquals(replacement, terminal.currentShellSuggestionState().selectedSuggestion?.replacementText)
            press(KeyEvent.VK_TAB)
            if (execute) press(KeyEvent.VK_ENTER)
        }
        val feedback = feedbackEvents.receive()
        assertEquals(SwingShellSuggestionFeedbackKind.ACCEPTED, feedback.kind, "Suggestion acceptance failed: ${feedback.acceptanceResult}")
    }

    suspend fun capturePopup(output: Path) {
        val image =
            withContext(Dispatchers.Swing) {
                val surface = assertNotNull(popup)
                assertTrue(surface.width > 0 && surface.height > 0)
                val image = BufferedImage(surface.width, surface.height, BufferedImage.TYPE_INT_ARGB)
                val graphics = image.createGraphics()
                try {
                    surface.printAll(graphics)
                } finally {
                    graphics.dispose()
                }
                image
            }
        withContext(Dispatchers.IO) {
            Files.createDirectories(output.parent)
            ImageIO.write(image, "png", output.toFile())
        }
    }

    companion object {
        private const val COLUMNS = 100
        private const val ROWS = 24

        internal suspend fun awaitEditableLine(
            session: TerminalSession,
            line: String,
        ) {
            session.renderGeneration.first {
                val edit = session.captureCommandEdit() ?: return@first false
                val snapshot = edit.commandLine
                edit.cancel()
                snapshot.commandText == line && snapshot.cursorOffset == line.length && !snapshot.requiresShellConfirmation
            }
        }

        suspend fun withSession(
            profile: TerminalProfile,
            directory: Path,
            decorateProvider: (SwingShellSuggestionProvider) -> SwingShellSuggestionProvider = { it },
            scenario: suspend StandaloneSuggestionFixture.() -> Unit,
        ) {
            val registry = StandaloneCompletionRegistry.create(directory.resolve("learning.json"), persistenceEnabled = false)
            var workspace: TerminalWorkspace? = null
            var fixture: StandaloneSuggestionFixture? = null
            var failure: Throwable? = null
            try {
                val owner = TerminalWorkspace()
                workspace = owner
                val setup =
                    if (profile.kind == TerminalProfileKind.POWERSHELL) {
                        "Set-PSReadLineOption -HistorySaveStyle SaveNothing; [Microsoft.PowerShell.PSConsoleReadLine]::ClearHistory()"
                    } else {
                        null
                    }
                check(profile.startupCommand == null) { "Suggestion fixture owns shell setup" }
                val launch = if (setup != null) profile.copy(startupCommand = TerminalStartupCommand(setup)) else profile
                val tab =
                    owner.openTab(
                        launch,
                        TerminalWorkspaceOpenOptions.create {
                            it.columns = COLUMNS
                            it.rows = ROWS
                        },
                    )
                val provider =
                    registry.createProvider(
                        profileId = profile.id,
                        shellCapabilities = profile.kind.completionShellCapabilities(),
                        session = tab.session,
                        workingDirectoryUriProvider = { tab.currentWorkingDirectoryUri },
                    )
                val settings = KetraTermSettings(KetraTermConfigManager(directory.resolve("config.toml")))
                val created =
                    withContext(Dispatchers.Swing) {
                        StandaloneSuggestionFixture(tab, provider, settings).also { fixture = it }
                    }
                created.start(decorateProvider)
                if (setup != null) {
                    created.awaitExecution(setup)
                    created.awaitLine("")
                    if (profile.kind == TerminalProfileKind.POWERSHELL) {
                        withTimeout(15.seconds) {
                            tab.session.renderGeneration.first {
                                if (!tab.session.shellCompletionAvailable) return@first false
                                val edit = tab.session.captureCommandEdit() ?: return@first false
                                val ready = edit.prefixResetKeys.isNotEmpty()
                                edit.cancel()
                                ready
                            }
                        }
                    }
                }
                created.scenario()
                // Await the shell's exit before JUnit deletes its working directory.
                // Closing the workspace requests termination but does not await the OS process.
                withContext(Dispatchers.Swing) { created.terminal.setShellSuggestionProvider(null) }
                withTimeout(15.seconds) {
                    tab.session.renderGeneration.first {
                        // A shell redraw can temporarily invalidate the context after the scenario.
                        val edit = tab.session.captureCommandEdit() ?: return@first false
                        val line = edit.commandLine
                        tab.session.submitInput(
                            edit,
                            listOf(
                                TerminalTextReplacementEvent(line.commandText.length - line.cursorOffset, line.cursorOffset, "exit"),
                                TerminalKeyEvent.key(TerminalKey.ENTER),
                            ),
                        ) == TerminalInputAdmission.ACCEPTED
                    }
                }
                withTimeout(15.seconds) { tab.session.state.first { it is TerminalSessionState.Closed } }
            } catch (caught: Throwable) {
                failure = caught
            } finally {
                withContext(NonCancellable) {
                    withContext(Dispatchers.Swing) {
                        failure = captureCleanupFailure(failure) { fixture?.terminal?.dispose() }
                        failure = captureCleanupFailure(failure) { fixture?.window?.dispose() }
                    }
                    failure = captureCleanupFailure(failure) { workspace?.close() }
                    failure = captureCleanupFailure(failure) { registry.closeWithoutFlush() }
                    fixture?.publications?.close()
                }
            }
            failure?.let { throw it }
        }
    }
}

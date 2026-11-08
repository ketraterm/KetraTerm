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

import io.github.ketraterm.app.completion.StandaloneSuggestionFixture.Companion.withSession
import io.github.ketraterm.input.event.TerminalKey
import io.github.ketraterm.input.event.TerminalKeyEvent
import io.github.ketraterm.input.event.TerminalPasteEvent
import io.github.ketraterm.session.TerminalInputAdmission
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestion
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionProvider
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource
import java.awt.event.KeyEvent
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Native PTY scenarios using standalone sources and the default Swing popup. Run with desktopTest. */
@Tag("desktop")
class StandaloneSuggestionDesktopTest {
    @ParameterizedTest(name = "automatic inline prediction [wrapped={0}, alreadyVisible={1}]")
    @CsvSource("false, false", "false, true", "true, false", "true, true")
    internal fun `PowerShell automatically confirms inline predictions before showing and accepting suggestions`(
        wrappedPrediction: Boolean,
        predictionAlreadyVisible: Boolean,
        @TempDir directory: Path,
    ): Unit =
        runBlocking {
            withSession(StandaloneSuggestionShell.POWERSHELL.profile(directory), directory) {
                if (predictionAlreadyVisible) {
                    // Establish the rendered prediction before enabling the automatic completion interaction.
                    setAutomaticSuggestionsEnabled(false)
                }
                val location =
                    if (wrappedPrediction) {
                        var nameLength = 0
                        session.readRenderFrame { frame ->
                            // Leave ten columns for the editable prefix, forcing prediction text onto the next row.
                            nameLength =
                                (frame.columns - 11 - frame.cursor.column).mod(frame.columns).takeUnless { it == 0 } ?: frame.columns
                        }
                        val nested = withContext(Dispatchers.IO) { Files.createDirectory(directory.resolve("p".repeat(nameLength))) }
                        "Set-Location -LiteralPath '${nested.toString().replace("'", "''")}'; "
                    } else {
                        ""
                    }
                val setup =
                    location + "Set-PSReadLineOption -PredictionSource History -PredictionViewStyle InlineView; " +
                        "[Microsoft.PowerShell.PSConsoleReadLine]::ClearHistory(); " +
                        "[Microsoft.PowerShell.PSConsoleReadLine]::AddToHistory('Get-ChildItem -Name ketra-popup-prediction')"
                assertEquals(
                    TerminalInputAdmission.ACCEPTED,
                    session.submitInput(listOf(TerminalPasteEvent(setup), TerminalKeyEvent.key(TerminalKey.ENTER))),
                )
                awaitExecution(setup)
                awaitLine("", requireShellCompletion = true)

                type("Get-Chi")
                if (predictionAlreadyVisible) {
                    withTimeout(15.seconds) {
                        session.renderGeneration.first {
                            val snapshot = session.activeShellCommandLine()
                            var predictionWrapped = !wrappedPrediction
                            if (snapshot != null && wrappedPrediction) {
                                session.readRenderFrame { frame -> predictionWrapped = frame.lineWrapped(snapshot.cursorRow) }
                            }
                            snapshot?.commandText == "Get-Chi" &&
                                snapshot.cursorOffset == 7 &&
                                snapshot.requiresShellConfirmation &&
                                predictionWrapped
                        }
                    }
                    setAutomaticSuggestionsEnabled(true)
                }
                awaitPopup("Get-Chi", "Get-ChildItem")
                selectAndAccept("Get-ChildItem")
                withTimeout(15.seconds) {
                    session.renderGeneration.first {
                        val snapshot = session.activeShellCommandLine()
                        snapshot?.commandText == "Get-ChildItem" && snapshot.cursorOffset == 13
                    }
                }
                val buffer = assertNotNull(session.requestShellCompletions("Get-ChildItem", 13))
                assertEquals("Get-ChildItem", buffer.commandLine)
                assertEquals(13, buffer.cursorOffset)
            }
        }

    @ParameterizedTest(name = "Unicode acceptance [{0}]")
    @EnumSource(StandaloneSuggestionShell::class, names = ["WINDOWS_POWERSHELL", "POWERSHELL"])
    internal fun `PowerShell accepts a supplementary character replacement through its prefix bridge`(
        shell: StandaloneSuggestionShell,
        @TempDir directory: Path,
    ): Unit =
        runBlocking {
            val line = "echo \uD83D\uDE80"
            withSession(shell.profile(directory), directory, decorateProvider = {
                SwingShellSuggestionProvider { request ->
                    flowOf(
                        if (request.commandText == line && request.cursorOffset == line.length) {
                            listOf(SwingShellSuggestion("accepted", 5, line.length, "test", "ARGUMENT"))
                        } else {
                            emptyList()
                        },
                    )
                }
            }) {
                type(line)
                awaitLine(line)
                requestSuggestions()
                awaitPopup(line, "accepted")
                selectAndAccept("accepted")
                awaitLine("echo accepted")
            }
        }

    @ParameterizedTest(name = "editing and acceptance [{0}]")
    @EnumSource(StandaloneSuggestionShell::class)
    internal fun `deletion refreshes suggestions and accepts the shell edit`(
        shell: StandaloneSuggestionShell,
        @TempDir directory: Path,
    ): Unit =
        runBlocking {
            withSession(shell.profile(directory), directory) {
                type("git sta")
                awaitLine("git sta")
                awaitPopup("git sta", "status")
                key(KeyEvent.VK_BACK_SPACE)
                awaitLine("git st")
                awaitPopup("git st", "status")
                selectAndAccept("status")
                awaitLine("git status")
            }
        }

    @ParameterizedTest(name = "live alias [{0}]")
    @EnumSource(StandaloneSuggestionShell::class)
    internal fun `a session-defined alias is discovered accepted and executed`(
        shell: StandaloneSuggestionShell,
        @TempDir directory: Path,
    ): Unit =
        runBlocking {
            val profile = shell.profile(directory)
            assumeTrue(shell.supportsLiveCommands(profile), "Live command discovery is unsupported or its shell prerequisites are missing")
            withSession(profile, directory) {
                val definition =
                    if (shell.isPowerShell) {
                        "Set-Alias ketra_smoke_echo Write-Output"
                    } else {
                        "alias ketra_smoke_echo=echo"
                    }
                assertEquals(
                    TerminalInputAdmission.ACCEPTED,
                    session.submitInput(listOf(TerminalPasteEvent(definition), TerminalKeyEvent.key(TerminalKey.ENTER))),
                )
                awaitExecution(definition)
                awaitLine("", requireShellCompletion = shell.isPowerShell)
                type("ketra_smoke_e")
                awaitLine("ketra_smoke_e")
                awaitPopup("ketra_smoke_e", "ketra_smoke_echo")
                selectAndAccept("ketra_smoke_echo")
                awaitLine("ketra_smoke_echo")
                type(" 'SMOKE_OK'")
                key(KeyEvent.VK_ENTER)
                awaitExecution("ketra_smoke_echo 'SMOKE_OK'")
                awaitLine("", requireShellCompletion = shell.isPowerShell)
            }
        }

    @ParameterizedTest(name = "directory context [{0}]")
    @EnumSource(StandaloneSuggestionShell::class)
    internal fun `directory suggestions respect the filesystem boundary and accepted cwd changes`(
        shell: StandaloneSuggestionShell,
        @TempDir directory: Path,
    ): Unit =
        runBlocking {
            val profile = shell.profile(directory)
            withContext(Dispatchers.IO) {
                Files.createDirectories(directory.resolve("alpha").resolve("inside"))
                Files.createDirectories(directory.resolve("alpine"))
                Files.writeString(directory.resolve("also.txt"), "not a directory")
            }
            withSession(profile, directory) {
                type("cd al")
                awaitLine("cd al")
                if (shell.isGuest) {
                    // Guest metadata must not accidentally select these Windows host directories.
                    val source = withContext(Dispatchers.Swing) { provider.open(SwingShellSuggestionRequest("cd al", 5)) }
                    assertTrue(withTimeout(15.seconds) { source.suggestions.last() }.isEmpty())
                } else {
                    val paths = awaitPopup("cd al", "alpha/")
                    assertTrue(paths.visibleSuggestions.none { it.displayText == "also.txt" })
                    val artifacts = Path.of(System.getProperty("terminal.test.artifactsDirectory", "build/test-artifacts/desktopTest"))
                    capturePopup(artifacts.resolve("suggestions").resolve("$shell.png"))
                    selectAndAccept("alpha/", execute = true)
                    awaitExecution("cd alpha/")
                    awaitLine("", requireShellCompletion = shell.isPowerShell)
                    assertTrue(
                        tab.currentWorkingDirectoryUri
                            .orEmpty()
                            .trimEnd('/')
                            .endsWith("/alpha"),
                        tab.currentWorkingDirectoryUri,
                    )
                    type("cd in")
                    awaitLine("cd in")
                    awaitPopup("cd in", "inside/")
                }
            }
        }
}

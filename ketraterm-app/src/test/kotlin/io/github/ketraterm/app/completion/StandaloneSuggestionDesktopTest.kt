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
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionProvider
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionRequest
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionSource
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.swing.Swing
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.awt.event.KeyEvent
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Native PTY scenarios using standalone sources and the default Swing popup. Run with desktopTest. */
@Tag("desktop")
class StandaloneSuggestionDesktopTest {
    @ParameterizedTest(name = "editing and cancellation [{0}]")
    @EnumSource(StandaloneSuggestionShell::class)
    internal fun `deletion cancels a slow request and accepts the refreshed edit`(
        shell: StandaloneSuggestionShell,
        @TempDir directory: Path,
    ): Unit =
        runBlocking {
            val profile = shell.profile(directory)
            val slowRequestEntered = CompletableDeferred<Unit>()
            val slowRequestCancelled = CompletableDeferred<Unit>()
            withSession(profile, directory, decorateProvider = { provider ->
                object : SwingShellSuggestionProvider by provider {
                    override fun open(request: SwingShellSuggestionRequest): SwingShellSuggestionSource {
                        if (request.commandText != "git sta") return provider.open(request)
                        return SwingShellSuggestionSource(
                            flow {
                                try {
                                    slowRequestEntered.complete(Unit)
                                    awaitCancellation()
                                } finally {
                                    slowRequestCancelled.complete(Unit)
                                }
                            },
                        )
                    }
                }
            }) {
                type("git st")
                awaitLine("git st")
                awaitPopup("git st", "status")
                type("a")
                awaitLine("git sta")
                withTimeout(15.seconds) { slowRequestEntered.await() }
                discardPopupUpdates()
                key(KeyEvent.VK_BACK_SPACE)
                awaitLine("git st")
                withTimeout(15.seconds) { slowRequestCancelled.await() }
                awaitPopup("git st", "status")
                key(KeyEvent.VK_BACK_SPACE)
                awaitLine("git s")
                awaitPopup("git s", "status")
                selectAndAccept("status")
                awaitLine("git status")
                repeat("git status".length) { key(KeyEvent.VK_BACK_SPACE) }
                awaitLine("")
                type("echo 'SMOKE_OK'")
                key(KeyEvent.VK_ENTER)
                awaitExecution("echo 'SMOKE_OK'")
                awaitLine("")
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
                awaitLine("")
                type("ketra_smoke_e")
                awaitLine("ketra_smoke_e")
                awaitPopup("ketra_smoke_e", "ketra_smoke_echo")
                selectAndAccept("ketra_smoke_echo")
                awaitLine("ketra_smoke_echo")
                type(" 'SMOKE_OK'")
                key(KeyEvent.VK_ENTER)
                awaitExecution("ketra_smoke_echo 'SMOKE_OK'")
                awaitLine("")
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
            Files.createDirectories(directory.resolve("alpha").resolve("inside"))
            Files.createDirectories(directory.resolve("alpine"))
            Files.writeString(directory.resolve("also.txt"), "not a directory")
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
                    awaitLine("")
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

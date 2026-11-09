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

import io.github.ketraterm.input.event.TerminalKey
import io.github.ketraterm.render.api.TerminalRenderBufferKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Real PTY programs in real Swing windows; no OS keyboard injection or packaged installation. */
@Tag("desktop")
class TerminalTuiDesktopTest {
    @ParameterizedTest(name = "Neovim [{0}]")
    @EnumSource(TuiTool::class, names = ["NEOVIM", "WSL_NEOVIM"])
    internal fun `Neovim renders Unicode selects text resizes edits and restores the primary screen`(
        tool: TuiTool,
        @TempDir directory: Path,
    ): Unit =
        runBlocking {
            val host = TuiHost.discover(tool)
            val init =
                withContext(Dispatchers.IO) {
                    Files.writeString(
                        directory.resolve("init.vim"),
                        """
                            set noswapfile
                            set shada=
                            set shortmess+=I
                        call setline(1, ['KETRA_NVIM_READY', 'CJK: 中文 日本語 界', 'Combining: é ä', 'Emoji: 😀 👩‍💻 👍🏽', 'RESIZE_PENDING'])
                        autocmd VimEnter * redraw! | call chansend(v:stderr, "\x07")
                        autocmd VimResized * call setline(5, printf('KETRA_NVIM_SIZE %d %d', &columns, &lines)) | redraw! | call chansend(v:stderr, "\x07")
                        """.trimIndent() + "\n",
                    )
                }
            TerminalTuiFixture.withWindow(host.command("-u", host.path(init), "--noplugin", "-n", "-i", "NONE"), directory) {
                awaitText("KETRA_NVIM_READY")
                session.readRenderFrame { frame -> assertEquals(TerminalRenderBufferKind.ALTERNATE, frame.activeBuffer) }
                withContext(Dispatchers.Swing) {
                    assertTrue(terminal.selectAll())
                    val copied = assertNotNull(terminal.selectedText())
                    for (expected in listOf("中文 日本語 界", "é ä", "😀", "👩‍💻", "👍🏽")) assertContains(copied, expected)
                    terminal.clearSelection()
                }
                for (scale in listOf(1.0, 1.25, 1.5, 1.75, 2.0)) capture("${tool.name}-${(scale * 100).toInt()}", scale)
                resize(132, 43)
                awaitText("KETRA_NVIM_SIZE 132 43")
                type("ggIedited ")
                key(TerminalKey.ESCAPE)
                awaitText("edited KETRA_NVIM_READY")
                capture("${tool.name}-resized")
                exit {
                    key(TerminalKey.ESCAPE)
                    type(":qa!")
                    key(TerminalKey.ENTER)
                }
            }
        }

    @ParameterizedTest(name = "less [{0}]")
    @EnumSource(TuiTool::class, names = ["LESS", "WSL_LESS"])
    internal fun `less navigates large Unicode output and restores the primary screen`(
        tool: TuiTool,
        @TempDir directory: Path,
    ): Unit =
        runBlocking {
            val host = TuiHost.discover(tool)
            val file =
                withContext(Dispatchers.IO) {
                    Files.writeString(
                        directory.resolve("output.txt"),
                        buildString {
                            appendLine("KETRA_LESS_READY")
                            repeat(400) { appendLine("LINE $it 中文 é 👩‍💻") }
                            appendLine("KETRA_LESS_END")
                        },
                    )
                }
            TerminalTuiFixture.withWindow(host.command("-R", host.path(file)), directory) {
                awaitText("KETRA_LESS_READY")
                session.readRenderFrame { frame -> assertEquals(TerminalRenderBufferKind.ALTERNATE, frame.activeBuffer) }
                type("G")
                awaitText("KETRA_LESS_END")
                type("g")
                awaitText("KETRA_LESS_READY")
                resize(132, 43)
                capture("${tool.name}-resized")
                exit { type("q") }
            }
        }

    @ParameterizedTest(name = "tmux [{0}]")
    @EnumSource(TuiTool::class, names = ["TMUX", "WSL_TMUX"])
    internal fun `tmux receives input propagates pane resize and restores the primary screen`(
        tool: TuiTool,
        @TempDir directory: Path,
    ): Unit =
        runBlocking {
            val host = TuiHost.discover(tool)
            val script =
                withContext(Dispatchers.IO) {
                    val path = Files.createDirectories(directory.resolve("path with 'quotes'")).resolve("child.sh")
                    Files.writeString(
                        path,
                        """
                        printf 'KETRA_TMUX_READY\n'
                        IFS= read -r reply
                        printf 'KETRA_TMUX_INPUT:%s\n' "${'$'}reply"
                        IFS= read -r release
                        printf 'KETRA_TMUX_SIZE '; stty size
                        IFS= read -r release
                        """.trimIndent() + "\n",
                    )
                }
            val socket = "ketra-test-${UUID.randomUUID()}"
            var failure: Throwable? = null
            try {
                val quotedScript = "'" + host.path(script).replace("'", """'\''""") + "'"
                val child = "/bin/sh $quotedScript"
                TerminalTuiFixture.withWindow(
                    host.command("-u", "-L", socket, "-f", "/dev/null", "new-session", "-s", "test", child),
                    directory,
                ) {
                    awaitText("KETRA_TMUX_READY")
                    session.readRenderFrame { frame -> assertEquals(TerminalRenderBufferKind.ALTERNATE, frame.activeBuffer) }
                    type("hello")
                    key(TerminalKey.ENTER)
                    awaitText("KETRA_TMUX_INPUT:hello")
                    resize(132, 43)
                    key(TerminalKey.ENTER)
                    awaitText("KETRA_TMUX_SIZE 42 132")
                    capture("${tool.name}-resized")
                    exit { key(TerminalKey.ENTER) }
                }
            } catch (caught: Throwable) {
                failure = caught
            } finally {
                withContext(NonCancellable) {
                    try {
                        host.cleanTmux(socket)
                    } catch (cleanup: Throwable) {
                        val first = failure
                        if (first == null) failure = cleanup else first.addSuppressed(cleanup)
                    }
                }
            }
            failure?.let { throw it }
        }
}

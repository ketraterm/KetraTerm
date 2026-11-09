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
import io.github.ketraterm.input.event.TerminalKeyEvent
import io.github.ketraterm.pty.PtyOptions
import io.github.ketraterm.pty.TerminalSessions
import io.github.ketraterm.render.api.TerminalRenderBufferKind
import io.github.ketraterm.render.api.TerminalRenderCellFlags
import io.github.ketraterm.render.cache.TerminalRenderCache
import io.github.ketraterm.session.TerminalInputAdmission
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.session.TerminalSessionState
import io.github.ketraterm.ui.swing.api.SwingTerminal
import io.github.ketraterm.ui.swing.settings.SwingSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.awt.Point
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import javax.swing.JFrame
import kotlin.math.ceil
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

internal enum class TuiTool(
    val executable: String,
    val wsl: Boolean = false,
) {
    NEOVIM("nvim"),
    LESS("less"),
    TMUX("tmux"),
    WSL_NEOVIM("nvim", true),
    WSL_LESS("less", true),
    WSL_TMUX("tmux", true),
    ;

    fun requireAvailable(
        available: Boolean,
        reason: String,
    ) {
        val required =
            System
                .getProperty("terminal.test.requiredTuis", "")
                .split(',')
                .filter(String::isNotBlank)
                .map { valueOf(it.trim()) }
                .toSet()
        if (this in required) assertTrue(available, "$name: $reason") else assumeTrue(available, "$name: $reason")
    }
}

/** Tool discovery and guest path conversion; no sessions or windows are created during discovery. */
internal class TuiHost private constructor(
    val tool: TuiTool,
    private val prefix: List<String>,
    private val executable: String,
) {
    fun command(vararg arguments: String): List<String> =
        prefix + (if (tool.wsl) listOf("env", "LC_ALL=C.UTF-8") else emptyList()) + executable + arguments

    suspend fun path(file: Path): String =
        withContext(Dispatchers.IO) {
            if (tool.wsl) output(prefix + listOf("wslpath", "-a", "-u", file.toAbsolutePath().toString())).trim() else file.toString()
        }

    suspend fun cleanTmux(socket: String) =
        withContext(Dispatchers.IO) {
            // Only this test's private server is addressed. An already-exited server is success.
            output(
                prefix +
                    listOf(
                        "/bin/sh",
                        "-c",
                        "\"\$2\" -L \"\$1\" kill-server 2>/dev/null || ! \"\$2\" -L \"\$1\" has-session 2>/dev/null",
                        "ketra-tui-cleanup",
                        socket,
                        executable,
                    ),
            )
        }

    companion object {
        suspend fun discover(tool: TuiTool): TuiHost =
            withContext(Dispatchers.IO) {
                val windows = System.getProperty("os.name").startsWith("Windows", true)
                val prefix =
                    if (tool.wsl) {
                        tool.requireAvailable(windows, "WSL requires Windows")
                        val distribution = System.getProperty("terminal.pty.wsl.distribution", "")
                        tool.requireAvailable(distribution.isNotBlank(), "set terminal.pty.wsl.distribution")
                        val wsl = findExecutable("wsl", windows)
                        tool.requireAvailable(wsl != null, "wsl.exe is not on PATH")
                        listOf(requireNotNull(wsl), "-d", distribution, "--exec")
                    } else {
                        tool.requireAvailable(!windows || tool == TuiTool.NEOVIM, "use WSL for less and tmux on Windows")
                        emptyList()
                    }
                val executable =
                    if (tool.wsl) {
                        output(prefix + listOf("/bin/sh", "-c", "command -v ${tool.executable} || true")).trim().ifEmpty { null }
                    } else {
                        findExecutable(tool.executable, windows)
                    }
                tool.requireAvailable(executable != null, "${tool.executable} is not installed or discoverable")
                TuiHost(tool, prefix, requireNotNull(executable))
            }

        private fun findExecutable(
            name: String,
            windows: Boolean,
        ): String? {
            for (entry in System.getenv("PATH").orEmpty().split(File.pathSeparator)) {
                if (entry.isBlank()) continue
                val candidate =
                    try {
                        Path.of(entry.trim('"'), if (windows) "$name.exe" else name)
                    } catch (_: InvalidPathException) {
                        continue
                    }
                if (Files.isRegularFile(candidate) && (windows || Files.isExecutable(candidate))) return candidate.toString()
            }
            return null
        }

        private fun output(command: List<String>): String {
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            var failure: Throwable? = null
            try {
                assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Command did not finish: $command")
                val result = process.inputStream.bufferedReader().use { it.readText() }
                assertEquals(0, process.exitValue(), "Command failed: $command\n$result")
                return result
            } catch (caught: Throwable) {
                failure = caught
                throw caught
            } finally {
                var cleanup =
                    captureCleanupFailure(failure) {
                        if (process.isAlive) {
                            process.destroyForcibly()
                            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Command did not terminate: $command")
                        }
                    }
                cleanup = captureCleanupFailure(cleanup) { process.inputStream.close() }
                cleanup = captureCleanupFailure(cleanup) { process.outputStream.close() }
                cleanup = captureCleanupFailure(cleanup) { process.errorStream.close() }
                if (failure == null) cleanup?.let { throw it }
            }
        }
    }
}

/** Real Swing window bound to a real local PTY. Every wait observes a named state or callback. */
internal class TerminalTuiFixture private constructor(
    val session: TerminalSession,
    val terminal: SwingTerminal,
    private val window: JFrame,
) {
    suspend fun awaitText(expected: String) {
        try {
            withTimeout(30.seconds) {
                session.renderGeneration.first { screenText().contains(expected) || session.isClosed }
            }
        } catch (failure: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("Missing TUI output '$expected'\n${screenText()}", failure)
        }
        assertTrue(screenText().contains(expected), "TUI ended before '$expected': ${session.failure}\n${screenText()}")
    }

    fun screenText(): String {
        val cache = TerminalRenderCache(1, 1).apply { updateFrom(session) }
        return (0 until cache.rows).joinToString("\n") { row ->
            buildString {
                for (column in 0 until cache.columns) {
                    val index = cache.rowOffset(row) + column
                    when {
                        cache.flags[index] and TerminalRenderCellFlags.WIDE_TRAILING != 0 -> Unit
                        cache.flags[index] and TerminalRenderCellFlags.CLUSTER != 0 -> append(checkNotNull(cache.clusterText(row, column)))
                        cache.flags[index] and TerminalRenderCellFlags.CODEPOINT != 0 -> appendCodePoint(cache.codeWords[index])
                        else -> append(' ')
                    }
                }
            }
        }
    }

    fun type(text: String) {
        text.codePoints().forEach {
            assertEquals(TerminalInputAdmission.ACCEPTED, session.submitInput(TerminalKeyEvent.codepoint(it)))
        }
    }

    fun key(key: TerminalKey) {
        assertEquals(TerminalInputAdmission.ACCEPTED, session.submitInput(TerminalKeyEvent.key(key)))
    }

    suspend fun resize(
        columns: Int,
        rows: Int,
    ) {
        withContext(Dispatchers.Swing) {
            val desired = terminal.preferredGridSize(columns, rows, TerminalRenderBufferKind.ALTERNATE)
            window.setSize(window.width + desired.width - terminal.width, window.height + desired.height - terminal.height)
            window.validate()
        }
        val completed = CompletableDeferred<Unit>()
        // The next EDT dispatch follows the window's component-resized event.
        withContext(Dispatchers.Swing) {
            terminal.resizeSessionToVisibleGrid { failure ->
                if (failure == null) completed.complete(Unit) else completed.completeExceptionally(failure)
            }
        }
        withTimeout(20.seconds) { completed.await() }
        session.readRenderFrame { frame -> assertEquals(columns to rows, frame.columns to frame.rows) }
    }

    suspend fun exit(request: () -> Unit) {
        request()
        withTimeout(30.seconds) { session.state.first { it is TerminalSessionState.Closed } }
        assertEquals(0, session.exitCode)
        assertNull(session.failure)
        session.readRenderFrame { frame -> assertEquals(TerminalRenderBufferKind.PRIMARY, frame.activeBuffer) }
    }

    suspend fun capture(
        name: String,
        scale: Double = 1.0,
    ) {
        val image =
            withContext(Dispatchers.Swing) {
                val result =
                    BufferedImage(ceil(terminal.width * scale).toInt(), ceil(terminal.height * scale).toInt(), BufferedImage.TYPE_INT_ARGB)
                result.createGraphics().let { graphics ->
                    try {
                        graphics.scale(scale, scale)
                        terminal.paint(graphics)
                    } finally {
                        graphics.dispose()
                    }
                }
                val bounds = Rectangle()
                val position = Point()
                for (column in 0 until terminal.visibleGridSize().width) {
                    assertTrue(terminal.copyCellBounds(column, 0, bounds))
                    assertTrue(terminal.copyCellPositionAt(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2, position))
                    assertEquals(Point(column, 0), position)
                }
                result
            }
        withContext(Dispatchers.IO) {
            val root = System.getProperty("terminal.test.artifactsDirectory", "build/test-artifacts/desktopTest")
            val directory = Path.of(root).resolve("tui")
            Files.createDirectories(directory)
            assertTrue(ImageIO.write(image, "png", directory.resolve("$name.png").toFile()))
        }
    }

    companion object {
        suspend fun withWindow(
            command: List<String>,
            directory: Path,
            scenario: suspend TerminalTuiFixture.() -> Unit,
        ) {
            val session =
                withContext(Dispatchers.IO) {
                    TerminalSessions.createLocalPty(
                        PtyOptions.create {
                            it.command = command
                            it.workingDirectory = directory
                            it.columns = 100
                            it.rows = 28
                            it.maxHistory = 10000
                            it.environment =
                                PtyOptions.defaultEnvironment().toMutableMap().apply {
                                    remove("TMUX")
                                    remove("TMUX_PANE")
                                    remove("NVIM")
                                    remove("LESS")
                                    remove("LESSOPEN")
                                    remove("LESSCLOSE")
                                    put("LESSHISTFILE", "-")
                                    put("NVIM_LOG_FILE", directory.resolve("nvim.log").toString())
                                    if (!System.getProperty("os.name").startsWith("Windows", true)) {
                                        val locale = if (System.getProperty("os.name").startsWith("Mac", true)) "en_US.UTF-8" else "C.UTF-8"
                                        put("LC_ALL", locale)
                                    }
                                }
                        },
                    )
                }
            var terminal: SwingTerminal? = null
            var window: JFrame? = null
            var failure: Throwable? = null
            try {
                val bound = CompletableDeferred<Unit>()
                val fixture =
                    withContext(Dispatchers.Swing) {
                        val settings =
                            SwingSettings.create {
                                it.columns = 100
                                it.rows = 28
                                it.cursorBlinkMillis = 0
                            }
                        val view = SwingTerminal(settingsProvider = { settings })
                        terminal = view
                        val frame = JFrame("KetraTerm real TUI: ${command.first()}")
                        window = frame
                        // Exercise the terminal grid without native title-bar sizing constraints.
                        frame.isUndecorated = true
                        frame.defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
                        frame.add(view)
                        view.bind(session) { error ->
                            if (error == null) bound.complete(Unit) else bound.completeExceptionally(error)
                        }
                        frame.pack()
                        frame.isVisible = true
                        TerminalTuiFixture(session, view, frame)
                    }
                withTimeout(20.seconds) { bound.await() }
                fixture.resize(100, 28)
                withContext(Dispatchers.IO) { session.start(100, 28) }
                fixture.scenario()
            } catch (caught: Throwable) {
                failure = caught
            } finally {
                withContext(NonCancellable) {
                    withContext(Dispatchers.Swing) {
                        failure = captureCleanupFailure(failure) { terminal?.dispose() }
                        failure = captureCleanupFailure(failure) { window?.dispose() }
                    }
                    withContext(Dispatchers.IO) { failure = captureCleanupFailure(failure, session::close) }
                }
            }
            failure?.let { throw it }
        }
    }
}

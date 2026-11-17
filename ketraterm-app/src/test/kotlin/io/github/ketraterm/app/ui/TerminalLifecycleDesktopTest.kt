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
import io.github.ketraterm.render.api.TerminalRenderCellFlags
import io.github.ketraterm.render.api.TerminalRenderCursorShape
import io.github.ketraterm.render.cache.TerminalRenderCache
import io.github.ketraterm.session.TerminalInputAdmission
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.session.TerminalSessionState
import io.github.ketraterm.ui.swing.api.SwingTerminal
import io.github.ketraterm.ui.swing.settings.SwingSettings
import io.github.ketraterm.ui.swing.settings.TerminalTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.swing.Swing
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.GraphicsEnvironment
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@Tag("desktop")
class TerminalLifecycleDesktopTest {
    @Test
    fun `real PTY windows reload resize and release processes across repeated lifecycles`(
        @TempDir directory: Path,
    ): Unit =
        runBlocking {
            assertFalse(GraphicsEnvironment.isHeadless(), "Run desktopTest with a desktop or virtual display")
            val child =
                withContext(Dispatchers.IO) {
                    Files.writeString(
                        directory.resolve("LifecycleChild.java"),
                        """
                        import java.io.BufferedReader;
                        import java.io.InputStreamReader;
                        import java.nio.file.Files;
                        import java.nio.file.Path;

                        class LifecycleChild {
                            public static void main(String[] args) throws Exception {
                                Files.writeString(Path.of(args[0]), Long.toString(ProcessHandle.current().pid()));
                                System.out.println("KETRA_LIFECYCLE_READY");
                                var input = new BufferedReader(new InputStreamReader(System.in));
                                for (String line; (line = input.readLine()) != null;) {
                                    if (line.equals("stream")) {
                                        while (true) System.out.println("KETRA_LIVE_OUTPUT");
                                    }
                                    System.out.println("KETRA_REPLY:" + line);
                                }
                            }
                        }
                        """.trimIndent(),
                    )
                }
            repeat(3) { cycle -> runLifecycle(child, directory, cycle) }
        }

    private suspend fun runLifecycle(
        child: Path,
        directory: Path,
        cycle: Int,
    ) {
        val javaName = if (System.getProperty("os.name").startsWith("Windows", true)) "java.exe" else "java"
        val java = Path.of(System.getProperty("java.home"), "bin", javaName)
        val pidFile = directory.resolve("child-$cycle.pid")
        val threadPrefix = "ketra-lifecycle-${UUID.randomUUID()}"
        val readerName = "$threadPrefix-reader"
        val watcherName = "$threadPrefix-watcher"
        val session =
            withContext(Dispatchers.IO) {
                TerminalSessions.createLocalPty(
                    PtyOptions.create {
                        it.command = listOf(java.toString(), child.toString(), pidFile.toString())
                        it.workingDirectory = directory
                        it.columns = 80
                        it.rows = 20
                        it.maxHistory = 128
                        it.readerThreadName = readerName
                        it.watcherThreadName = watcherName
                    },
                )
            }
        var view: SwingTerminal? = null
        var window: JFrame? = null
        var process: ProcessHandle? = null
        var ownedThreads = emptyList<Thread>()
        val completions = mutableListOf<AtomicInteger>()
        var failure: Throwable? = null

        suspend fun cleanup(action: suspend () -> Unit) {
            try {
                action()
            } catch (caught: Throwable) {
                val first = failure
                if (first == null) {
                    failure = caught
                } else if (first !== caught) {
                    first.addSuppressed(caught)
                }
            }
        }

        try {
            var settings =
                SwingSettings.create {
                    it.columns = 80
                    it.rows = 20
                    it.cursorBlinkMillis = 0
                    it.useSystemFallbackFonts = false
                }
            val terminal =
                withContext(Dispatchers.Swing) {
                    SwingTerminal(settingsProvider = { settings }).also { component ->
                        view = component
                        window =
                            JFrame("KetraTerm PTY lifecycle $cycle").apply {
                                isUndecorated = true
                                defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
                                add(component)
                                pack()
                                isVisible = true
                            }
                    }
                }
            completions += awaitMutation { terminal.bind(session, it) }
            completions += awaitMutation { terminal.resizeSessionToVisibleGrid(it) }
            withContext(Dispatchers.IO) { session.start() }
            val cache = TerminalRenderCache(1, 1)
            awaitOutput(session, cache, "KETRA_LIFECYCLE_READY")
            process =
                withContext(Dispatchers.IO) {
                    ProcessHandle.of(Files.readString(pidFile).trim().toLong()).orElseThrow()
                }
            assertTrue(process.isAlive)
            ownedThreads = Thread.getAllStackTraces().keys.filter { it.name == readerName || it.name == watcherName }
            assertEquals(setOf(readerName, watcherName), ownedThreads.map { it.name }.toSet())

            withContext(Dispatchers.Swing) {
                settings =
                    settings.copy {
                        it.font = it.font.deriveFont(it.font.size2D + 1f)
                        it.palette = if (cycle % 2 == 0) TerminalTheme.NORD.createPalette() else TerminalTheme.ONE_DARK.createPalette()
                        it.cursorShape = if (cycle % 2 == 0) TerminalRenderCursorShape.BAR else TerminalRenderCursorShape.UNDERLINE
                    }
            }
            completions += awaitMutation { terminal.reloadSettings(it) }
            session.readRenderFrame {
                assertEquals(settings.palette, it.palette)
                assertEquals(settings.cursorShape, it.cursor.shape)
            }

            val columns = 90 + cycle * 6
            val rows = 24 + cycle
            withContext(Dispatchers.Swing) {
                val frame = checkNotNull(window)
                val desired = terminal.preferredGridSize(columns, rows)
                frame.setSize(frame.width + desired.width - terminal.width, frame.height + desired.height - terminal.height)
                frame.validate()
            }
            completions += awaitMutation { terminal.resizeSessionToVisibleGrid(it) }
            session.readRenderFrame { assertEquals(columns to rows, it.columns to it.rows) }
            sendLine(session, "cycle-$cycle")
            awaitOutput(session, cache, "KETRA_REPLY:cycle-$cycle")
            sendLine(session, "stream")
            awaitOutput(session, cache, "KETRA_LIVE_OUTPUT")

            withContext(Dispatchers.Swing) {
                terminal.dispose()
                assertFalse(session.isClosed, "The view must leave its host-owned session open")
                checkNotNull(window).dispose()
                session.requestClose()
            }
            val closed =
                withTimeout(
                    20.seconds,
                ) { session.state.first { it is TerminalSessionState.Closed } } as TerminalSessionState.Closed
            assertTrue(closed.event.locallyRequested)
            assertNull(closed.event.failure)
        } catch (caught: Throwable) {
            failure = caught
        } finally {
            withContext(NonCancellable) {
                cleanup { withContext(Dispatchers.Swing) { view?.dispose() } }
                cleanup { withContext(Dispatchers.Swing) { window?.dispose() } }
                cleanup {
                    session.requestClose()
                    withTimeout(20.seconds) { session.state.first { it is TerminalSessionState.Closed } }
                }
                cleanup {
                    withContext(Dispatchers.IO) {
                        // On Linux, ProcessHandle.onExit can complete while the child is still
                        // a zombie. The PTY watcher waits for PTY4J to reap it before returning.
                        ownedThreads.firstOrNull { it.name == watcherName }?.let { watcher ->
                            watcher.join(10_000)
                            assertFalse(watcher.isAlive, "The owned PTY watcher must terminate")
                        }
                        process?.let {
                            assertFalse(it.isAlive, "The owned PTY process must terminate")
                        }
                    }
                }
                cleanup {
                    withContext(Dispatchers.IO) {
                        process?.takeIf { it.isAlive }?.let {
                            it.destroyForcibly()
                            it.onExit().get(15, TimeUnit.SECONDS)
                            assertFalse(it.isAlive, "Failed cleanup must still release the owned child")
                        }
                    }
                }
                for (thread in ownedThreads) {
                    cleanup {
                        withContext(Dispatchers.IO) {
                            thread.join(10_000)
                            assertFalse(thread.isAlive, "The owned PTY thread must terminate: ${thread.name}")
                        }
                    }
                }
                cleanup {
                    withContext(Dispatchers.Swing) {
                        assertFalse(window?.isDisplayable ?: false)
                        assertTrue(completions.all { it.get() == 1 }, "Each mutation must complete once")
                    }
                }
            }
        }
        failure?.let { throw it }
    }

    private suspend fun awaitMutation(action: ((Throwable?) -> Unit) -> Unit): AtomicInteger {
        val completed = CompletableDeferred<Unit>()
        val count = AtomicInteger()
        withContext(Dispatchers.Swing) {
            action { failure ->
                count.incrementAndGet()
                assertTrue(SwingUtilities.isEventDispatchThread())
                if (failure == null) completed.complete(Unit) else completed.completeExceptionally(failure)
            }
        }
        withTimeout(20.seconds) { completed.await() }
        assertEquals(1, count.get())
        return count
    }

    private fun sendLine(
        session: TerminalSession,
        text: String,
    ) {
        for (character in text) {
            assertEquals(TerminalInputAdmission.ACCEPTED, session.submitInput(TerminalKeyEvent.codepoint(character.code)))
        }
        assertEquals(TerminalInputAdmission.ACCEPTED, session.submitInput(TerminalKeyEvent.key(TerminalKey.ENTER)))
    }

    private suspend fun awaitOutput(
        session: TerminalSession,
        cache: TerminalRenderCache,
        expected: String,
    ) {
        fun visibleText(): String {
            cache.updateFrom(session)
            return buildString {
                for (row in 0 until cache.rows) {
                    for (column in 0 until cache.columns) {
                        val index = cache.rowOffset(row) + column
                        if (cache.flags[index] and TerminalRenderCellFlags.CODEPOINT !=
                            0
                        ) {
                            appendCodePoint(cache.codeWords[index])
                        } else {
                            append(' ')
                        }
                    }
                    append('\n')
                }
            }
        }
        withTimeout(30.seconds) { session.renderGeneration.first { visibleText().contains(expected) || session.isClosed } }
        assertTrue(visibleText().contains(expected), "Missing child output '$expected': ${session.failure}")
    }
}

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
package io.github.ketraterm.shell.integration

import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.render.api.TerminalRenderCellFlags
import io.github.ketraterm.render.cache.TerminalRenderCache
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.session.TerminalShellCommandLineSnapshot
import io.github.ketraterm.session.TerminalShellIntegrationCommandLifecycle
import io.github.ketraterm.session.TerminalShellIntegrationCommandRecord
import io.github.ketraterm.testkit.MockConnector
import io.github.ketraterm.transport.TerminalConnector
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.charset.StandardCharsets

@OptIn(ExperimentalCoroutinesApi::class)
class OscShellIntegrationTest {
    @Test
    fun `OSC 133 markers populate shared shell integration state`() {
        val connector = MockConnector()
        val session = createStartedSession(connector, columns = 10, rows = 4)

        connector.feedFromHost("\u001B]133;A\u0007prompt> \u001B]133;B\u0007\u001B]133;C\u0007run\r\nfailed\u001B]133;D;2\u0007".ascii())

        val decorations = session.shellDecorations()
        assertAll(
            { assertTrue(decorations.promptStarts[0]) },
        )
        session.close()
    }

    @Test
    fun `OSC 133 prompt marker skips leading blank layout row in multiline Bash prompt`() {
        val connector = MockConnector()
        val session = createStartedSession(connector, columns = 30, rows = 4)

        connector.feedFromHost(
            "\u001B]133;A\u0007\r\ngagik@host MINGW64 ~\r\n$ \u001B]133;B\u0007".ascii(),
        )

        val decorations = session.shellDecorations()
        assertAll(
            { assertFalse(decorations.promptStarts[0]) },
            { assertTrue(decorations.promptStarts[1]) },
            { assertFalse(decorations.promptStarts[2]) },
        )
        session.close()
    }

    @Test
    fun `OSC 133 prompt marker remains on first row when prompt content begins there`() {
        val connector = MockConnector()
        val session = createStartedSession(connector, columns = 30, rows = 4)

        connector.feedFromHost(
            "\u001B]133;A\u0007gagik@host\r\n$ \u001B]133;B\u0007".ascii(),
        )

        val decorations = session.shellDecorations()
        assertAll(
            { assertTrue(decorations.promptStarts[0]) },
            { assertFalse(decorations.promptStarts[1]) },
        )
        session.close()
    }

    @Test
    fun `OSC 133 empty prompt span preserves original marker anchor`() {
        val connector = MockConnector()
        val session = createStartedSession(connector, columns = 30, rows = 4)

        connector.feedFromHost("\u001B]133;A\u0007\r\n\u001B]133;B\u0007".ascii())

        val decorations = session.shellDecorations()
        assertAll(
            { assertTrue(decorations.promptStarts[0]) },
            { assertFalse(decorations.promptStarts[1]) },
        )
        session.close()
    }

    @Test
    fun `OSC 7 updates session directory and OSC 133 snapshots it onto the command`() {
        val connector = MockConnector()
        val session = createStartedSession(connector, columns = 30, rows = 4)

        connector.feedFromHost(
            "\u001B]7;file:///workspace/My%20Project\u001B\\".ascii(),
        )
        connector.feedFromHost(
            "\u001B]133;A\u0007PS> \u001B]133;B\u0007build\u001B]133;C\u0007".ascii(),
        )

        val recordId = session.shellDecorations().commandRecordIds[0]
        assertAll(
            { assertEquals("file:///workspace/My%20Project", session.currentWorkingDirectoryUri()) },
            {
                assertEquals(
                    "file:///workspace/My%20Project",
                    session.shellIntegrationState.commandWorkingDirectoryUri(recordId),
                )
            },
        )
        session.close()
    }

    @Test
    fun `OSC 133 command start captures same line command text after prompt end`() {
        val connector = MockConnector()
        val session = createStartedSession(connector, columns = 30, rows = 4)

        connector.feedFromHost("\u001B]133;A\u0007PS> \u001B]133;B\u0007git status\u001B]133;C\u0007\r\nok\u001B]133;D;0\u0007".ascii())

        val decorations = session.shellDecorations()
        val recordId = decorations.commandRecordIds[0]
        assertAll(
            { assertTrue(recordId != TerminalShellIntegrationCommandRecord.NONE) },
            { assertEquals("git status", session.shellIntegrationState.commandText(recordId)) },
        )
        session.close()
    }

    @Test
    fun `OSC 133 active command line captures visible text before command start`() {
        val connector = MockConnector()
        val session = createStartedSession(connector, columns = 30, rows = 4)

        connector.feedFromHost("\u001B]133;A\u0007PS> \u001B]133;B\u0007git status".ascii())

        assertEquals(
            TerminalShellCommandLineSnapshot(
                commandText = "git status",
                cursorOffset = "git status".length,
                cursorColumn = "PS> git status".length,
                cursorRow = 0,
            ),
            session.activeShellCommandLine(),
        )
        session.close()
    }

    @Test
    fun `OSC 133 active command line is unavailable after command start`() {
        val connector = MockConnector()
        val session = createStartedSession(connector, columns = 30, rows = 4)

        connector.feedFromHost("\u001B]133;A\u0007PS> \u001B]133;B\u0007git status\u001B]133;C\u0007".ascii())

        assertNull(session.activeShellCommandLine())
        session.close()
    }

    @Test
    fun `OSC 133 active command line is unavailable without prompt end`() {
        val connector = MockConnector()
        val session = createStartedSession(connector, columns = 30, rows = 4)

        connector.feedFromHost("\u001B]133;A\u0007PS> git status".ascii())

        assertNull(session.activeShellCommandLine())
        session.close()
    }

    @Test
    fun `OSC 133 active command line is unavailable when cursor is before visible command end`() {
        val connector = MockConnector()
        val session = createStartedSession(connector, columns = 30, rows = 4)

        connector.feedFromHost("\u001B]133;A\u0007PS> \u001B]133;B\u0007git status\u001B[2D".ascii())

        assertNull(session.activeShellCommandLine())
        session.close()
    }

    @Test
    fun `OSC 133 active command line reads bounded scrollback when prompt moved above live rows`() {
        val connector = MockConnector()
        val session = createStartedSession(connector, columns = 20, rows = 2)

        connector.feedFromHost("\u001B]133;A\u0007P> \u001B]133;B\u0007one\r\ntwo\r\nthree".ascii())

        assertEquals(
            TerminalShellCommandLineSnapshot(
                commandText = "one\ntwo\nthree",
                cursorOffset = "one\ntwo\nthree".length,
                cursorColumn = "three".length,
                cursorRow = 2,
            ),
            session.activeShellCommandLine(),
        )
        session.close()
    }

    @Test
    fun `OSC 133 command start captures previous line command text at column zero`() {
        val connector = MockConnector()
        val session = createStartedSession(connector, columns = 30, rows = 4)

        connector.feedFromHost("\u001B]133;A\u0007PS> \u001B]133;B\u0007git status\r\n\u001B]133;C\u0007output\u001B]133;D;1\u0007".ascii())

        val decorations = session.shellDecorations()
        val recordId = decorations.commandRecordIds[1]
        assertAll(
            { assertTrue(recordId != TerminalShellIntegrationCommandRecord.NONE) },
            { assertEquals("git status", session.shellIntegrationState.commandText(recordId)) },
        )
        session.close()
    }

    @Test
    fun `OSC 133 command start preserves hard line breaks in multiline command text`() {
        val connector = MockConnector()
        val session = createStartedSession(connector, columns = 30, rows = 4)

        connector.feedFromHost(
            "\u001B]133;A\u0007PS> \u001B]133;B\u0007echo first\r\nsecond\u001B]133;C\u0007".ascii(),
        )

        val recordId = session.shellDecorations().commandRecordIds[1]
        assertEquals("echo first\nsecond", session.shellIntegrationState.commandText(recordId))
        session.close()
    }

    @Test
    fun `OSC 133 command start joins soft wrapped command rows without a newline`() {
        val connector = MockConnector()
        val session = createStartedSession(connector, columns = 10, rows = 4)

        connector.feedFromHost(
            "\u001B]133;A\u0007P> \u001B]133;B\u0007abcdefghijk\u001B]133;C\u0007".ascii(),
        )

        val recordId = session.shellDecorations().commandRecordIds[0]
        assertEquals("abcdefghijk", session.shellIntegrationState.commandText(recordId))
        session.close()
    }

    @Test
    fun `OSC 133 command start preserves argument separator at soft wrap`() {
        val connector = MockConnector()
        createStartedSession(connector, columns = 8, rows = 4).use { session ->
            connector.feedFromHost(
                "\u001B]133;A\u0007P> \u001B]133;B\u0007echo hello\u001B]133;C\u0007".ascii(),
            )

            val recordId = session.shellDecorations().commandRecordIds[0]
            assertEquals("echo hello", session.shellIntegrationState.commandText(recordId))
        }
    }

    @Test
    fun `OSC 133 command start preserves a soft wrapped row containing only spaces`() {
        val connector = MockConnector()
        createStartedSession(connector, columns = 8, rows = 4).use { session ->
            val command = "echo" + " ".repeat(9) + "hello"
            connector.feedFromHost(
                ("\u001B]133;A\u0007P> \u001B]133;B\u0007" + command + "\u001B]133;C\u0007").ascii(),
            )

            val recordId = session.shellDecorations().commandRecordIds[0]
            assertEquals("echo" + " ".repeat(9) + "hello", session.shellIntegrationState.commandText(recordId))
        }
    }

    @Test
    fun `OSC 133 command start preserves wrapped argument separators after prompt enters scrollback`() {
        val connector = MockConnector()
        createStartedSession(connector, columns = 8, rows = 2).use { session ->
            connector.feedFromHost(
                "\u001B]133;A\u0007P> \u001B]133;B\u0007echo first second third\u001B]133;C\u0007".ascii(),
            )

            val recordId = session.shellDecorations().commandRecordIds[1]
            assertEquals("echo first second third", session.shellIntegrationState.commandText(recordId))
        }
    }

    @Test
    fun `OSC 133 command start preserves spaces before wide glyph wrap padding`() {
        val connector = MockConnector()
        createStartedSession(connector, columns = 10, rows = 4).use { session ->
            connector.feedFromHost(
                "\u001B]133;A\u0007P> \u001B]133;B\u0007echo  \u754C\u001B]133;C\u0007"
                    .toByteArray(StandardCharsets.UTF_8),
            )

            val recordId = session.shellDecorations().commandRecordIds[0]
            assertEquals("echo  \u754C", session.shellIntegrationState.commandText(recordId))
        }
    }

    @Test
    fun `OSC 133 command start preserves spaces before grapheme cluster wrap padding`() {
        val connector = MockConnector()
        createStartedSession(connector, columns = 10, rows = 4).use { session ->
            connector.feedFromHost(
                "\u001B]133;A\u0007P> \u001B]133;B\u0007echo  \uD83D\uDC69\u200D\uD83D\uDCBB\u001B]133;C\u0007"
                    .toByteArray(StandardCharsets.UTF_8),
            )

            val recordId = session.shellDecorations().commandRecordIds[0]
            assertEquals("echo  \uD83D\uDC69\u200D\uD83D\uDCBB", session.shellIntegrationState.commandText(recordId))
        }
    }

    @Test
    fun `OSC 133 command start preserves erased cells at a soft wrap boundary`() {
        val connector = MockConnector()
        createStartedSession(connector, columns = 8, rows = 4).use { session ->
            connector.feedFromHost(
                "\u001B]133;A\u0007P> \u001B]133;B\u0007echoXhello\u001B[1;8H\u001B[X\u001B[2;6H\u001B]133;C\u0007".ascii(),
            )

            val recordId = session.shellDecorations().commandRecordIds[0]
            assertEquals("echo hello", session.shellIntegrationState.commandText(recordId))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["echo hello", "echo         hello", "echo  \u754C", "echo  \uD83D\uDC69\u200D\uD83D\uDCBB"])
    fun `command text and frame and cache fingerprints agree across soft wrap widths`(command: String) {
        val extractor = ShellIntegrationCommandTextExtractor()
        val boundedExtractor = ShellIntegrationCommandTextExtractor(maxTextLength = command.length - 1)
        var unwrappedFingerprint: LongArray? = null
        for (columns in listOf(80, 8, 10)) {
            val connector = MockConnector()
            createStartedSession(connector, columns = columns, rows = 4).use { session ->
                connector.feedFromHost(
                    ("\u001B]133;A\u0007P> \u001B]133;B\u0007" + command + "\u001B[0m")
                        .toByteArray(StandardCharsets.UTF_8),
                )
                val frameFingerprint = LongArray(TERMINAL_SHELL_COMMAND_FINGERPRINT_LONGS)
                session.readRenderFrame { frame ->
                    val lineId = frame.lineId(0)
                    val cursor = frame.cursor
                    assertEquals(command, extractor.extract(frame, lineId, 3, cursor.row, cursor.column))
                    assertEquals(
                        TerminalShellCommandFingerprintStatus.COMPLETE,
                        extractor.fingerprint(frame, lineId, 3, cursor.row, cursor.column, frameFingerprint),
                    )
                    assertNull(boundedExtractor.extract(frame, lineId, 3, cursor.row, cursor.column))
                    assertEquals(
                        TerminalShellCommandFingerprintStatus.INVALID,
                        boundedExtractor.fingerprint(
                            frame,
                            lineId,
                            3,
                            cursor.row,
                            cursor.column,
                            LongArray(TERMINAL_SHELL_COMMAND_FINGERPRINT_LONGS),
                        ),
                    )
                }
                val cache = TerminalRenderCache(columns, 4).also { it.updateFrom(session) }
                val cacheFingerprint = LongArray(TERMINAL_SHELL_COMMAND_FINGERPRINT_LONGS)
                assertEquals(
                    TerminalShellCommandFingerprintStatus.COMPLETE,
                    extractor.fingerprint(cache, cache.lineIds[0], 3, cache.cursorRow, cache.cursorColumn, cacheFingerprint),
                )
                assertArrayEquals(frameFingerprint, cacheFingerprint)
                assertEquals(command.length.toLong(), frameFingerprint[TERMINAL_SHELL_COMMAND_FINGERPRINT_UTF16_LENGTH_INDEX])
                assertEquals(
                    TerminalShellCommandFingerprintStatus.INVALID,
                    boundedExtractor.fingerprint(
                        cache,
                        cache.lineIds[0],
                        3,
                        cache.cursorRow,
                        cache.cursorColumn,
                        LongArray(TERMINAL_SHELL_COMMAND_FINGERPRINT_LONGS),
                    ),
                )
                if (columns == 80) {
                    unwrappedFingerprint = frameFingerprint
                } else {
                    assertArrayEquals(unwrappedFingerprint, frameFingerprint, "fingerprint at width $columns")
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [0, TerminalRenderCellFlags.CLUSTER])
    fun `wrapped command fingerprint rejects invalid cell flags or missing cluster data`(invalidFlags: Int) {
        val connector = MockConnector()
        createStartedSession(connector, columns = 8, rows = 4).use { session ->
            connector.feedFromHost("\u001B]133;A\u0007P> \u001B]133;B\u0007echo hello\u001B[0m".ascii())
            val cache = TerminalRenderCache(8, 4).also { it.updateFrom(session) }
            cache.flags[4] = invalidFlags

            assertEquals(
                TerminalShellCommandFingerprintStatus.INVALID,
                ShellIntegrationCommandTextExtractor().fingerprint(
                    cache,
                    cache.lineIds[0],
                    3,
                    cache.cursorRow,
                    cache.cursorColumn,
                    LongArray(TERMINAL_SHELL_COMMAND_FINGERPRINT_LONGS),
                ),
            )
        }
    }

    @Test
    fun `OSC 133 command start extracts multiline command whose prompt moved into scrollback`() {
        val connector = MockConnector()
        val session = createStartedSession(connector, columns = 10, rows = 2)

        connector.feedFromHost(
            "\u001B]133;A\u0007P> \u001B]133;B\u0007one\r\ntwo\r\nthree\u001B]133;C\u0007".ascii(),
        )

        val recordId = session.shellDecorations().commandRecordIds[1]
        assertEquals("one\ntwo\nthree", session.shellIntegrationState.commandText(recordId))
        session.close()
    }

    @Test
    fun `OSC 133 command start preserves grapheme cluster command text`() {
        val connector = MockConnector()
        val session = createStartedSession(connector, columns = 30, rows = 4)

        connector.feedFromHost(
            "\u001B]133;A\u0007P> \u001B]133;B\u0007e\u0301\u001B]133;C\u0007"
                .toByteArray(StandardCharsets.UTF_8),
        )

        val recordId = session.shellDecorations().commandRecordIds[0]
        assertEquals("e\u0301", session.shellIntegrationState.commandText(recordId))
        session.close()
    }

    @Test
    fun `OSC 133 command start rejects command text above the retention bound`() {
        val connector = MockConnector()
        val session = createStartedSession(connector, columns = 5000, rows = 2)
        val oversizedCommand = "a".repeat(DEFAULT_SHELL_INTEGRATION_COMMAND_TEXT_LENGTH + 1)

        connector.feedFromHost(
            ("\u001B]133;A\u0007P> \u001B]133;B\u0007" + oversizedCommand + "\u001B]133;C\u0007").ascii(),
        )

        val recordId = session.shellDecorations().commandRecordIds[0]
        assertNull(session.shellIntegrationState.commandText(recordId))
        session.close()
    }

    @Test
    fun `OSC 133 command start stores unknown command text without prompt end`() {
        val connector = MockConnector()
        val session = createStartedSession(connector, columns = 30, rows = 4)

        connector.feedFromHost("PS> git status\u001B]133;C\u0007".ascii())

        val decorations = session.shellDecorations()
        val recordId = decorations.commandRecordIds[0]
        assertAll(
            { assertTrue(recordId != TerminalShellIntegrationCommandRecord.NONE) },
            { assertNull(session.shellIntegrationState.commandText(recordId)) },
        )
        session.close()
    }

    @Test
    fun `OSC 133 command start stores unknown command text after orphan prompt end`() {
        val connector = MockConnector()
        val session = createStartedSession(connector, columns = 30, rows = 4)

        connector.feedFromHost("PS> \u001B]133;B\u0007git status\u001B]133;C\u0007".ascii())

        val decorations = session.shellDecorations()
        val recordId = decorations.commandRecordIds[0]
        assertAll(
            { assertTrue(recordId != TerminalShellIntegrationCommandRecord.NONE) },
            { assertNull(session.shellIntegrationState.commandText(recordId)) },
        )
        session.close()
    }

    @Test
    fun `OSC 133 decorations re-anchor after clear screen and history`() {
        val connector = MockConnector()
        val session = createStartedSession(connector, columns = 30, rows = 4)

        connector.feedFromHost(
            (
                "\u001B]133;A\u0007PS> \u001B]133;B\u0007bad\u001B]133;C\u0007\r\n" +
                    "failed\u001B]133;D;1\u0007" +
                    "\u001B[H\u001B[2J\u001B[3J" +
                    "\u001B]133;A\u0007PS> \u001B]133;B\u0007"
            ).ascii(),
        )

        val decorations = session.shellDecorations()
        assertAll(
            { assertTrue(decorations.lineIds.all { it > 0L }, "clear-history render frame exposed a zero line id") },
            { assertTrue(decorations.promptStarts.any { it }, "new prompt marker did not re-anchor after clear") },
        )
        session.close()
    }

    @Test
    fun `OSC 133 command finish followed by prompt preserves next prompt marker`() {
        val connector = MockConnector()
        val session = createStartedSession(connector, columns = 20, rows = 4)

        connector.feedFromHost("\u001B]133;C\u0007failed\r\n\u001B]133;D;2\u0007\u001B]133;A\u0007PS> ".ascii())

        val decorations = session.shellDecorations()
        assertAll(
            { assertTrue(decorations.promptStarts[1]) },
        )
        session.close()
    }

    @Test
    fun `OSC 133 prompt start abandons unfinished command before stale finish marker`() {
        val connector = MockConnector()
        val session = createStartedSession(connector, columns = 20, rows = 4)

        connector.feedFromHost("\u001B]133;C\u0007partial\r\n\u001B]133;A\u0007PS> \u001B]133;D;2\u0007".ascii())

        val decorations = session.shellDecorations()
        assertAll(
            { assertTrue(decorations.promptStarts[1]) },
        )
        session.close()
    }

    @Test
    fun `OSC 133 zero exit code records succeeded lifecycle`() {
        val connector = MockConnector()
        val session = createStartedSession(connector, columns = 20, rows = 4)

        connector.feedFromHost("\u001B]133;C\u0007ok\u001B]133;D;0\u0007".ascii())

        val decorations = session.shellDecorations()
        assertAll(
            { assertTrue(decorations.commandRecordIds[0] != TerminalShellIntegrationCommandRecord.NONE) },
            { assertEquals(TerminalShellIntegrationCommandLifecycle.SUCCEEDED, decorations.commandLifecycleStates[0]) },
        )
        session.close()
    }

    @Test
    fun `OSC 133 duplicate command start abandons first command and finishes newest command`() {
        val connector = MockConnector()
        val session = createStartedSession(connector, columns = 20, rows = 4)

        connector.feedFromHost("\u001B]133;C\u0007partial\r\n\u001B]133;C\u0007new\r\n\u001B]133;D;1\u0007".ascii())

        val decorations = session.shellDecorations()
        assertAll(
            { assertTrue(decorations.commandRecordIds[0] != TerminalShellIntegrationCommandRecord.NONE) },
            { assertTrue(decorations.commandRecordIds[1] != TerminalShellIntegrationCommandRecord.NONE) },
            { assertNotEquals(decorations.commandRecordIds[0], decorations.commandRecordIds[1]) },
            { assertEquals(TerminalShellIntegrationCommandLifecycle.ABANDONED, decorations.commandLifecycleStates[0]) },
            { assertEquals(TerminalShellIntegrationCommandLifecycle.FAILED, decorations.commandLifecycleStates[1]) },
        )
        session.close()
    }

    @Test
    fun `OSC 133 finish without command start is ignored by command timeline`() {
        val connector = MockConnector()
        val session = createStartedSession(connector, columns = 20, rows = 4)

        connector.feedFromHost("orphan\u001B]133;D;1\u0007".ascii())

        val decorations = session.shellDecorations()
        assertAll(
            { assertEquals(TerminalShellIntegrationCommandRecord.NONE, decorations.commandRecordIds[0]) },
            { assertEquals(TerminalShellIntegrationCommandLifecycle.NONE, decorations.commandLifecycleStates[0]) },
        )
        session.close()
    }

    @Test
    fun `resize preserves shared shell integration timeline`() {
        val connector = MockConnector()
        val session = createStartedSession(connector, columns = 10, rows = 4)

        connector.feedFromHost("\u001B]133;A\u0007prompt> ".ascii())
        assertTrue(session.shellDecorations().promptStarts[0])

        session.resize(columns = 4, rows = 4)

        val decorations = session.shellDecorations()
        assertAll(
            { assertTrue(decorations.promptStarts[0]) },
            { assertFalse(decorations.promptStarts[1]) },
        )
        session.close()
    }

    private fun createStartedSession(
        connector: TerminalConnector,
        columns: Int = 10,
        rows: Int = 3,
    ): TerminalSession {
        val terminal = TerminalBuffers.create(width = columns, height = rows)
        val session =
            TerminalSession.create(
                terminal,
                connector,
                shellIntegration = OscShellIntegration,
                workerDispatcher = StandardTestDispatcher(),
                ioDispatcher = UnconfinedTestDispatcher(),
            )
        session.start(columns, rows)
        return session
    }

    private fun TerminalSession.shellDecorations(): ShellDecorationSnapshot {
        var lineIds = LongArray(0)
        var promptStarts = BooleanArray(0)
        var commandStarts = BooleanArray(0)
        var commandEnds = BooleanArray(0)
        var commandRecordIds = IntArray(0)
        var commandLifecycleStates = IntArray(0)
        readRenderFrame { frame ->
            lineIds = LongArray(frame.rows)
            var row = 0
            while (row < frame.rows) {
                lineIds[row] = frame.lineId(row)
                row++
            }
            promptStarts = BooleanArray(frame.rows)
            commandStarts = BooleanArray(frame.rows)
            commandEnds = BooleanArray(frame.rows)
            commandRecordIds = IntArray(frame.rows)
            commandLifecycleStates = IntArray(frame.rows)
            shellIntegrationState.copyViewport(
                lineIds = lineIds,
                rowCount = frame.rows,
                promptStarts = promptStarts,
                commandStarts = commandStarts,
                commandEnds = commandEnds,
                commandRecordIds = commandRecordIds,
                commandLifecycleStates = commandLifecycleStates,
            )
        }
        return ShellDecorationSnapshot(
            lineIds = lineIds,
            promptStarts = promptStarts,
            commandStarts = commandStarts,
            commandEnds = commandEnds,
            commandRecordIds = commandRecordIds,
            commandLifecycleStates = commandLifecycleStates,
        )
    }

    private data class ShellDecorationSnapshot(
        val lineIds: LongArray,
        val promptStarts: BooleanArray,
        val commandStarts: BooleanArray,
        val commandEnds: BooleanArray,
        val commandRecordIds: IntArray,
        val commandLifecycleStates: IntArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as ShellDecorationSnapshot

            if (!promptStarts.contentEquals(other.promptStarts)) return false
            if (!commandStarts.contentEquals(other.commandStarts)) return false
            if (!commandEnds.contentEquals(other.commandEnds)) return false
            if (!commandRecordIds.contentEquals(other.commandRecordIds)) return false
            if (!commandLifecycleStates.contentEquals(other.commandLifecycleStates)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = promptStarts.contentHashCode()
            result = 31 * result + commandStarts.contentHashCode()
            result = 31 * result + commandEnds.contentHashCode()
            result = 31 * result + commandRecordIds.contentHashCode()
            result = 31 * result + commandLifecycleStates.contentHashCode()
            return result
        }
    }

    private fun String.ascii(): ByteArray = toByteArray(StandardCharsets.US_ASCII)
}

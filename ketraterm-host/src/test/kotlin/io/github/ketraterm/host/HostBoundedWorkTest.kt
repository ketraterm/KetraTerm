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
package io.github.ketraterm.host

import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.core.api.TerminalBuffer
import io.github.ketraterm.parser.api.TerminalParsers
import io.github.ketraterm.protocol.AnsiMode
import io.github.ketraterm.render.api.TerminalRenderFrame
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class HostBoundedWorkTest {
    @ParameterizedTest
    @CsvSource("S,0", "S,2", "S,8", "T,0", "T,2", "T,8")
    fun `scroll counts stop at the region height without adding surplus blank history`(
        command: String,
        historyCapacity: Int,
    ) {
        val bytes = "\u001B[1;3r\u001B[2;2H\u001B[6$command".encodeToByteArray()
        assertAllByteSplits(bytes) { split ->
            val terminal = TerminalBuffers.create(6, 5, maxHistory = historyCapacity)
            for (row in 0 until terminal.height) {
                terminal.positionCursor(0, row)
                terminal.writeText("row$row")
            }
            val parser = TerminalParsers.create(HostCommandAdapter(terminal))
            parser.accept(bytes, 0, split)
            parser.accept(bytes, split, bytes.size - split)

            val retained = if (command == "S") minOf(3, historyCapacity) else 0
            assertEquals(retained, terminal.historySize, "split=$split")
            assertEquals((3 - retained until 3).map { "row$it" }, terminal.getAllAsString().lines().take(retained))
            assertEquals(listOf("", "", "", "row3", "row4"), (0 until 5).map(terminal::getLineAsString))
            assertEquals(1, terminal.cursorRow)
            assertEquals(1, terminal.cursorCol)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["S", "T"])
    fun `saturated scrolling finishes within retained grid work and preserves guard rows`(command: String) {
        val historyCapacity = 4
        val terminal = TerminalBuffers.create(6, 5, maxHistory = historyCapacity)
        for (row in 0 until terminal.height) {
            terminal.positionCursor(0, row)
            terminal.writeText("row$row")
        }
        var scalarScrolls = 0
        // A scalar pass over every retained row already bounds all observable scroll changes.
        // Guard the existing public scalar API so a broken huge loop fails without hanging.
        val retainedRows = terminal.height + historyCapacity
        val bounded =
            object : TerminalBuffer by terminal {
                override fun scrollUp() {
                    scalarScrolls++
                    assertTrue(scalarScrolls <= retainedRows, "SU exceeded work proportional to the retained grid")
                    terminal.scrollUp()
                }

                override fun scrollDown() {
                    scalarScrolls++
                    assertTrue(scalarScrolls <= retainedRows, "SD exceeded work proportional to the retained grid")
                    terminal.scrollDown()
                }
            }
        val parser = TerminalParsers.create(HostCommandAdapter(bounded))
        parser.accept("\u001B[2;4r\u001B[3;3H".encodeToByteArray())

        parser.accept("\u001B[999999999999999999999999999999$command".encodeToByteArray())

        assertEquals("row0", terminal.getLineAsString(0))
        assertEquals("", terminal.getLineAsString(1))
        assertEquals("", terminal.getLineAsString(2))
        assertEquals("", terminal.getLineAsString(3))
        assertEquals("row4", terminal.getLineAsString(4))
        assertEquals(2, terminal.cursorRow)
        assertEquals(2, terminal.cursorCol)
        assertEquals(0, terminal.historySize)
        parser.accept("X".encodeToByteArray())
        assertEquals('X'.code, terminal.getCodepointAt(2, 2))
    }

    @ParameterizedTest
    @ValueSource(strings = ["I", "Z"])
    fun `saturated tab counts reach the boundary without an unbounded loop`(
        command: String,
        @TempDir directory: Path,
    ) {
        val executable = if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
        val java = Path.of(System.getProperty("java.home"), "bin", executable)
        val classpath =
            listOf(
                HostTabWorkProcess::class.java,
                HostCommandAdapter::class.java,
                TerminalBuffers::class.java,
                TerminalParsers::class.java,
                TerminalRenderFrame::class.java,
                AnsiMode::class.java,
                Unit::class.java,
            ).map {
                Path
                    .of(
                        it.protectionDomain.codeSource.location
                            .toURI(),
                    ).toString()
            }.distinct()
                .joinToString(File.pathSeparator)
        val output = directory.resolve("tab-$command.log")
        val process =
            ProcessBuilder(
                java.toString(),
                "-Xint",
                "-cp",
                classpath,
                HostTabWorkProcess::class.java.name,
                command,
            ).redirectErrorStream(true)
                .redirectOutput(output.toFile())
                .start()
        try {
            // This is a child-process hang guard, not a paint/latency performance assertion.
            // Interpreted execution prevents JIT loop elimination from hiding unbounded work.
            assertTrue(process.waitFor(30, TimeUnit.SECONDS)) {
                "Saturated $command did not finish; child output:\n${Files.readString(output)}"
            }
            val text = Files.readString(output)
            assertEquals(0, process.exitValue(), text)
            assertTrue(text.contains("completed $command"), text)
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
                assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Failed to stop isolated tab-count process")
            }
        }
    }
}

internal object HostTabWorkProcess {
    @JvmStatic
    fun main(args: Array<String>) {
        val command = args.single()
        require(command == "I" || command == "Z")
        val terminal = TerminalBuffers.create(10, 5, maxHistory = 0)
        val parser = TerminalParsers.create(HostCommandAdapter(terminal))
        parser.accept("\u001B[2;5H".encodeToByteArray())
        println("entered $command")
        parser.accept("\u001B[999999999999999999999999999999${command}X".encodeToByteArray())
        parser.endOfInput()

        val column = if (command == "I") 9 else 0
        check(terminal.getCodepointAt(column, 1) == 'X'.code) { "Marker did not land at the tab boundary" }
        check(terminal.getLineAsString(1) == if (command == "I") "         X" else "X")
        check(terminal.cursorRow == 1)
        check(terminal.cursorCol == if (command == "I") 9 else 1)
        check(terminal.historySize == 0)
        for (margins in listOf(false, true)) {
            for (clearStops in listOf(false, true)) {
                val setup = if (margins) "\u001B[?69h\u001B[3;8s" else ""
                val tabs = if (clearStops) "\u001B[3g" else "\u001B[1;6H\u001BH"
                val bytes = "$setup$tabs\u001B[2;5H\u001B[2147483647${command}X".encodeToByteArray()
                for (split in 0..bytes.size) {
                    val bounded = TerminalBuffers.create(10, 5, maxHistory = 0)
                    val chunked = TerminalParsers.create(HostCommandAdapter(bounded))
                    chunked.accept(bytes, 0, split)
                    chunked.accept(bytes, split, bytes.size - split)
                    val edge =
                        if (command == "I") {
                            if (margins) 7 else 9
                        } else {
                            if (margins) 2 else 0
                        }
                    check(bounded.getCodepointAt(edge, 1) == 'X'.code) { "margins=$margins clear=$clearStops split=$split" }
                    check(bounded.cursorRow == 1)
                    check(bounded.cursorCol == if (command == "I") edge else edge + 1)
                    check(bounded.historySize == 0)
                }
            }
        }
        println("completed $command")
    }
}

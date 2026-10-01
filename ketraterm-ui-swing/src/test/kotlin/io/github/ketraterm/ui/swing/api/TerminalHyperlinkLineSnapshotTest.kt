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
package io.github.ketraterm.ui.swing.api

import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.render.api.TerminalRenderCellFlags
import io.github.ketraterm.render.api.TerminalRenderFrameReader
import io.github.ketraterm.render.cache.TerminalRenderCache
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.function.Executable
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class TerminalHyperlinkLineSnapshotTest {
    @ParameterizedTest
    @ValueSource(longs = [0L, 1L])
    fun `changing padding into a blank invalidates logical line identity`(lineId: Long) {
        val cache = TerminalRenderCache(3, 2)
        cache.flags.fill(TerminalRenderCellFlags.EMPTY)
        for ((index, codepoint) in intArrayOf(0, 1, 3).zip("abc".map(Char::code))) {
            cache.flags[index] = TerminalRenderCellFlags.CODEPOINT
            cache.codeWords[index] = codepoint
        }
        cache.flags[2] = TerminalRenderCellFlags.EMPTY or TerminalRenderCellFlags.WRAP_PADDING
        cache.lineWrapped[0] = true
        cache.lineIds[0] = lineId
        val builder = TerminalHyperlinkLineSnapshotBuilder()
        val padded = builder.snapshot(cache, 0, 2)
        assertEquals("abc\n", padded.text)
        assertTrue(padded.matchesRow(cache, 0, 0))
        assertTrue(padded.matchesRow(cache, 1, 1))
        assertTrue(padded.matchesTextRow(cache, 0, 0))

        cache.flags[2] = TerminalRenderCellFlags.EMPTY
        cache.lineGenerations[0]++
        val blank = builder.snapshot(cache, 0, 2)
        assertEquals("ab c\n", blank.text)
        assertFalse(padded.matchesRow(cache, 0, 0))
        assertFalse(padded.matchesTextRow(cache, 0, 0))
        assertTrue(padded.matchesRow(cache, 1, 1))
        assertEquals(2, blank.cellStarts[2])
        assertEquals(3, blank.cellEnds[2])
        assertEquals(3, blank.cellStarts[3])
    }

    @ParameterizedTest
    @CsvSource("false,abc", "false,'ab '", "true,abc", "true,'ab '")
    fun `logical detector text omits wrap padding while retaining real spaces and cell ownership`(
        cluster: Boolean,
        prefix: String,
    ) {
        val terminal = TerminalBuffers.create(width = 4, height = 3, maxHistory = 8)
        terminal.writeText(prefix)
        val wideText = if (cluster) "界\u0301" else "界"
        if (cluster) terminal.writeCluster(intArrayOf(0x754C, 0x0301)) else terminal.writeCodepoint(0x754C)
        terminal.writeText("x")
        val cache = TerminalRenderCache(4, 3)
        val builder = TerminalHyperlinkLineSnapshotBuilder()

        assertAll(
            intArrayOf(4, 8, 4).map { width ->
                Executable {
                    terminal.resize(newWidth = width, newHeight = 3)
                    cache.updateFromAbsoluteRange(terminal as TerminalRenderFrameReader, 0L, Long.MAX_VALUE)
                    var endRow = 1
                    while (endRow < cache.rows && cache.lineWrapped[endRow - 1]) endRow++
                    val snapshot = builder.snapshot(cache, 0, endRow)

                    assertEquals("$prefix${wideText}x\n", snapshot.text, "width=$width prefix='$prefix'")
                    val expectedStart = if (width == 4) 4 else 3
                    for (offset in prefix.length until prefix.length + wideText.length) {
                        assertEquals(expectedStart, snapshot.cellStarts[offset], "All cluster code units own the wide leading cell")
                        assertEquals(expectedStart + 2, snapshot.cellEnds[offset], "The mapping must cover the entire wide cell")
                    }
                    val viewport = TerminalHyperlinkIndex()
                    viewport.update(cache)
                    val pending = viewport.pendingLines()
                    viewport.accept(
                        pending,
                        pending.mapIndexed { index, line ->
                            listOf(
                                TerminalDetectedHyperlink(
                                    0,
                                    line.text.length - 1,
                                    detectionRequest(pending).hyperlink(
                                        index,
                                        0,
                                        line.text.length - 1,
                                        SwingHyperlinkAction.NONE,
                                    ),
                                    0,
                                    line.text.length,
                                ),
                            )
                        },
                    )
                    viewport.writeOverlay(cache) { _, _, _, _ -> }
                    val ids = viewport.idsFor(cache)
                    for (cell in 0 until 3) assertTrue(ids[cell] < 0)
                    if (width == 4) assertEquals(0, ids[3], "Wrap padding must not acquire a detector action")
                    for (cell in expectedStart until expectedStart + 3) {
                        assertEquals(ids[0], ids[cell], "The wide cell and following text must share the detected span")
                    }
                }
            },
        )
    }
}

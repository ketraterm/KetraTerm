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
package io.github.jvterm.render.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class TerminalRenderFrameReaderTest {
    @Test
    fun `reader passes short lived frame to callback`() {
        val frame = RecordingFrame(columns = 80, rows = 24)
        val reader =
            object : TerminalRenderFrameReader {
                override fun readRenderFrame(consumer: TerminalRenderFrameConsumer) {
                    consumer.accept(frame)
                }
            }

        var observed: TerminalRenderFrame? = null
        reader.readRenderFrame { callbackFrame ->
            observed = callbackFrame
            assertEquals(80, callbackFrame.columns)
            assertEquals(24, callbackFrame.rows)
        }

        assertSame(frame, observed)
    }

    @Test
    fun `default overscan read delegates to scrollback read`() {
        val frame = RecordingFrame(columns = 80, rows = 24)
        var requestedOffset = -1
        val reader =
            object : TerminalRenderFrameReader {
                override fun readRenderFrame(consumer: TerminalRenderFrameConsumer) {
                    consumer.accept(frame)
                }

                override fun readRenderFrame(
                    scrollbackOffset: Int,
                    consumer: TerminalRenderFrameConsumer,
                ) {
                    requestedOffset = scrollbackOffset
                    consumer.accept(frame)
                }
            }

        reader.readRenderFrame(scrollbackOffset = 7, viewportRows = 25) {
            assertSame(frame, it)
        }

        assertEquals(7, requestedOffset)
    }

    private class RecordingFrame(
        override val columns: Int,
        override val rows: Int,
    ) : TerminalRenderFrame {
        override val frameGeneration: Long = 0L
        override val structureGeneration: Long = 0L
        override val activeBuffer: TerminalRenderBufferKind = TerminalRenderBufferKind.PRIMARY
        override val cursor: TerminalRenderCursor =
            TerminalRenderCursor(
                column = 0,
                row = 0,
                visible = true,
                blinking = false,
                shape = TerminalRenderCursorShape.BLOCK,
                generation = 0L,
            )

        override fun lineGeneration(row: Int): Long = 0L

        override fun lineWrapped(row: Int): Boolean = false

        override fun copyLine(
            row: Int,
            codeWords: IntArray,
            codeOffset: Int,
            attrWords: LongArray,
            attrOffset: Int,
            flags: IntArray,
            flagOffset: Int,
            extraAttrWords: LongArray?,
            extraAttrOffset: Int,
            hyperlinkIds: IntArray?,
            hyperlinkOffset: Int,
            clusterSink: TerminalRenderClusterSink?,
            clusterDataSink: TerminalRenderClusterDataSink?,
        ): Unit = throw UnsupportedOperationException("not needed for callback contract test")
    }
}

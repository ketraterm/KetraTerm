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
package io.github.jvterm.render.cache

import io.github.jvterm.render.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class TerminalRenderPublisherTest {
    @Test
    fun `writer buffers can be reused by different worker threads`() {
        val publisher = TerminalRenderPublisher(3, 1)
        val texts = listOf("abc", "def", "ghi", "jkl", "mno", "pqr")

        texts.forEach { text ->
            var exception: Throwable? = null
            val renderThread =
                thread(start = true) {
                    try {
                        publisher.updateAndPublish(MockFrame(3, 1, text))
                    } catch (error: Throwable) {
                        exception = error
                    }
                }
            renderThread.join()

            assertNull(exception)
        }

        assertEquals("pqr", publisher.current()?.rowText(0))
    }

    @Test
    fun `updateAndPublish rotates buffers`() {
        val publisher = TerminalRenderPublisher(3, 1)
        val frame1 = MockFrame(3, 1, "abc")
        val frame2 = MockFrame(3, 1, "def")

        publisher.updateAndPublish(frame1)
        val firstFront = publisher.current()
        assertEquals("abc", firstFront?.rowText(0))

        publisher.updateAndPublish(frame2)
        val secondFront = publisher.current()
        assertEquals("def", secondFront?.rowText(0))

        // Ensure they are different objects (triple buffering)
        assertNotSame(firstFront, secondFront)
    }

    @Test
    fun `publication after resize uses resolved frame shape`() {
        val publisher = TerminalRenderPublisher(3, 1)

        val frame = MockFrame(5, 2, "12345")
        publisher.updateAndPublish(frame)
        assertEquals("12345", publisher.current()?.rowText(0))
    }

    @Test
    fun `reading a leased front snapshot is stable`() {
        val publisher = TerminalRenderPublisher(3, 1)
        publisher.updateAndPublish(MockFrame(3, 1, "abc"))

        publisher.readCurrent { front ->
            assertAll(
                { assertEquals(3, front.columns) },
                { assertEquals(1, front.rows) },
                { assertEquals("abc", front.rowText(0)) },
            )
        }
    }

    @Test
    fun `publication completes successfully when writer is blocked briefly`() {
        val publisher = TerminalRenderPublisher(3, 1)
        val frame = BlockingMockFrame(3, 1, "abc")
        val writerFinished = AtomicBoolean(false)

        val writer =
            thread(start = true) {
                publisher.updateAndPublish(frame)
                writerFinished.set(true)
            }

        assertTrue(frame.awaitCopy(), "writer did not enter copyLine")
        frame.releaseCopy()
        writer.join(1_000)

        assertTrue(writerFinished.get(), "writer did not finish")
        assertAll(
            { assertEquals(3, publisher.current()?.columns) },
            { assertEquals(1, publisher.current()?.rows) },
            { assertEquals("abc", publisher.current()?.rowText(0)) },
        )
    }

    @Test
    fun `overscan cluster copy works correctly`() {
        val publisher = TerminalRenderPublisher(1, 30)
        val frame = BlockingOverscanClusterFrame(rows = 31)
        var exception: Throwable? = null

        val writer =
            thread(start = true) {
                try {
                    publisher.updateAndPublish(frame)
                } catch (error: Throwable) {
                    exception = error
                }
            }

        assertTrue(frame.awaitOverscanRowCopy(), "writer did not reach overscan row")
        frame.releaseOverscanRowCopy()
        writer.join(1_000)

        val current = publisher.current()
        assertNotNull(current)
        val clusterRef = current!!.clusterRefs[current.rowOffset(30)]
        val clusterOffset = current.clusterOffset(clusterRef)
        val clusterLength = current.clusterLength(clusterRef)
        assertNull(exception)
        assertAll(
            { assertEquals(31, current.rows) },
            { assertNotEquals(0L, clusterRef) },
            { assertEquals(2, clusterLength) },
            {
                assertArrayEquals(
                    intArrayOf('e'.code, 0x0301),
                    current.clusterCodepoints.copyOfRange(clusterOffset, clusterOffset + clusterLength),
                )
            },
            { assertEquals("e\u0301", current.clusterText(30, 0)) },
        )
    }

    @Test
    fun `resize does not require worker thread affinity on later publication`() {
        val publisher = TerminalRenderPublisher(3, 1)
        val firstFrame = MockFrame(3, 1, "abc")
        val secondFrame = MockFrame(5, 2, "12345")

        val firstRenderThread =
            thread(start = true) {
                publisher.updateAndPublish(firstFrame)
            }
        firstRenderThread.join()

        var exception: Throwable? = null
        val secondRenderThread =
            thread(start = true) {
                try {
                    publisher.updateAndPublish(secondFrame)
                } catch (e: Throwable) {
                    exception = e
                }
            }
        secondRenderThread.join()

        assertNull(exception)
        assertEquals("12345", publisher.current()?.rowText(0))
    }

    @Test
    fun `updateAndPublish forwards scrollback offset to cache update`() {
        val publisher = TerminalRenderPublisher(3, 1)
        val frame = OffsetRecordingFrame()

        publisher.updateAndPublish(frame, scrollbackOffset = 2)

        val front = publisher.current()
        assertAll(
            { assertEquals(2, frame.lastRequestedOffset) },
            { assertEquals(2, front?.scrollbackOffset) },
            { assertEquals(4, front?.historySize) },
            { assertEquals("old", front?.rowText(0)) },
            { assertFalse(front?.cursor?.visible == true) },
        )
    }

    @Test
    fun `updateAndPublish forwards viewport row overscan to reader`() {
        val publisher = TerminalRenderPublisher(3, 1)
        val frame = OffsetRecordingFrame()

        publisher.updateAndPublish(frame, scrollbackOffset = 1, viewportRows = 2)

        val front = publisher.current()
        assertAll(
            { assertEquals(1, frame.lastRequestedOffset) },
            { assertEquals(2, frame.lastRequestedRows) },
            { assertEquals(2, front?.rows) },
        )
    }

    @Test
    fun `readCurrent does not block publishing while reader is active`() {
        val publisher = TerminalRenderPublisher(3, 1)
        publisher.updateAndPublish(MockFrame(3, 1, "abc"))

        val readerEntered = CountDownLatch(1)
        val releaseReader = CountDownLatch(1)
        val writerFinished = AtomicBoolean(false)

        val reader =
            thread(start = true) {
                publisher.readCurrent { front ->
                    readerEntered.countDown()
                    assertEquals("abc", front.rowText(0))
                    assertTrue(releaseReader.await(1, TimeUnit.SECONDS))
                    assertEquals("abc", front.rowText(0))
                }
            }

        assertTrue(readerEntered.await(1, TimeUnit.SECONDS))

        val writer =
            thread(start = true) {
                publisher.updateAndPublish(MockFrame(3, 1, "def"))
                writerFinished.set(true)
            }

        writer.join(1_000)
        assertTrue(writerFinished.get(), "writer was blocked by an active readCurrent callback")

        releaseReader.countDown()
        reader.join()

        assertEquals("def", publisher.current()?.rowText(0))
    }

    @Test
    fun `readCurrent keeps pinned snapshot stable across multiple publishes`() {
        val publisher = TerminalRenderPublisher(3, 1)
        publisher.updateAndPublish(MockFrame(3, 1, "abc"))

        val readerEntered = CountDownLatch(1)
        val releaseReader = CountDownLatch(1)

        val reader =
            thread(start = true) {
                publisher.readCurrent { front ->
                    readerEntered.countDown()
                    assertEquals("abc", front.rowText(0))
                    assertTrue(releaseReader.await(1, TimeUnit.SECONDS))
                    assertEquals("abc", front.rowText(0))
                }
            }

        assertTrue(readerEntered.await(1, TimeUnit.SECONDS))

        publisher.updateAndPublish(MockFrame(3, 1, "def"))
        publisher.updateAndPublish(MockFrame(3, 1, "ghi"))

        releaseReader.countDown()
        reader.join()

        assertEquals("ghi", publisher.current()?.rowText(0))
    }

    private fun TerminalRenderCache.rowText(row: Int): String =
        buildString(columns) {
            val start = rowOffset(row)
            var column = 0
            while (column < columns) {
                val code = codeWords[start + column]
                append(if (code == 0) ' ' else code.toChar())
                column++
            }
        }

    private open class MockFrame(
        override val columns: Int,
        override val rows: Int,
        val text: String,
    ) : TerminalRenderFrame,
        TerminalRenderFrameReader {
        override val frameGeneration: Long = text.hashCode().toLong()
        override val structureGeneration: Long = 1L
        override val activeBuffer: TerminalRenderBufferKind = TerminalRenderBufferKind.PRIMARY
        override val cursor: TerminalRenderCursor =
            TerminalRenderCursor(
                0,
                0,
                visible = true,
                blinking = false,
                shape = TerminalRenderCursorShape.BLOCK,
                generation = 1L,
            )

        override fun lineGeneration(row: Int): Long = text.hashCode().toLong()

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
        ) {
            for (i in 0 until columns) {
                codeWords[codeOffset + i] = text.getOrNull(i)?.code ?: 0
                flags[flagOffset + i] = TerminalRenderCellFlags.CODEPOINT
            }
        }

        override fun readRenderFrame(consumer: TerminalRenderFrameConsumer) {
            consumer.accept(this)
        }
    }

    private class BlockingMockFrame(
        columns: Int,
        rows: Int,
        text: String,
    ) : MockFrame(columns, rows, text) {
        private val copyEntered = CountDownLatch(1)
        private val releaseCopy = CountDownLatch(1)

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
        ) {
            copyEntered.countDown()
            assertTrue(releaseCopy.await(1, TimeUnit.SECONDS), "copyLine was not released")
            super.copyLine(
                row,
                codeWords,
                codeOffset,
                attrWords,
                attrOffset,
                flags,
                flagOffset,
                extraAttrWords,
                extraAttrOffset,
                hyperlinkIds,
                hyperlinkOffset,
                clusterSink,
                clusterDataSink,
            )
        }

        fun awaitCopy(): Boolean = copyEntered.await(1, TimeUnit.SECONDS)

        fun releaseCopy() {
            releaseCopy.countDown()
        }
    }

    private class BlockingOverscanClusterFrame(
        override val rows: Int,
    ) : TerminalRenderFrame,
        TerminalRenderFrameReader {
        private val overscanRowCopyEntered = CountDownLatch(1)
        private val releaseOverscanRowCopy = CountDownLatch(1)

        override val columns: Int = 1
        override val frameGeneration: Long = 31
        override val structureGeneration: Long = 31
        override val activeBuffer: TerminalRenderBufferKind = TerminalRenderBufferKind.PRIMARY
        override val cursor: TerminalRenderCursor =
            TerminalRenderCursor(
                column = 0,
                row = 0,
                visible = false,
                blinking = false,
                shape = TerminalRenderCursorShape.BLOCK,
                generation = 1L,
            )

        override fun readRenderFrame(consumer: TerminalRenderFrameConsumer) {
            consumer.accept(this)
        }

        override fun lineGeneration(row: Int): Long = row.toLong()

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
        ) {
            attrWords[attrOffset] = TerminalRenderAttrs.DEFAULT
            extraAttrWords?.set(extraAttrOffset, TerminalRenderExtraAttrs.DEFAULT)
            hyperlinkIds?.set(hyperlinkOffset, 0)

            if (row == rows - 1) {
                overscanRowCopyEntered.countDown()
                assertTrue(releaseOverscanRowCopy.await(1, TimeUnit.SECONDS), "overscan row copy was not released")
                codeWords[codeOffset] = 0
                flags[flagOffset] = TerminalRenderCellFlags.CLUSTER
                clusterSink?.onCluster(0, "e\u0301")
                clusterDataSink?.onCluster(0, intArrayOf('e'.code, 0x0301), 0, 2)
            } else {
                codeWords[codeOffset] = 'x'.code
                flags[flagOffset] = TerminalRenderCellFlags.CODEPOINT
            }
        }

        fun awaitOverscanRowCopy(): Boolean = overscanRowCopyEntered.await(1, TimeUnit.SECONDS)

        fun releaseOverscanRowCopy() {
            releaseOverscanRowCopy.countDown()
        }
    }

    private class OffsetRecordingFrame :
        TerminalRenderFrame,
        TerminalRenderFrameReader {
        private var resolvedOffset: Int = 0
        private var resolvedRows: Int = 1

        var lastRequestedOffset: Int = -1
            private set
        var lastRequestedRows: Int = -1
            private set

        override val columns: Int = 3
        override val rows: Int
            get() = resolvedRows
        override val historySize: Int = 4
        override val scrollbackOffset: Int
            get() = resolvedOffset
        override val frameGeneration: Long = 1L
        override val structureGeneration: Long = 1L
        override val activeBuffer: TerminalRenderBufferKind = TerminalRenderBufferKind.PRIMARY
        override val cursor: TerminalRenderCursor
            get() =
                TerminalRenderCursor(
                    column = 0,
                    row = 0,
                    visible = resolvedOffset == 0,
                    blinking = false,
                    shape = TerminalRenderCursorShape.BLOCK,
                    generation = 1L,
                )

        override fun lineGeneration(row: Int): Long = 1L

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
        ) {
            val text = if (resolvedOffset == 0) "new" else "old"
            for (i in 0 until columns) {
                codeWords[codeOffset + i] = text[i].code
                flags[flagOffset + i] = TerminalRenderCellFlags.CODEPOINT
            }
        }

        override fun readRenderFrame(consumer: TerminalRenderFrameConsumer) {
            readRenderFrame(scrollbackOffset = 0, consumer = consumer)
        }

        override fun readRenderFrame(
            scrollbackOffset: Int,
            consumer: TerminalRenderFrameConsumer,
        ) {
            lastRequestedOffset = scrollbackOffset
            lastRequestedRows = 0
            resolvedOffset = scrollbackOffset.coerceIn(0, historySize)
            resolvedRows = 1
            consumer.accept(this)
        }

        override fun readRenderFrame(
            scrollbackOffset: Int,
            viewportRows: Int,
            consumer: TerminalRenderFrameConsumer,
        ) {
            lastRequestedOffset = scrollbackOffset
            lastRequestedRows = viewportRows
            resolvedOffset = scrollbackOffset.coerceIn(0, historySize)
            resolvedRows = viewportRows.coerceAtLeast(1)
            consumer.accept(this)
        }
    }
}

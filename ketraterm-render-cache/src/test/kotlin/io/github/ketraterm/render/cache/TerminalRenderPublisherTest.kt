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
package io.github.ketraterm.render.cache

import io.github.ketraterm.render.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class TerminalRenderPublisherTest {
    @Test
    fun `reader ABI does not expose buffer bookkeeping`() {
        val internals = setOf("getBuffers", "getReaderCounts", "getFrontIndex", "getPublishLock")
        assertTrue(TerminalRenderPublisher::class.java.methods.none { it.name in internals }, "Reader ABI exposes buffer representation")
    }

    @Test
    fun `empty publication does not invoke the reader`() {
        val publisher = TerminalRenderPublisher(1, 1)
        assertNull(publisher.readCurrent<Unit> { fail("No frame has been published") })
    }

    @Test
    fun `retired buffers release image references and restore unchanged images on reuse`() {
        val publisher = TerminalRenderPublisher(3, 1)
        val frame = ImageFrame()
        publisher.updateAndPublish(frame)
        val retired = checkNotNull(publisher.current())
        val image = checkNotNull(frame.image)

        frame.image = null
        publisher.updateAndPublish(frame)
        assertEquals(0, retired.images.size)
        assertEquals(0, publisher.current()?.images?.size)
        assertEquals("abc", retired.rowText(0), "Retirement preserves reusable row storage")
        frame.image = image
        repeat(2) { publisher.updateAndPublish(frame) }

        assertSame(retired, publisher.current(), "The original buffer is reused after rotation")
        assertSame(image, retired.images.image(0), "Equal graphics generations must still restore retired references")
    }

    @Test
    fun `retired image snapshots stay pinned until their final lease is released`() {
        val publisher = TerminalRenderPublisher(3, 1)
        val frame = ImageFrame()
        publisher.updateAndPublish(frame)
        val first = checkNotNull(publisher.acquireFrontLease())
        val second = checkNotNull(publisher.acquireFrontLease())
        val old = checkNotNull(frame.image)
        var firstReleased = false
        try {
            frame.image = TestImage()
            publisher.updateAndPublish(frame)
            publisher.updateAndPublish(frame)
            assertSame(old, first.images.image(0))
            publisher.releaseFrontLease(first)
            firstReleased = true
            assertSame(old, second.images.image(0), "Another reader still pins the retired image")
        } finally {
            if (!firstReleased) publisher.releaseFrontLease(first)
            publisher.releaseFrontLease(second)
        }
        assertEquals(0, second.images.size)
        assertSame(frame.image, publisher.current()?.images?.image(0))
    }

    @Test
    fun `retired lease cleanup preserves a concurrent writer's image references`() {
        val publisher = TerminalRenderPublisher(3, 1)
        val frame = ImageFrame()
        publisher.updateAndPublish(frame)
        publisher.updateAndPublish(frame)
        val pinned = checkNotNull(publisher.acquireFrontLease())
        publisher.updateAndPublish(frame)
        val copyEntered = CountDownLatch(1)
        val releaseCopy = CountDownLatch(1)
        frame.image = TestImage()
        frame.afterImageCopy = {
            copyEntered.countDown()
            releaseCopy.await()
        }
        var leaseReleased = false
        TestWorker { publisher.updateAndPublish(frame) }.use { writer ->
            try {
                assertTrue(copyEntered.await(10, TimeUnit.SECONDS), "writer did not copy its image")
                publisher.releaseFrontLease(pinned)
                leaseReleased = true
                assertEquals(0, pinned.images.size)
            } finally {
                if (!leaseReleased) publisher.releaseFrontLease(pinned)
                releaseCopy.countDown()
            }
            writer.await()
        }
        assertSame(frame.image, publisher.current()?.images?.image(0))
    }

    @Test
    fun `failed publication releases staged images while preserving the current frame`() {
        val publisher = TerminalRenderPublisher(3, 1)
        val frame = ImageFrame()
        publisher.updateAndPublish(frame)
        val retired = checkNotNull(publisher.current())
        publisher.updateAndPublish(frame)
        publisher.updateAndPublish(frame)
        val current = checkNotNull(publisher.current())
        val currentImage = checkNotNull(frame.image)
        frame.image = TestImage()
        frame.failRead = true

        assertThrows(IllegalStateException::class.java) { publisher.updateAndPublish(frame) }

        assertEquals(0, retired.images.size, "A failed reader must not retain its completed staging copy")
        assertSame(current, publisher.current())
        assertSame(currentImage, current.images.image(0))
        frame.failRead = false
        publisher.updateAndPublish(frame)
        assertSame(frame.image, publisher.current()?.images?.image(0))
    }

    @Test
    fun `non-local return and callback failure release all reader leases`() {
        val publisher = TerminalRenderPublisher(3, 1)
        publisher.updateAndPublish(MockFrame(3, 1, "abc"))
        val original = checkNotNull(publisher.acquireFrontLease())
        publisher.releaseFrontLease(original)

        fun firstCodepoint(): Int {
            publisher.readCurrent { return it.codeWords[0] }
            return 0
        }
        assertEquals('a'.code, firstCodepoint())
        val failure = IllegalStateException("Reader failed")
        assertSame(failure, assertThrows(IllegalStateException::class.java) { publisher.readCurrent { throw failure } })
        assertThrows(IllegalStateException::class.java) { publisher.releaseFrontLease(original) }
        // Every returned/failed callback leaves its own buffer with no outstanding lease.
        TestWorker {
            repeat(9) {
                publisher.updateAndPublish(MockFrame(3, 1, "abc"))
                val current = checkNotNull(publisher.acquireFrontLease())
                publisher.releaseFrontLease(current)
                assertEquals('a'.code, firstCodepoint())
                assertSame(failure, assertThrows(IllegalStateException::class.java) { publisher.readCurrent { throw failure } })
                assertThrows(IllegalStateException::class.java) { publisher.releaseFrontLease(current) }
            }
        }.use { it.await() }
        publisher.updateAndPublish(MockFrame(3, 1, "def"))
        assertEquals("def", publisher.readCurrent { it.rowText(0) })
    }

    @Test
    fun `manual leases reject foreign and unmatched release without damaging publication`() {
        val publisher = TerminalRenderPublisher(3, 1)
        assertNull(publisher.acquireFrontLease())
        publisher.updateAndPublish(MockFrame(3, 1, "abc"))
        val first = checkNotNull(publisher.acquireFrontLease())
        val second = checkNotNull(publisher.acquireFrontLease())
        assertSame(first, second)
        assertThrows(IllegalArgumentException::class.java) { publisher.releaseFrontLease(TerminalRenderCache(3, 1)) }
        publisher.releaseFrontLease(first)
        publisher.updateAndPublish(MockFrame(3, 1, "def"))
        publisher.updateAndPublish(MockFrame(3, 1, "ghi"))
        assertEquals("abc", second.rowText(0))
        publisher.releaseFrontLease(second)
        assertThrows(IllegalStateException::class.java) { publisher.releaseFrontLease(second) }
        publisher.updateAndPublish(MockFrame(3, 1, "jkl"))
        assertEquals("jkl", publisher.readCurrent { it.rowText(0) })
    }

    @Test
    fun `writer buffers can be reused by different worker threads`() {
        val publisher = TerminalRenderPublisher(3, 1)
        val texts = listOf("abc", "def", "ghi", "jkl", "mno", "pqr")

        texts.forEach { text ->
            TestWorker { publisher.updateAndPublish(MockFrame(3, 1, text)) }.use { it.await() }
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
    fun `publication completes after the frame copy is released`() {
        val publisher = TerminalRenderPublisher(3, 1)
        val frame = BlockingMockFrame(3, 1, "abc")
        TestWorker { publisher.updateAndPublish(frame) }.use { writer ->
            try {
                assertTrue(frame.awaitCopy(), "writer did not enter copyLine")
            } finally {
                frame.releaseCopy()
            }
            writer.await()
        }
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
        TestWorker { publisher.updateAndPublish(frame) }.use { writer ->
            try {
                assertTrue(frame.awaitOverscanRowCopy(), "writer did not reach overscan row")
            } finally {
                frame.releaseOverscanRowCopy()
            }
            writer.await()
        }

        val current = publisher.current()
        assertNotNull(current)
        val clusterRef = current!!.clusterRefs[current.rowOffset(30)]
        val clusterOffset = current.clusterOffset(clusterRef)
        val clusterLength = current.clusterLength(clusterRef)
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

        TestWorker { publisher.updateAndPublish(firstFrame) }.use { it.await() }
        TestWorker { publisher.updateAndPublish(secondFrame) }.use { it.await() }
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
        TestWorker {
            publisher.readCurrent { front ->
                readerEntered.countDown()
                assertEquals("abc", front.rowText(0))
                releaseReader.await()
                assertEquals("abc", front.rowText(0))
            }
        }.use { reader ->
            try {
                assertTrue(readerEntered.await(10, TimeUnit.SECONDS), "reader did not acquire a snapshot")
                TestWorker { publisher.updateAndPublish(MockFrame(3, 1, "def")) }.use { writer ->
                    writer.await()
                }
            } finally {
                releaseReader.countDown()
            }
            reader.await()
        }

        assertEquals("def", publisher.current()?.rowText(0))
    }

    @Test
    fun `readCurrent keeps pinned snapshot stable across multiple publishes`() {
        val publisher = TerminalRenderPublisher(3, 1)
        publisher.updateAndPublish(MockFrame(3, 1, "abc"))

        val readerEntered = CountDownLatch(1)
        val releaseReader = CountDownLatch(1)

        TestWorker {
            publisher.readCurrent { front ->
                readerEntered.countDown()
                assertEquals("abc", front.rowText(0))
                releaseReader.await()
                assertEquals("abc", front.rowText(0))
            }
        }.use { reader ->
            try {
                assertTrue(readerEntered.await(10, TimeUnit.SECONDS), "reader did not acquire a snapshot")
                TestWorker {
                    publisher.updateAndPublish(MockFrame(3, 1, "def"))
                    publisher.updateAndPublish(MockFrame(3, 1, "ghi"))
                }.use { it.await() }
            } finally {
                releaseReader.countDown()
            }
            reader.await()
        }

        assertEquals("ghi", publisher.current()?.rowText(0))
    }

    /** Keeps real thread ownership tests bounded and propagates worker assertions to JUnit. */
    private class TestWorker(
        action: () -> Unit,
    ) : AutoCloseable {
        private val result = FutureTask(action)
        private val worker = thread(isDaemon = true, block = result::run)

        fun await() {
            result.get(10, TimeUnit.SECONDS)
        }

        override fun close() {
            result.cancel(true)
            worker.join(10_000)
            assertFalse(worker.isAlive, "worker did not stop")
        }
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

    private class TestImage : TerminalRenderImage {
        override val width = 1
        override val height = 1

        override fun copyArgb(
            destination: IntArray,
            offset: Int,
        ) {
            destination[offset] = 0xffff0000.toInt()
        }
    }

    private class ImageFrame : MockFrame(3, 1, "abc") {
        var image: TerminalRenderImage? = TestImage()
        var afterImageCopy: (() -> Unit)? = null
        var failRead = false

        override val graphicsGeneration = 1L

        override fun copyImages(sink: TerminalRenderImageSink) {
            image?.let { sink.accept(it, 0.0, 0.0, 1.0, 1.0, 0, 0, 1, 1, 0.0, 1.0, 0) }
            afterImageCopy?.invoke()
        }

        override fun readRenderFrame(consumer: TerminalRenderFrameConsumer) {
            super.readRenderFrame(consumer)
            check(!failRead) { "reader failed after delivering its frame" }
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
            releaseCopy.await()
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

        fun awaitCopy(): Boolean = copyEntered.await(10, TimeUnit.SECONDS)

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
                releaseOverscanRowCopy.await()
                codeWords[codeOffset] = 0
                flags[flagOffset] = TerminalRenderCellFlags.CLUSTER
                clusterSink?.onCluster(0, "e\u0301")
                clusterDataSink?.onCluster(0, intArrayOf('e'.code, 0x0301), 0, 2)
            } else {
                codeWords[codeOffset] = 'x'.code
                flags[flagOffset] = TerminalRenderCellFlags.CODEPOINT
            }
        }

        fun awaitOverscanRowCopy(): Boolean = overscanRowCopyEntered.await(10, TimeUnit.SECONDS)

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

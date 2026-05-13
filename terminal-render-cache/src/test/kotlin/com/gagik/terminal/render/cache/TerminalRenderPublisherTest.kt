package com.gagik.terminal.render.cache

import com.gagik.terminal.render.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class TerminalRenderPublisherTest {

    @Test
    fun `TerminalRenderCache ownership assertion`() {
        val cache = TerminalRenderCache(3, 1)
        cache.assertOwnership() // owner = main

        var exception: Throwable? = null
        val t = thread(start = true) {
            try {
                cache.assertOwnership()
            } catch (e: Throwable) {
                exception = e
            }
        }
        t.join()

        assertNotNull(exception)
        assertTrue(exception is IllegalStateException)
        assertTrue(exception?.message?.contains("owned by") == true)
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
    fun `resize updates all buffers`() {
        val publisher = TerminalRenderPublisher(3, 1)
        publisher.resize(5, 2)

        // Triple buffering: check other buffers via updateAndPublish
        val frame = MockFrame(5, 2, "12345")
        publisher.updateAndPublish(frame)
        assertEquals("12345", publisher.current()?.rowText(0))
    }

    @Test
    fun `resize resets buffer ownership for next render worker update`() {
        val publisher = TerminalRenderPublisher(3, 1)
        val firstFrame = MockFrame(3, 1, "abc")
        val secondFrame = MockFrame(5, 2, "12345")

        val firstRenderThread = thread(start = true) {
            publisher.updateAndPublish(firstFrame)
        }
        firstRenderThread.join()

        publisher.resize(5, 2)

        var exception: Throwable? = null
        val secondRenderThread = thread(start = true) {
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
    fun `readCurrent pins front buffer while reader is active`() {
        val publisher = TerminalRenderPublisher(3, 1)
        publisher.updateAndPublish(MockFrame(3, 1, "abc"))

        val readerEntered = CountDownLatch(1)
        val releaseReader = CountDownLatch(1)
        val writerFinished = AtomicBoolean(false)

        val reader = thread(start = true) {
            publisher.readCurrent { front ->
                readerEntered.countDown()
                assertEquals("abc", front.rowText(0))
                assertTrue(releaseReader.await(1, TimeUnit.SECONDS))
                assertEquals("abc", front.rowText(0))
            }
        }

        assertTrue(readerEntered.await(1, TimeUnit.SECONDS))

        val writer = thread(start = true) {
            publisher.updateAndPublish(MockFrame(3, 1, "def"))
            writerFinished.set(true)
        }

        Thread.sleep(50)
        assertFalse(writerFinished.get(), "writer recycled a buffer while readCurrent was active")

        releaseReader.countDown()
        reader.join()
        writer.join()

        assertEquals("def", publisher.current()?.rowText(0))
    }

    private fun TerminalRenderCache.rowText(row: Int): String =
        codeWords[row].map { if (it == 0) ' ' else it.toChar() }.joinToString("")

    private class MockFrame(
        override val columns: Int,
        override val rows: Int,
        val text: String
    ) : TerminalRenderFrame, TerminalRenderFrameReader {

        override val frameGeneration: Long = 1L
        override val structureGeneration: Long = 1L
        override val activeBuffer: TerminalRenderBufferKind = TerminalRenderBufferKind.PRIMARY
        override val cursor: TerminalRenderCursor = TerminalRenderCursor(0, 0, true, false, TerminalRenderCursorShape.BLOCK, 1L)

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
            clusterSink: TerminalRenderClusterSink?
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

    private class OffsetRecordingFrame : TerminalRenderFrame, TerminalRenderFrameReader {
        private var resolvedOffset: Int = 0

        var lastRequestedOffset: Int = -1
            private set

        override val columns: Int = 3
        override val rows: Int = 1
        override val historySize: Int = 4
        override val scrollbackOffset: Int
            get() = resolvedOffset
        override val frameGeneration: Long = 1L
        override val structureGeneration: Long = 1L
        override val activeBuffer: TerminalRenderBufferKind = TerminalRenderBufferKind.PRIMARY
        override val cursor: TerminalRenderCursor
            get() = TerminalRenderCursor(
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
            clusterSink: TerminalRenderClusterSink?
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

        override fun readRenderFrame(scrollbackOffset: Int, consumer: TerminalRenderFrameConsumer) {
            lastRequestedOffset = scrollbackOffset
            resolvedOffset = scrollbackOffset.coerceIn(0, historySize)
            consumer.accept(this)
        }
    }
}

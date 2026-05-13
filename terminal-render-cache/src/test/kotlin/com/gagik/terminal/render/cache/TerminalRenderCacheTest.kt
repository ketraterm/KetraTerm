package com.gagik.terminal.render.cache

import com.gagik.terminal.render.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TerminalRenderCacheTest {
    @Test
    fun `constructor rejects non-positive dimensions`() {
        assertAll(
            { assertThrows(IllegalArgumentException::class.java) { TerminalRenderCache(0, 1) } },
            { assertThrows(IllegalArgumentException::class.java) { TerminalRenderCache(1, 0) } },
        )
    }

    @Test
    fun `first update copies all visible rows`() {
        val frame = MutableFrame(columns = 3, rows = 2)
        frame.setRow(0, "abc")
        frame.setRow(1, "def")
        val cache = TerminalRenderCache(columns = 3, rows = 2)

        cache.updateFrom(frame.reader)

        assertAll(
            { assertEquals("abc", cache.rowText(0)) },
            { assertEquals("def", cache.rowText(1)) },
            { assertTrue(cache.dirtyRows.contentEquals(booleanArrayOf(true, true))) },
            { assertEquals(frame.frameGeneration, cache.frameGeneration) },
            { assertEquals(frame.structureGeneration, cache.structureGeneration) },
            { assertEquals(frame.cursor, cache.cursor) },
            { assertEquals(1, frame.copyCounts[0]) },
            { assertEquals(1, frame.copyCounts[1]) },
        )
    }

    @Test
    fun `second update skips rows with unchanged structure and line generation`() {
        val frame = MutableFrame(columns = 3, rows = 2)
        frame.setRow(0, "abc")
        frame.setRow(1, "def")
        val cache = TerminalRenderCache(columns = 3, rows = 2)

        cache.updateFrom(frame.reader)
        cache.updateFrom(frame.reader)

        assertAll(
            { assertTrue(cache.dirtyRows.contentEquals(booleanArrayOf(false, false))) },
            { assertEquals(1, frame.copyCounts[0]) },
            { assertEquals(1, frame.copyCounts[1]) },
        )
    }

    @Test
    fun `line generation change recopies only that row`() {
        val frame = MutableFrame(columns = 3, rows = 2)
        frame.setRow(0, "abc")
        frame.setRow(1, "def")
        val cache = TerminalRenderCache(columns = 3, rows = 2)
        cache.updateFrom(frame.reader)

        frame.setRow(1, "xyz")
        cache.updateFrom(frame.reader)

        assertAll(
            { assertEquals("abc", cache.rowText(0)) },
            { assertEquals("xyz", cache.rowText(1)) },
            { assertTrue(cache.dirtyRows.contentEquals(booleanArrayOf(false, true))) },
            { assertEquals(1, frame.copyCounts[0]) },
            { assertEquals(2, frame.copyCounts[1]) },
        )
    }

    @Test
    fun `structure generation change recopies all rows`() {
        val frame = MutableFrame(columns = 3, rows = 2)
        frame.setRow(0, "abc")
        frame.setRow(1, "def")
        val cache = TerminalRenderCache(columns = 3, rows = 2)
        cache.updateFrom(frame.reader)

        frame.structureGeneration++
        cache.updateFrom(frame.reader)

        assertAll(
            { assertTrue(cache.dirtyRows.contentEquals(booleanArrayOf(true, true))) },
            { assertEquals(2, frame.copyCounts[0]) },
            { assertEquals(2, frame.copyCounts[1]) },
        )
    }

    @Test
    fun `shape change resizes storage and recopies all rows`() {
        val frame = MutableFrame(columns = 2, rows = 1)
        frame.setRow(0, "ab")
        val cache = TerminalRenderCache(columns = 3, rows = 2)

        cache.updateFrom(frame.reader)

        assertAll(
            { assertTrue(cache.resizedOnLastUpdate) },
            { assertEquals(2, cache.columns) },
            { assertEquals(1, cache.rows) },
            { assertEquals("ab", cache.rowText(0)) },
            { assertTrue(cache.dirtyRows.contentEquals(booleanArrayOf(true))) },
        )
    }

    @Test
    fun `cluster text is copied and cleared when row is recopied`() {
        val frame = MutableFrame(columns = 3, rows = 1)
        frame.setClusterRow("e\u0301x")
        val cache = TerminalRenderCache(columns = 3, rows = 1)
        cache.updateFrom(frame.reader)

        assertEquals("e\u0301", cache.clusters[0][0])

        frame.setRow(0, "abc")
        cache.updateFrom(frame.reader)

        assertAll(
            { assertNull(cache.clusters[0][0]) },
            { assertEquals("abc", cache.rowText(0)) },
        )
    }

    @Test
    fun `cursor change updates cursor without dirtying rows`() {
        val frame = MutableFrame(columns = 3, rows = 1)
        frame.setRow(0, "abc")
        val cache = TerminalRenderCache(columns = 3, rows = 1)
        cache.updateFrom(frame.reader)

        frame.cursor = frame.cursor.copy(column = 2, generation = frame.cursor.generation + 1)
        frame.frameGeneration++
        cache.updateFrom(frame.reader)

        assertAll(
            { assertTrue(cache.cursorChangedOnLastUpdate) },
            { assertEquals(2, cache.cursor?.column) },
            { assertTrue(cache.dirtyRows.contentEquals(booleanArrayOf(false))) },
            { assertEquals(1, frame.copyCounts[0]) },
        )
    }

    @Test
    fun `active buffer is cached`() {
        val frame = MutableFrame(columns = 3, rows = 1)
        frame.activeBuffer = TerminalRenderBufferKind.ALTERNATE
        val cache = TerminalRenderCache(columns = 3, rows = 1)

        cache.updateFrom(frame.reader)

        assertEquals(TerminalRenderBufferKind.ALTERNATE, cache.activeBuffer)
    }

    @Test
    fun `scrollback offset change recopies rows even when generations match`() {
        val frame = OffsetFrame()
        val cache = TerminalRenderCache(columns = 3, rows = 1)

        cache.updateFrom(frame.reader, scrollbackOffset = 0)
        cache.updateFrom(frame.reader, scrollbackOffset = 1)

        assertAll(
            { assertEquals("old", cache.rowText(0)) },
            { assertEquals(2, cache.historySize) },
            { assertEquals(1, cache.scrollbackOffset) },
            { assertTrue(cache.dirtyRows.contentEquals(booleanArrayOf(true))) },
            { assertFalse(cache.cursor?.visible == true) },
            { assertTrue(cache.cursorChangedOnLastUpdate) },
            { assertEquals(2, frame.copyCount) },
        )
    }

    private fun TerminalRenderCache.rowText(row: Int): String =
        codeWords[row].map { if (it == 0) ' ' else it.toChar() }.joinToString("")

    private class MutableFrame(
        override val columns: Int,
        override val rows: Int,
    ) : TerminalRenderFrame {
        private val codeWords = Array(rows) { IntArray(columns) }
        private val flags = Array(rows) { IntArray(columns) { TerminalRenderCellFlags.EMPTY } }
        private val clusters = Array(rows) { arrayOfNulls<String>(columns) }

        val copyCounts = IntArray(rows)

        override var frameGeneration: Long = 0L
        override var structureGeneration: Long = 0L
        override var activeBuffer: TerminalRenderBufferKind = TerminalRenderBufferKind.PRIMARY
        override var cursor: TerminalRenderCursor = TerminalRenderCursor(
            column = 0,
            row = 0,
            visible = true,
            blinking = false,
            shape = TerminalRenderCursorShape.BLOCK,
            generation = 0L,
        )

        private val lineGenerations = LongArray(rows)
        private val wrapped = BooleanArray(rows)

        val reader = object : TerminalRenderFrameReader {
            override fun readRenderFrame(consumer: TerminalRenderFrameConsumer) {
                consumer.accept(this@MutableFrame)
            }
        }

        fun setRow(row: Int, text: String) {
            require(text.length <= columns)
            var col = 0
            while (col < columns) {
                val code = text.getOrNull(col)?.code ?: 0
                codeWords[row][col] = code
                flags[row][col] = if (code == 0) TerminalRenderCellFlags.EMPTY else TerminalRenderCellFlags.CODEPOINT
                clusters[row][col] = null
                col++
            }
            lineGenerations[row]++
            frameGeneration++
        }

        fun setClusterRow(text: String) {
            codeWords[0].fill(0)
            flags[0].fill(TerminalRenderCellFlags.EMPTY)
            clusters[0].fill(null)
            flags[0][0] = TerminalRenderCellFlags.CLUSTER
            clusters[0][0] = "e\u0301"
            codeWords[0][1] = 'x'.code
            flags[0][1] = TerminalRenderCellFlags.CODEPOINT
            lineGenerations[0]++
            frameGeneration++
            require(text.isNotEmpty())
        }

        override fun lineGeneration(row: Int): Long = lineGenerations[row]

        override fun lineWrapped(row: Int): Boolean = wrapped[row]

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
        ) {
            copyCounts[row]++
            var col = 0
            while (col < columns) {
                codeWords[codeOffset + col] = this.codeWords[row][col]
                attrWords[attrOffset + col] = TerminalRenderAttrs.DEFAULT
                flags[flagOffset + col] = this.flags[row][col]
                extraAttrWords?.set(extraAttrOffset + col, TerminalRenderExtraAttrs.DEFAULT)
                hyperlinkIds?.set(hyperlinkOffset + col, 0)
                val cluster = clusters[row][col]
                if (cluster != null) {
                    clusterSink?.onCluster(col, cluster)
                }
                col++
            }
        }
    }

    private class OffsetFrame : TerminalRenderFrame {
        private var currentOffset: Int = 0

        var copyCount: Int = 0
            private set

        override val columns: Int = 3
        override val rows: Int = 1
        override val historySize: Int = 2
        override val scrollbackOffset: Int
            get() = currentOffset
        override val frameGeneration: Long = 1L
        override val structureGeneration: Long = 1L
        override val activeBuffer: TerminalRenderBufferKind = TerminalRenderBufferKind.PRIMARY
        override val cursor: TerminalRenderCursor
            get() = TerminalRenderCursor(
                column = 0,
                row = 0,
                visible = currentOffset == 0,
                blinking = false,
                shape = TerminalRenderCursorShape.BLOCK,
                generation = 1L,
            )

        val reader = object : TerminalRenderFrameReader {
            override fun readRenderFrame(consumer: TerminalRenderFrameConsumer) {
                readRenderFrame(scrollbackOffset = 0, consumer = consumer)
            }

            override fun readRenderFrame(scrollbackOffset: Int, consumer: TerminalRenderFrameConsumer) {
                currentOffset = scrollbackOffset.coerceIn(0, historySize)
                consumer.accept(this@OffsetFrame)
            }
        }

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
        ) {
            copyCount++
            val text = if (currentOffset == 0) "new" else "old"
            var col = 0
            while (col < columns) {
                codeWords[codeOffset + col] = text[col].code
                attrWords[attrOffset + col] = TerminalRenderAttrs.DEFAULT
                flags[flagOffset + col] = TerminalRenderCellFlags.CODEPOINT
                extraAttrWords?.set(extraAttrOffset + col, TerminalRenderExtraAttrs.DEFAULT)
                hyperlinkIds?.set(hyperlinkOffset + col, 0)
                col++
            }
        }
    }
}

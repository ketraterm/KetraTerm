package com.gagik.core.render

import com.gagik.core.state.TerminalState
import com.gagik.terminal.render.api.*

/**
 * Adapter from core state to the stable public render frame ABI.
 */
internal class CoreTerminalRenderFrame(
    private val state: TerminalState,
) : TerminalRenderFrame {
    private val attrTranslator = RenderAttrTranslator()
    private val clusterScratch = RenderClusterScratch()
    private var isValid: Boolean = false
    private var resolvedScrollbackOffset: Int = 0
    private var resolvedRows: Int = 0

    internal fun <T> use(scrollbackOffset: Int, block: () -> T): T {
        return use(scrollbackOffset, state.dimensions.height, block)
    }

    internal fun <T> use(scrollbackOffset: Int, viewportRows: Int, block: () -> T): T {
        require(viewportRows > 0) { "viewportRows must be > 0, was $viewportRows" }
        resolvedScrollbackOffset = state.clampScrollbackOffset(scrollbackOffset)
        resolvedRows = viewportRows.coerceAtMost(state.dimensions.height + resolvedScrollbackOffset)
        isValid = true
        try {
            return block()
        } finally {
            isValid = false
            resolvedScrollbackOffset = 0
            resolvedRows = 0
        }
    }

    private fun checkValid() {
        check(isValid) { "TerminalRenderFrame is only valid inside the readRenderFrame callback" }
    }

    override val columns: Int
        get() {
            checkValid()
            return state.dimensions.width
        }

    override val rows: Int
        get() {
            checkValid()
            return resolvedRows
        }

    override val historySize: Int
        get() {
            checkValid()
            return state.historySize
        }

    override val scrollbackOffset: Int
        get() {
            checkValid()
            return resolvedScrollbackOffset
        }

    override val frameGeneration: Long
        get() {
            checkValid()
            return state.frameGeneration
        }

    override val structureGeneration: Long
        get() {
            checkValid()
            return state.structureGeneration
        }

    override val activeBuffer: TerminalRenderBufferKind
        get() {
            checkValid()
            return if (state.isAltScreenActive) {
                TerminalRenderBufferKind.ALTERNATE
            } else {
                TerminalRenderBufferKind.PRIMARY
            }
        }

    override val cursor: TerminalRenderCursor
        get() {
            checkValid()
            val row = state.cursor.row + resolvedScrollbackOffset
            return TerminalRenderCursor(
                column = state.cursor.col,
                row = row,
                visible = state.modes.isCursorVisible && row in 0 until resolvedRows,
                blinking = state.modes.isCursorBlinking,
                shape = TerminalRenderCursorShape.BLOCK,
                generation = state.cursorGeneration,
            )
        }

    override fun copyCursor(sink: TerminalRenderCursorSink) {
        checkValid()
        val row = state.cursor.row + resolvedScrollbackOffset
        sink.onCursor(
            column = state.cursor.col,
            row = row,
            visible = state.modes.isCursorVisible && row in 0 until resolvedRows,
            blinking = state.modes.isCursorBlinking,
            shape = TerminalRenderCursorShape.BLOCK,
            generation = state.cursorGeneration,
        )
    }

    override fun lineGeneration(row: Int): Long {
        checkValid()
        checkRow(row)
        return visibleLineAt(row).renderGeneration
    }

    override fun lineWrapped(row: Int): Boolean {
        checkValid()
        checkRow(row)
        return visibleLineAt(row).wrapped
    }

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
        checkValid()
        checkRow(row)
        checkCapacity(codeWords, codeOffset, columns, "codeWords")
        checkCapacity(attrWords, attrOffset, columns, "attrWords")
        checkCapacity(flags, flagOffset, columns, "flags")
        if (extraAttrWords != null) {
            checkCapacity(extraAttrWords, extraAttrOffset, columns, "extraAttrWords")
        }
        if (hyperlinkIds != null) {
            checkCapacity(hyperlinkIds, hyperlinkOffset, columns, "hyperlinkIds")
        }

        visibleLineAt(row).copyToRenderAbi(
            width = columns,
            codeWords = codeWords,
            codeOffset = codeOffset,
            attrWords = attrWords,
            attrOffset = attrOffset,
            flags = flags,
            flagOffset = flagOffset,
            extraAttrWords = extraAttrWords,
            extraAttrOffset = extraAttrOffset,
            hyperlinkIds = hyperlinkIds,
            hyperlinkOffset = hyperlinkOffset,
            clusterSink = clusterSink,
            clusterDataSink = clusterDataSink,
            attrTranslator = attrTranslator,
            clusterScratch = clusterScratch,
            reverseVideo = state.modes.isReverseVideo,
        )
    }

    private fun visibleLineAt(row: Int) =
        state.ring[state.resolveScrollbackRingIndex(row, resolvedScrollbackOffset)]

    private fun checkRow(row: Int) {
        require(row in 0 until rows) {
            "row out of bounds: $row, rows=$rows"
        }
    }

    private fun checkCapacity(array: IntArray, offset: Int, length: Int, name: String) {
        require(offset >= 0 && array.size - offset >= length) {
            "$name has insufficient capacity: size=${array.size}, offset=$offset, required=$length"
        }
    }

    private fun checkCapacity(array: LongArray, offset: Int, length: Int, name: String) {
        require(offset >= 0 && array.size - offset >= length) {
            "$name has insufficient capacity: size=${array.size}, offset=$offset, required=$length"
        }
    }
}

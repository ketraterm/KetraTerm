package com.gagik.core.buffer.impl

import com.gagik.core.api.TerminalLineApi
import com.gagik.core.api.TerminalReader
import com.gagik.core.model.TerminalConstants
import com.gagik.core.model.VoidLine
import com.gagik.core.state.TerminalState

internal class TerminalReaderImpl(
	private val state: TerminalState
) : TerminalReader {

	override val width: Int get() = state.dimensions.width
	override val height: Int get() = state.dimensions.height
	override val windowTitle: String get() = state.windowTitle
	override val iconTitle: String get() = state.iconTitle
	override val cursorCol: Int get() = state.cursor.col
	override val cursorRow: Int get() = state.cursor.row
	override val historySize: Int
		get() = state.historySize

	override fun getLine(row: Int): TerminalLineApi {
		if (!state.dimensions.isValidRow(row)) return VoidLine
		return state.ring[state.resolveRingIndex(row)]
	}

	override fun getCodepointAt(col: Int, row: Int): Int {
		if (!state.dimensions.isValidCol(col)) return TerminalConstants.EMPTY
		val line = getLine(row)
		return if (line.width == 0) TerminalConstants.EMPTY else line.getCodepoint(col)
	}

	override fun getPackedAttrAt(col: Int, row: Int): Long {
		if (!state.dimensions.isValidCol(col)) return state.pen.currentAttr
		val line = getLine(row)
		return if (line.width == 0) state.pen.currentAttr else line.getPackedAttr(col)
	}

	override fun getPackedExtendedAttrAt(col: Int, row: Int): Long {
		if (!state.dimensions.isValidCol(col)) return state.pen.currentExtendedAttr
		val line = getLine(row)
		return if (line.width == 0) state.pen.currentExtendedAttr else line.getPackedExtendedAttr(col)
	}
}

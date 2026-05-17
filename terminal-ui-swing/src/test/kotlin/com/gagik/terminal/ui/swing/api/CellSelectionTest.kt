package com.gagik.terminal.ui.swing.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CellSelectionTest {
    @Test
    fun `single row selection exposes half-open range`() {
        val selection = CellSelection(anchorColumn = 2, anchorRow = 0, caretColumn = 5, caretRow = 0)

        val range = selection.packedColumnRange(row = 0, columns = 10)

        assertEquals(2, CellSelection.rangeStart(range))
        assertEquals(5, CellSelection.rangeEnd(range))
    }

    @Test
    fun `backward multi row selection normalizes ranges`() {
        val selection = CellSelection(anchorColumn = 7, anchorRow = 2, caretColumn = 2, caretRow = 0)

        val first = selection.packedColumnRange(row = 0, columns = 10)
        val middle = selection.packedColumnRange(row = 1, columns = 10)
        val last = selection.packedColumnRange(row = 2, columns = 10)

        assertEquals(2, CellSelection.rangeStart(first))
        assertEquals(10, CellSelection.rangeEnd(first))
        assertEquals(0, CellSelection.rangeStart(middle))
        assertEquals(10, CellSelection.rangeEnd(middle))
        assertEquals(0, CellSelection.rangeStart(last))
        assertEquals(7, CellSelection.rangeEnd(last))
    }
}

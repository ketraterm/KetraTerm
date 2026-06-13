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
package io.github.jvterm.core.engine

import io.github.jvterm.core.model.Line
import io.github.jvterm.core.state.TerminalState
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("CursorEngine")
class CursorEngineTest {
    // ── Fixtures ──────────────────────────────────────────────────────────

    private fun state(
        width: Int = 6,
        height: Int = 4,
        history: Int = 2,
    ) = TerminalState(width, height, maxHistory = history)

    private fun engine(state: TerminalState) = CursorEngine(state)

    private fun line(
        state: TerminalState,
        row: Int,
    ): Line = state.ring[state.resolveRingIndex(row)]

    private fun seed(
        state: TerminalState,
        row: Int,
        text: String,
    ) {
        val l = line(state, row)
        text.forEachIndexed { i, c -> if (i < l.width) l.setCell(i, c.code, 0) }
    }

    private fun snapshot(state: TerminalState): String =
        buildString {
            for (r in 0 until state.dimensions.height) {
                if (r > 0) append('\n')
                append(line(state, r).toTextTrimmed())
            }
        }

    // Narrow helper: assert no grid mutation occurred
    private fun assertGridUnchanged(
        before: String,
        state: TerminalState,
    ) = assertEquals(before, snapshot(state), "grid must not be mutated")

    // Scroll margins now live in ScreenBuffer and are private-set; use DECSTBM helper.
    private fun setScrollRegion(
        state: TerminalState,
        top: Int,
        bottom: Int,
    ) {
        val col = state.cursor.col
        val row = state.cursor.row
        val pendingWrap = state.cursor.pendingWrap
        state.activeBuffer.setScrollRegion(
            top = top + 1,
            bottom = bottom + 1,
            isOriginMode = state.modes.isOriginMode,
            viewportHeight = state.dimensions.height,
        )
        state.cursor.col = col
        state.cursor.row = row
        state.cursor.pendingWrap = pendingWrap
    }

    // ── carriageReturn ────────────────────────────────────────────────────

    @Nested
    @DisplayName("carriageReturn")
    inner class CarriageReturn {
        @Test
        fun `moves column to zero, preserves row`() {
            val s = state()
            val e = engine(s)
            s.cursor.col = 5
            s.cursor.row = 3
            e.carriageReturn()
            assertAll({ assertEquals(0, s.cursor.col) }, { assertEquals(3, s.cursor.row) })
        }

        @Test
        fun `clears pendingWrap`() {
            val s = state()
            val e = engine(s)
            s.cursor.pendingWrap = true
            e.carriageReturn()
            assertFalse(s.cursor.pendingWrap)
        }

        @Test
        fun `is idempotent at column zero`() {
            val s = state()
            val e = engine(s)
            s.cursor.col = 0
            s.cursor.row = 2
            e.carriageReturn()
            e.carriageReturn()
            assertAll({ assertEquals(0, s.cursor.col) }, { assertEquals(2, s.cursor.row) })
        }

        @Test
        fun `does not mutate the grid`() {
            val s = state()
            val e = engine(s)
            seed(s, 0, "AB")
            seed(s, 1, "CD")
            val before = snapshot(s)
            s.cursor.col = 4
            e.carriageReturn()
            assertGridUnchanged(before, s)
        }
    }

    // ── setCursorAbsolute ─────────────────────────────────────────────────

    @Nested
    @DisplayName("setCursorAbsolute")
    inner class SetCursorAbsolute {
        @Test
        fun `accepts in-bounds coordinates without adjustment`() {
            val s = state()
            val e = engine(s)
            e.setCursorAbsolute(3, 2)
            assertAll({ assertEquals(3, s.cursor.col) }, { assertEquals(2, s.cursor.row) })
        }

        @Test
        fun `clamps negative values to top-left`() {
            val s = state()
            val e = engine(s)
            e.setCursorAbsolute(-99, -42)
            assertAll({ assertEquals(0, s.cursor.col) }, { assertEquals(0, s.cursor.row) })
        }

        @Test
        fun `clamps values past bounds to bottom-right`() {
            val s = state(width = 5, height = 4)
            val e = engine(s)
            e.setCursorAbsolute(999, 999)
            assertAll({ assertEquals(4, s.cursor.col) }, { assertEquals(3, s.cursor.row) })
        }

        @Test
        fun `clears pendingWrap`() {
            val s = state()
            val e = engine(s)
            s.cursor.pendingWrap = true
            e.setCursorAbsolute(0, 0)
            assertFalse(s.cursor.pendingWrap)
        }

        @Test
        fun `ignores origin mode - always absolute`() {
            val s = state()
            val e = engine(s)
            setScrollRegion(s, top = 2, bottom = 3)
            s.modes.isOriginMode = true
            e.setCursorAbsolute(0, 0)
            // Must land at absolute (0,0), not scrollTop
            assertAll({ assertEquals(0, s.cursor.col) }, { assertEquals(0, s.cursor.row) })
        }

        @Test
        fun `does not mutate the grid`() {
            val s = state()
            val e = engine(s)
            seed(s, 0, "XY")
            val before = snapshot(s)
            e.setCursorAbsolute(3, 2)
            assertGridUnchanged(before, s)
        }
    }

    // ── setCursor ──────────────────────────────────────────────────

    @Nested
    @DisplayName("setCursor")
    inner class SetCursor {
        @Test
        fun `absolute positioning when DECOM is off`() {
            val s = state()
            val e = engine(s)
            s.modes.isOriginMode = false
            e.setCursor(2, 3)
            assertAll({ assertEquals(2, s.cursor.col) }, { assertEquals(3, s.cursor.row) })
        }

        @Test
        fun `row 0 maps to scrollTop when DECOM is on`() {
            val s = state(width = 6, height = 6)
            val e = engine(s)
            setScrollRegion(s, top = 2, bottom = 5)
            s.modes.isOriginMode = true
            e.setCursor(0, 0)
            assertAll({ assertEquals(0, s.cursor.col) }, { assertEquals(2, s.cursor.row) })
        }

        @Test
        fun `row translates relative to scrollTop when DECOM is on`() {
            val s = state(width = 6, height = 6)
            val e = engine(s)
            setScrollRegion(s, top = 2, bottom = 5)
            s.modes.isOriginMode = true
            e.setCursor(1, 2) // col=1, row=2 relative → physical row = 2+2 = 4
            assertAll({ assertEquals(1, s.cursor.col) }, { assertEquals(4, s.cursor.row) })
        }

        @Test
        fun `cannot escape scrollBottom when DECOM is on`() {
            val s = state(width = 6, height = 6)
            val e = engine(s)
            setScrollRegion(s, top = 1, bottom = 3)
            s.modes.isOriginMode = true
            e.setCursor(0, 99) // would map to row 100; must clamp to scrollBottom
            assertEquals(3, s.cursor.row)
        }

        @Test
        fun `cannot escape scrollTop when DECOM is on`() {
            val s = state(width = 6, height = 6)
            val e = engine(s)
            setScrollRegion(s, top = 2, bottom = 5)
            s.modes.isOriginMode = true
            e.setCursor(0, -5) // scrollTop + (-5) = -3; must clamp to scrollTop
            assertEquals(2, s.cursor.row)
        }

        @Test
        fun `column is always absolute regardless of DECOM`() {
            val s = state(width = 6, height = 6)
            val e = engine(s)
            setScrollRegion(s, top = 2, bottom = 5)
            s.modes.isOriginMode = true
            e.setCursor(4, 0)
            assertEquals(4, s.cursor.col)
        }

        @Test
        fun `clamps column past width`() {
            val s = state(width = 4, height = 4)
            val e = engine(s)
            e.setCursor(99, 0)
            assertEquals(3, s.cursor.col)
        }

        @Test
        fun `clears pendingWrap in both DECOM states`() {
            val s = state()
            val e = engine(s)
            s.cursor.pendingWrap = true
            s.modes.isOriginMode = false
            e.setCursor(1, 1)
            assertFalse(s.cursor.pendingWrap, "pendingWrap must clear (DECOM off)")

            s.cursor.pendingWrap = true
            s.modes.isOriginMode = true
            e.setCursor(1, 0)
            assertFalse(s.cursor.pendingWrap, "pendingWrap must clear (DECOM on)")
        }
    }

    // ── cursorUp ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("cursorUp")
    inner class CursorUp {
        @Test
        fun `moves up by n rows`() {
            val s = state()
            val e = engine(s)
            s.cursor.row = 3
            e.cursorUp(2)
            assertEquals(1, s.cursor.row)
        }

        @Test
        fun `clamps to top of viewport when cursor is outside scroll region`() {
            val s = state(height = 6)
            val e = engine(s)
            setScrollRegion(s, top = 2, bottom = 4)
            s.cursor.row = 0 // already above scroll region
            e.cursorUp(5)
            assertEquals(0, s.cursor.row)
        }

        @Test
        fun `stops at scrollTop when cursor is inside scroll region`() {
            val s = state(height = 6)
            val e = engine(s)
            setScrollRegion(s, top = 2, bottom = 5)
            s.cursor.row = 3
            e.cursorUp(99)
            assertEquals(2, s.cursor.row)
        }

        @Test
        fun `cursor below scroll region clamps to row 0 (not scrollTop)`() {
            val s = state(height = 6)
            val e = engine(s)
            setScrollRegion(s, top = 1, bottom = 3)
            s.cursor.row = 5 // below scroll region
            e.cursorUp(99)
            assertEquals(0, s.cursor.row)
        }

        @Test
        fun `non-positive n is a no-op`() {
            val s = state()
            val e = engine(s)
            s.cursor.row = 2
            e.cursorUp(0)
            assertEquals(2, s.cursor.row, "n=0 must be no-op")
            e.cursorUp(-3)
            assertEquals(2, s.cursor.row, "n<0 must be no-op")
        }

        @Test
        fun `clears pendingWrap`() {
            val s = state()
            val e = engine(s)
            s.cursor.row = 3
            s.cursor.pendingWrap = true
            e.cursorUp(1)
            assertFalse(s.cursor.pendingWrap)
        }

        @Test
        fun `does not mutate the grid`() {
            val s = state()
            val e = engine(s)
            seed(s, 0, "AB")
            val before = snapshot(s)
            s.cursor.row = 2
            e.cursorUp(1)
            assertGridUnchanged(before, s)
        }
    }

    // ── cursorDown ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("cursorDown")
    inner class CursorDown {
        @Test
        fun `moves down by n rows`() {
            val s = state()
            val e = engine(s)
            s.cursor.row = 0
            e.cursorDown(2)
            assertEquals(2, s.cursor.row)
        }

        @Test
        fun `clamps to bottom of viewport when cursor is outside scroll region`() {
            val s = state(height = 6)
            val e = engine(s)
            setScrollRegion(s, top = 1, bottom = 3)
            s.cursor.row = 5 // below region
            e.cursorDown(99)
            assertEquals(5, s.cursor.row)
        }

        @Test
        fun `stops at scrollBottom when cursor is inside scroll region`() {
            val s = state(height = 6)
            val e = engine(s)
            setScrollRegion(s, top = 1, bottom = 3)
            s.cursor.row = 2
            e.cursorDown(99)
            assertEquals(3, s.cursor.row)
        }

        @Test
        fun `cursor above scroll region clamps to height-1 (not scrollBottom)`() {
            val s = state(height = 6)
            val e = engine(s)
            setScrollRegion(s, top = 2, bottom = 4)
            s.cursor.row = 0 // above scroll region
            e.cursorDown(99)
            assertEquals(5, s.cursor.row)
        }

        @Test
        fun `non-positive n is a no-op`() {
            val s = state()
            val e = engine(s)
            s.cursor.row = 1
            e.cursorDown(0)
            assertEquals(1, s.cursor.row, "n=0 must be no-op")
            e.cursorDown(-2)
            assertEquals(1, s.cursor.row, "n<0 must be no-op")
        }

        @Test
        fun `clears pendingWrap`() {
            val s = state()
            val e = engine(s)
            s.cursor.row = 0
            s.cursor.pendingWrap = true
            e.cursorDown(1)
            assertFalse(s.cursor.pendingWrap)
        }
    }

    // ── cursorLeft ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("cursorLeft")
    inner class CursorLeft {
        @Test
        fun `moves left by n columns`() {
            val s = state()
            val e = engine(s)
            s.cursor.col = 4
            e.cursorLeft(3)
            assertEquals(1, s.cursor.col)
        }

        @Test
        fun `clamps to column 0`() {
            val s = state()
            val e = engine(s)
            s.cursor.col = 2
            e.cursorLeft(99)
            assertEquals(0, s.cursor.col)
        }

        @Test
        fun `non-positive n is a no-op`() {
            val s = state()
            val e = engine(s)
            s.cursor.col = 3
            e.cursorLeft(0)
            assertEquals(3, s.cursor.col)
            e.cursorLeft(-1)
            assertEquals(3, s.cursor.col)
        }

        @Test
        fun `clears pendingWrap`() {
            val s = state()
            val e = engine(s)
            s.cursor.col = 5
            s.cursor.pendingWrap = true
            e.cursorLeft(1)
            assertFalse(s.cursor.pendingWrap)
        }

        @Test
        fun `does not mutate the grid`() {
            val s = state()
            val e = engine(s)
            seed(s, 0, "AB")
            val before = snapshot(s)
            s.cursor.col = 4
            e.cursorLeft(2)
            assertGridUnchanged(before, s)
        }
    }

    // ── cursorRight ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("cursorRight")
    inner class CursorRight {
        @Test
        fun `moves right by n columns`() {
            val s = state()
            val e = engine(s)
            s.cursor.col = 1
            e.cursorRight(3)
            assertEquals(4, s.cursor.col)
        }

        @Test
        fun `clamps to width minus one`() {
            val s = state(width = 5)
            val e = engine(s)
            s.cursor.col = 2
            e.cursorRight(99)
            assertEquals(4, s.cursor.col)
        }

        @Test
        fun `non-positive n is a no-op`() {
            val s = state()
            val e = engine(s)
            s.cursor.col = 2
            e.cursorRight(0)
            assertEquals(2, s.cursor.col)
            e.cursorRight(-1)
            assertEquals(2, s.cursor.col)
        }

        @Test
        fun `clears pendingWrap`() {
            val s = state()
            val e = engine(s)
            s.cursor.col = 2
            s.cursor.pendingWrap = true
            e.cursorRight(1)
            assertFalse(s.cursor.pendingWrap)
        }
    }

    // ── horizontalTab ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("horizontalTab")
    inner class HorizontalTab {
        @Test
        fun `advances to next default tab stop`() {
            val s = state(width = 20)
            val e = engine(s)
            // Default tab stops every 8 columns: 0→8
            s.cursor.col = 0
            e.horizontalTab()
            assertEquals(8, s.cursor.col)
        }

        @Test
        fun `advances through multiple tab stops sequentially`() {
            val s = state(width = 20)
            val e = engine(s)
            s.cursor.col = 0
            e.horizontalTab()
            assertEquals(8, s.cursor.col)
            e.horizontalTab()
            assertEquals(16, s.cursor.col)
        }

        @Test
        fun `clamps to right margin when no further stops exist`() {
            val s = state(width = 10)
            val e = engine(s)
            // Default stops at 8; from col 9 (width-1) there is no further stop
            s.cursor.col = 9
            e.horizontalTab()
            assertEquals(9, s.cursor.col)
        }

        @Test
        fun `clears pendingWrap`() {
            val s = state(width = 20)
            val e = engine(s)
            s.cursor.pendingWrap = true
            e.horizontalTab()
            assertFalse(s.cursor.pendingWrap)
        }

        @Test
        fun `respects custom tab stops`() {
            val s = state(width = 20)
            val e = engine(s)
            s.tabStops.clearAll()
            s.tabStops.setStop(3)
            s.tabStops.setStop(7)
            s.cursor.col = 0
            e.horizontalTab()
            assertEquals(3, s.cursor.col)
            e.horizontalTab()
            assertEquals(7, s.cursor.col)
        }

        @Test
        fun `clamps to active right margin when lr margins are narrower than the next stop`() {
            val s = state(width = 20)
            val e = engine(s)
            s.modes.isLeftRightMarginMode = true
            s.activeBuffer.setLeftRightMargins(left = 3, right = 6, viewportWidth = 20)
            s.cursor.col = 2

            e.horizontalTab()

            assertEquals(5, s.cursor.col)
        }

        @Test
        fun `never wraps when already at the active right boundary`() {
            val s = state(width = 20, height = 3)
            val e = engine(s)
            s.modes.isLeftRightMarginMode = true
            s.activeBuffer.setLeftRightMargins(left = 3, right = 6, viewportWidth = 20)
            s.cursor.col = 5
            s.cursor.row = 2
            s.cursor.pendingWrap = true

            e.horizontalTab()

            assertAll(
                { assertEquals(5, s.cursor.col) },
                { assertEquals(2, s.cursor.row) },
                { assertFalse(s.cursor.pendingWrap) },
            )
        }

        @Test
        fun `does not mutate the grid`() {
            val s = state(width = 20)
            val e = engine(s)
            seed(s, 0, "AB")
            val before = snapshot(s)
            e.horizontalTab()
            assertGridUnchanged(before, s)
        }
    }

    @Nested
    @DisplayName("cursorForwardTab")
    inner class CursorForwardTab {
        @Test
        fun `defaults to one stop when count is zero`() {
            val s = state(width = 20)
            val e = engine(s)
            s.cursor.col = 0

            e.cursorForwardTab(0)

            assertEquals(8, s.cursor.col)
        }

        @Test
        fun `moves across multiple tab stops`() {
            val s = state(width = 20)
            val e = engine(s)
            s.cursor.col = 0

            e.cursorForwardTab(2)

            assertEquals(16, s.cursor.col)
        }

        @Test
        fun `clamps at the active right boundary`() {
            val s = state(width = 20)
            val e = engine(s)
            s.cursor.col = 9

            e.cursorForwardTab(2)

            assertEquals(19, s.cursor.col)
        }

        @Test
        fun `respects declrmm right margin`() {
            val s = state(width = 20)
            val e = engine(s)
            s.modes.isLeftRightMarginMode = true
            s.activeBuffer.setLeftRightMargins(left = 3, right = 6, viewportWidth = 20)
            s.cursor.col = 0

            e.cursorForwardTab(2)

            assertEquals(5, s.cursor.col)
        }

        @Test
        fun `cancels pendingWrap`() {
            val s = state(width = 20)
            val e = engine(s)
            s.cursor.col = 0
            s.cursor.pendingWrap = true

            e.cursorForwardTab(2)

            assertFalse(s.cursor.pendingWrap)
        }
    }

    @Nested
    @DisplayName("cursorBackwardTab")
    inner class CursorBackwardTab {
        @Test
        fun `defaults to one stop when count is zero`() {
            val s = state(width = 20)
            val e = engine(s)
            s.cursor.col = 16

            e.cursorBackwardTab(0)

            assertEquals(8, s.cursor.col)
        }

        @Test
        fun `moves across multiple tab stops`() {
            val s = state(width = 20)
            val e = engine(s)
            s.cursor.col = 19

            e.cursorBackwardTab(2)

            assertEquals(8, s.cursor.col)
        }

        @Test
        fun `respects declrmm left margin`() {
            val s = state(width = 20)
            val e = engine(s)
            s.modes.isLeftRightMarginMode = true
            s.activeBuffer.setLeftRightMargins(left = 4, right = 14, viewportWidth = 20)
            s.cursor.col = 10

            e.cursorBackwardTab(2)

            assertEquals(3, s.cursor.col)
        }

        @Test
        fun `cancels pendingWrap`() {
            val s = state(width = 20)
            val e = engine(s)
            s.cursor.col = 16
            s.cursor.pendingWrap = true

            e.cursorBackwardTab(1)

            assertFalse(s.cursor.pendingWrap)
        }
    }

    // ── saveCursor ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("saveCursor")
    inner class SaveCursor {
        @Test
        fun `captures position, pen, pendingWrap, and origin mode`() {
            val s = state()
            val e = engine(s)
            s.cursor.col = 2
            s.cursor.row = 1
            s.cursor.pendingWrap = true
            s.modes.isOriginMode = true
            s.pen.setAttributes(fg = 3, bg = 4, bold = true)

            e.saveCursor()

            assertAll(
                { assertTrue(s.savedCursor.isSaved) },
                { assertEquals(2, s.savedCursor.col) },
                { assertEquals(1, s.savedCursor.row) },
                { assertEquals(s.pen.currentAttr, s.savedCursor.attr) },
                { assertTrue(s.savedCursor.pendingWrap) },
                { assertTrue(s.savedCursor.isOriginMode) },
            )
        }

        @Test
        fun `captures pendingWrap=false correctly`() {
            val s = state()
            val e = engine(s)
            s.cursor.pendingWrap = false
            e.saveCursor()
            assertFalse(s.savedCursor.pendingWrap)
        }

        @Test
        fun `captures isOriginMode=false correctly`() {
            val s = state()
            val e = engine(s)
            s.modes.isOriginMode = false
            e.saveCursor()
            assertFalse(s.savedCursor.isOriginMode)
        }

        @Test
        fun `overwrites the previous save`() {
            val s = state()
            val e = engine(s)
            s.cursor.col = 1
            s.cursor.row = 2
            s.cursor.pendingWrap = true
            s.modes.isOriginMode = true
            e.saveCursor()

            s.cursor.col = 4
            s.cursor.row = 3
            s.cursor.pendingWrap = false
            s.modes.isOriginMode = false
            e.saveCursor()

            assertAll(
                { assertEquals(4, s.savedCursor.col) },
                { assertEquals(3, s.savedCursor.row) },
                { assertFalse(s.savedCursor.pendingWrap) },
                { assertFalse(s.savedCursor.isOriginMode) },
            )
        }

        @Test
        fun `save is a snapshot - mutations after save do not affect saved state`() {
            val s = state()
            val e = engine(s)
            s.cursor.col = 2
            s.cursor.row = 1
            e.saveCursor()

            // Mutate cursor after save
            s.cursor.col = 5
            s.cursor.row = 3
            s.cursor.pendingWrap = true

            assertAll(
                { assertEquals(2, s.savedCursor.col) },
                { assertEquals(1, s.savedCursor.row) },
                { assertFalse(s.savedCursor.pendingWrap) },
            )
        }

        @Test
        fun `does not mutate the grid`() {
            val s = state()
            val e = engine(s)
            seed(s, 0, "AB")
            val before = snapshot(s)
            e.saveCursor()
            assertGridUnchanged(before, s)
        }
    }

    // ── restoreCursor ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("restoreCursor")
    inner class RestoreCursor {
        @Test
        fun `restores position, pen, pendingWrap, and origin mode`() {
            val s = state()
            val e = engine(s)

            // pendingWrap=true is only valid when cursor is at the right margin (col = width - 1).
            s.cursor.col = s.dimensions.width - 1
            s.cursor.row = 1
            s.cursor.pendingWrap = true
            s.modes.isOriginMode = true
            s.pen.setAttributes(fg = 2, bg = 3, bold = true)
            e.saveCursor()

            // Dirty everything
            s.cursor.col = 0
            s.cursor.row = 0
            s.cursor.pendingWrap = false
            s.modes.isOriginMode = false
            s.pen.reset()

            e.restoreCursor()

            assertAll(
                { assertEquals(s.dimensions.width - 1, s.cursor.col) },
                { assertEquals(1, s.cursor.row) },
                { assertTrue(s.cursor.pendingWrap) },
                { assertTrue(s.modes.isOriginMode) },
                { assertEquals(s.savedCursor.attr, s.pen.currentAttr) },
            )
        }

        @Test
        fun `without saved state - homes cursor, resets pen, clears origin mode`() {
            val s = state()
            val e = engine(s)
            val defaultAttr = s.pen.currentAttr
            s.cursor.col = 4
            s.cursor.row = 3
            s.cursor.pendingWrap = true
            s.modes.isOriginMode = true
            s.pen.setAttributes(fg = 7, bg = 8, bold = true)

            e.restoreCursor()

            assertAll(
                { assertEquals(0, s.cursor.col) },
                { assertEquals(0, s.cursor.row) },
                { assertFalse(s.cursor.pendingWrap) },
                { assertFalse(s.modes.isOriginMode) },
                { assertEquals(defaultAttr, s.pen.currentAttr) },
            )
        }

        @Test
        fun `clamps restored column after terminal narrowed`() {
            val s = state(width = 10, height = 5)
            val e = engine(s)
            s.cursor.col = 9
            s.cursor.row = 4
            e.saveCursor()

            s.dimensions.width = 5
            s.activeBuffer.resetScrollRegion(s.dimensions.height)
            e.restoreCursor()

            assertEquals(4, s.cursor.col)
        }

        @Test
        fun `clamps restored row after terminal shrunk vertically`() {
            val s = state(width = 6, height = 6)
            val e = engine(s)
            s.cursor.col = 2
            s.cursor.row = 5
            e.saveCursor()

            s.dimensions.height = 3
            s.activeBuffer.resetScrollRegion(s.dimensions.height)
            e.restoreCursor()

            assertEquals(2, s.cursor.row)
        }

        @Test
        fun `pendingWrap survives restore when col still at right margin`() {
            val s = state(width = 6, height = 4)
            val e = engine(s)
            s.cursor.col = 5
            s.cursor.row = 0
            s.cursor.pendingWrap = true
            e.saveCursor()

            s.cursor.pendingWrap = false
            e.restoreCursor()

            assertTrue(s.cursor.pendingWrap)
        }

        @Test
        fun `pendingWrap cleared when restored col no longer at right margin after widening`() {
            val s = state(width = 4, height = 4)
            val e = engine(s)
            // Save at right margin col=3 of a width=4 terminal
            s.cursor.col = 3
            s.cursor.pendingWrap = true
            e.saveCursor()

            // Terminal widened: col 3 is no longer the right margin
            s.dimensions.width = 8
            e.restoreCursor()

            assertFalse(s.cursor.pendingWrap, "pendingWrap must clear after widening")
        }

        @Test
        fun `restored col at new right margin after narrowing keeps pendingWrap`() {
            val s = state(width = 6, height = 4)
            val e = engine(s)
            s.cursor.col = 5
            s.cursor.pendingWrap = true
            e.saveCursor()

            // Narrow so width-1 == 5 still holds but terminal is smaller
            s.dimensions.width = 6 // same — pendingWrap must survive
            e.restoreCursor()

            assertTrue(s.cursor.pendingWrap)
        }

        @Test
        fun `restores absolute row regardless of current scroll margins`() {
            val s = state(width = 6, height = 6)
            val e = engine(s)
            s.cursor.col = 0
            s.cursor.row = 4 // absolute row 4
            s.modes.isOriginMode = false
            e.saveCursor()

            // Change margins before restoring
            setScrollRegion(s, top = 2, bottom = 3)
            s.modes.isOriginMode = true
            e.restoreCursor()

            // Row must be the saved absolute row 4, not clamped into [2..3]
            assertEquals(
                4,
                s.cursor.row,
                "DECRC must restore absolute saved row regardless of current margins",
            )
        }

        @Test
        fun `multiple restores without intervening save always produce same result`() {
            val s = state()
            val e = engine(s)
            s.cursor.col = 3
            s.cursor.row = 2
            s.pen.setAttributes(fg = 1, bg = 2)
            e.saveCursor()

            e.restoreCursor()
            s.cursor.col = 5
            s.cursor.row = 3 // dirty cursor between restores
            e.restoreCursor()

            assertAll(
                { assertEquals(3, s.cursor.col) },
                { assertEquals(2, s.cursor.row) },
                { assertTrue(s.savedCursor.isSaved) },
            )
        }

        @Test
        fun `does not mutate the grid`() {
            val s = state()
            val e = engine(s)
            seed(s, 0, "AB")
            seed(s, 1, "CD")
            val before = snapshot(s)
            e.restoreCursor()
            assertGridUnchanged(before, s)
        }
    }

    // ── pendingWrap — cross-method invariant ──────────────────────────────

    @Nested
    @DisplayName("pendingWrap invariant")
    inner class PendingWrapInvariant {
        @Test
        fun `carriageReturn cancels wrap`() {
            val s = state()
            val e = engine(s)
            s.cursor.col = 5
            s.cursor.pendingWrap = true
            e.carriageReturn()
            assertFalse(s.cursor.pendingWrap)
        }

        @Test
        fun `cursorUp cancels wrap`() {
            val s = state()
            val e = engine(s)
            s.cursor.row = 2
            s.cursor.pendingWrap = true
            e.cursorUp(1)
            assertFalse(s.cursor.pendingWrap)
        }

        @Test
        fun `cursorDown cancels wrap`() {
            val s = state()
            val e = engine(s)
            s.cursor.row = 0
            s.cursor.pendingWrap = true
            e.cursorDown(1)
            assertFalse(s.cursor.pendingWrap)
        }

        @Test
        fun `cursorLeft cancels wrap`() {
            val s = state()
            val e = engine(s)
            s.cursor.col = 3
            s.cursor.pendingWrap = true
            e.cursorLeft(1)
            assertFalse(s.cursor.pendingWrap)
        }

        @Test
        fun `cursorRight cancels wrap`() {
            val s = state()
            val e = engine(s)
            s.cursor.col = 2
            s.cursor.pendingWrap = true
            e.cursorRight(1)
            assertFalse(s.cursor.pendingWrap)
        }

        @Test
        fun `horizontalTab cancels wrap`() {
            val s = state(width = 20)
            val e = engine(s)
            s.cursor.pendingWrap = true
            e.horizontalTab()
            assertFalse(s.cursor.pendingWrap)
        }

        @Test
        fun `cursorForwardTab cancels wrap`() {
            val s = state(width = 20)
            val e = engine(s)
            s.cursor.pendingWrap = true
            e.cursorForwardTab(1)
            assertFalse(s.cursor.pendingWrap)
        }

        @Test
        fun `cursorBackwardTab cancels wrap`() {
            val s = state(width = 20)
            val e = engine(s)
            s.cursor.pendingWrap = true
            e.cursorBackwardTab(1)
            assertFalse(s.cursor.pendingWrap)
        }

        @Test
        fun `no-op cursorUp does not clear pendingWrap`() {
            // n=0 returns before cancelPendingWrap; flag must be untouched
            val s = state()
            val e = engine(s)
            s.cursor.row = 2
            s.cursor.pendingWrap = true
            e.cursorUp(0)
            assertTrue(
                s.cursor.pendingWrap,
                "n=0 is a no-op and must not side-effect pendingWrap",
            )
        }

        @Test
        fun `no-op cursorDown does not clear pendingWrap`() {
            val s = state()
            val e = engine(s)
            s.cursor.row = 0
            s.cursor.pendingWrap = true
            e.cursorDown(0)
            assertTrue(s.cursor.pendingWrap)
        }

        @Test
        fun `no-op cursorLeft does not clear pendingWrap`() {
            val s = state()
            val e = engine(s)
            s.cursor.col = 2
            s.cursor.pendingWrap = true
            e.cursorLeft(0)
            assertTrue(s.cursor.pendingWrap)
        }

        @Test
        fun `no-op cursorRight does not clear pendingWrap`() {
            val s = state()
            val e = engine(s)
            s.cursor.col = 2
            s.cursor.pendingWrap = true
            e.cursorRight(0)
            assertTrue(s.cursor.pendingWrap)
        }
    }

    // ── DECOM interaction with save/restore ───────────────────────────────

    @Nested
    @DisplayName("DECOM interaction")
    inner class DecomInteraction {
        @Test
        fun `DECSC captures origin-mode-off and DECRC restores it`() {
            val s = state()
            val e = engine(s)
            s.modes.isOriginMode = false
            e.saveCursor()
            s.modes.isOriginMode = true
            e.restoreCursor()
            assertFalse(s.modes.isOriginMode)
        }

        @Test
        fun `DECSC captures origin-mode-on and DECRC restores it`() {
            val s = state()
            val e = engine(s)
            s.modes.isOriginMode = true
            e.saveCursor()
            s.modes.isOriginMode = false
            e.restoreCursor()
            assertTrue(s.modes.isOriginMode)
        }

        @Test
        fun `CUP with DECOM on positions correctly, then DECSC round-trips absolute row`() {
            val s = state(width = 8, height = 8)
            val e = engine(s)
            setScrollRegion(s, top = 2, bottom = 6)
            s.modes.isOriginMode = true
            e.setCursor(1, 1) // relative row 1 → absolute row 3
            assertEquals(3, s.cursor.row)
            e.saveCursor()

            // Change margins and restore
            setScrollRegion(s, top = 0, bottom = 7)
            e.restoreCursor()

            // Restored row must be the saved absolute row 3, not re-translated
            assertEquals(3, s.cursor.row)
        }
    }
}

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
package io.github.ketraterm.ui.swing.render.painter

import io.github.ketraterm.render.api.TerminalRenderCellFlags
import io.github.ketraterm.render.api.TerminalRenderUnderline
import io.github.ketraterm.ui.swing.api.*
import io.github.ketraterm.ui.swing.render.TestRenderFrame
import io.github.ketraterm.ui.swing.render.renderCache
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerminalTextRunStyleTest {
    @Test
    fun `prepared provider styles follow normal hover active and followed state across table growth`() {
        val cache = renderCache(TestRenderFrame.text("link"))
        val actions = TerminalHyperlinkActions()
        val range = SwingHyperlinkTextRange(SwingHyperlinkTextPosition(0, 0), SwingHyperlinkTextPosition(0, 4))
        val colors = intArrayOf(0xff123456.toInt(), 0xff234567.toInt(), 0xff345678.toInt(), 0xff456789.toInt())

        fun style(index: Int) = SwingHyperlinkStyle(colors[index], colors[index], colors[index], TerminalRenderUnderline.CURLY)
        val link =
            SwingHyperlink(
                range,
                range,
                SwingHyperlinkAction.NONE,
                presentation = SwingHyperlinkPresentation(style(0), style(1), style(2), style(3), true),
            )
        val id = actions.add(link)
        actions.retain(id)
        repeat(100) { actions.retain(actions.add(link)) }
        cache.hyperlinkIds.fill(id)
        val run = TerminalTextRunStyle()

        fun assertStyle(
            index: Int,
            hovered: Boolean,
            active: Boolean,
        ) {
            run.configureRow(true, cache.hyperlinkIds, if (hovered) id else 0, active, 0, actions)
            run.begin(cache, cache.palette, 0, 0)
            assertEquals(colors[index], run.foreground)
            assertEquals(colors[index], run.hyperlinkUnderlineColor)
            assertEquals(TerminalRenderUnderline.CURLY, run.hyperlinkUnderline)
            assertTrue(run.matches(cache, cache.palette, 0, 1))
            val offset = actions.styleOffset(id, hovered, active)
            assertEquals(colors[index], actions.background(offset))
        }
        assertStyle(0, false, false)
        assertStyle(1, true, false)
        assertStyle(2, true, true)
        actions.follow(id)
        assertStyle(3, false, false)
        assertStyle(1, true, false)
        actions.release(id)
        assertEquals(-1, actions.styleOffset(id, false, false))
    }

    @Test
    fun `artificial wrap padding has no hyperlink decoration while authored spaces retain it`() {
        val cache = renderCache(TestRenderFrame.text("  "))
        cache.hyperlinkIds.fill(7)
        cache.flags[1] = cache.flags[1] or TerminalRenderCellFlags.WRAP_PADDING
        val style = TerminalTextRunStyle()
        style.configureRow(true, cache.hyperlinkIds, 7, true, 0xFF4DA3FF.toInt())
        style.begin(cache, cache.palette, 0, 0)
        assertTrue(style.hovered)
        assertEquals(7, style.hyperlinkId)
        assertFalse(style.matches(cache, cache.palette, 0, 1))
        style.begin(cache, cache.palette, 0, 1)
        assertFalse(style.hovered)
        assertEquals(0, style.hyperlinkId)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `semantic hover splits runs at hyperlink segment boundaries`(activationHover: Boolean) {
        val cache = renderCache(TestRenderFrame.text("A".repeat(80)))
        cache.hyperlinkIds.fill(7, 20, 60)
        val style = TerminalTextRunStyle()
        val activationForeground = 0xFF4DA3FF.toInt()
        style.configureRow(
            textBlinkVisible = true,
            hyperlinkIds = cache.hyperlinkIds,
            hoveredHyperlinkId = 7,
            hyperlinkActivationHover = activationHover,
            hyperlinkActivationForeground = activationForeground,
        )
        style.begin(cache, cache.palette, 0, 0)
        val starts = mutableListOf(0)
        for (column in 1 until cache.columns) {
            if (!style.matches(cache, cache.palette, 0, column)) {
                starts.add(column)
                style.begin(cache, cache.palette, 0, column)
            }
        }
        assertEquals(listOf(0, 20, 60), starts)

        style.begin(cache, cache.palette, 0, 20)
        assertTrue(style.hovered)
        assertEquals(if (activationHover) activationForeground else cache.palette.defaultForeground, style.foreground)
        assertFalse(style.matches(cache, cache.palette, 0, 60))
        assertTrue(style.hovered, "Comparing another cell must not replace the retained run style")
        style.begin(cache, cache.palette, 0, 60)
        assertFalse(style.hovered)
        assertEquals(cache.palette.defaultForeground, style.foreground)
    }
}

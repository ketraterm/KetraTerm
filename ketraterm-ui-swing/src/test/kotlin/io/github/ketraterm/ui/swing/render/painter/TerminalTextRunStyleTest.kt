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
import io.github.ketraterm.ui.swing.api.SwingHyperlinkPresentation
import io.github.ketraterm.ui.swing.api.SwingHyperlinkStyle
import io.github.ketraterm.ui.swing.render.TestRenderFrame
import io.github.ketraterm.ui.swing.render.hyperlinkHover
import io.github.ketraterm.ui.swing.render.renderCache
import io.github.ketraterm.ui.swing.render.styleFor
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerminalTextRunStyleTest {
    @Test
    fun `prepared provider styles follow normal hover active and followed state`() {
        val cache = renderCache(TestRenderFrame.text("link"))
        val colors = intArrayOf(0xff123456.toInt(), 0xff234567.toInt(), 0xff345678.toInt(), 0xff456789.toInt())

        fun style(index: Int) = SwingHyperlinkStyle(colors[index], colors[index], colors[index], TerminalRenderUnderline.CURLY)
        val presentation = SwingHyperlinkPresentation(style(0), style(1), style(2), style(3), true)
        val presentations = Array<SwingHyperlinkPresentation?>(cache.columns) { presentation }
        val id = -1
        cache.hyperlinkIds.fill(id)
        val run = TerminalTextRunStyle()
        var followed = 0

        fun assertStyle(
            index: Int,
            hovered: Boolean,
            active: Boolean,
        ) {
            run.configureRow(true, cache.hyperlinkIds, hyperlinkHover(if (hovered) id else 0, active), 0, presentations, followed)
            run.begin(cache, cache.palette, 0, 0)
            assertEquals(colors[index], run.foreground)
            assertEquals(colors[index], run.hyperlinkUnderlineColor)
            assertEquals(TerminalRenderUnderline.CURLY, run.hyperlinkUnderline)
            assertTrue(run.matches(cache, cache.palette, 0, 1))
            assertEquals(colors[index], presentation.styleFor(hovered, active, id == followed)?.backgroundArgb)
        }
        assertStyle(0, false, false)
        assertStyle(1, true, false)
        assertStyle(2, true, true)
        followed = id
        assertStyle(3, false, false)
        assertStyle(1, true, false)
        assertStyle(2, true, true)
    }

    @Test
    fun `missing hover styles preserve followed presentation until another link is followed`() {
        val cache = renderCache(TestRenderFrame.text("link"))
        val normal = 0xff123456.toInt()
        val followed = 0xff654321.toInt()
        val presentation =
            SwingHyperlinkPresentation(
                normal = SwingHyperlinkStyle(normal, normal, normal, TerminalRenderUnderline.SINGLE),
                followed = SwingHyperlinkStyle(followed, followed, followed, TerminalRenderUnderline.CURLY),
                isVisible = true,
            )
        val presentations = Array<SwingHyperlinkPresentation?>(cache.columns) { presentation }
        val id = -1
        cache.hyperlinkIds.fill(id)
        val run = TerminalTextRunStyle()
        var followedId = 0

        fun assertPresentation(
            color: Int,
            underline: Int,
        ) {
            for (hovered in listOf(false, true)) {
                for (active in listOf(false, true)) {
                    run.configureRow(true, cache.hyperlinkIds, hyperlinkHover(if (hovered) id else 0, active), 0, presentations, followedId)
                    run.begin(cache, cache.palette, 0, 0)
                    assertEquals(color, run.foreground)
                    assertEquals(color, run.hyperlinkUnderlineColor)
                    assertEquals(underline, run.hyperlinkUnderline)
                    assertEquals(color, presentation.styleFor(hovered, active, id == followedId)?.backgroundArgb)
                }
            }
        }
        assertPresentation(normal, TerminalRenderUnderline.SINGLE)
        followedId = id
        assertPresentation(followed, TerminalRenderUnderline.CURLY)
        followedId = -2
        assertPresentation(normal, TerminalRenderUnderline.SINGLE)
    }

    @Test
    fun `explicit hover style is also the active fallback after following a link`() {
        val hovered = 0xff112233.toInt()
        val presentation =
            SwingHyperlinkPresentation(
                hovered = SwingHyperlinkStyle(foregroundArgb = hovered),
                followed = SwingHyperlinkStyle(foregroundArgb = 0xff332211.toInt()),
            )
        assertEquals(hovered, presentation.styleFor(hovered = true, active = false, followed = true)?.foregroundArgb)
        assertEquals(hovered, presentation.styleFor(hovered = true, active = true, followed = true)?.foregroundArgb)
    }

    @Test
    fun `artificial wrap padding has no hyperlink decoration while authored spaces retain it`() {
        val cache = renderCache(TestRenderFrame.text("  "))
        cache.hyperlinkIds.fill(7)
        cache.flags[1] = cache.flags[1] or TerminalRenderCellFlags.WRAP_PADDING
        val style = TerminalTextRunStyle()
        style.configureRow(true, cache.hyperlinkIds, hyperlinkHover(7, true), 0xFF4DA3FF.toInt())
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
            hyperlinkHover = hyperlinkHover(7, activationHover),
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

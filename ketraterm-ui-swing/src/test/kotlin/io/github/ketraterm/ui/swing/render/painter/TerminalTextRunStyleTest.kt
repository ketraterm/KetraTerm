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

import io.github.ketraterm.render.api.TerminalColorBehavior
import io.github.ketraterm.render.api.TerminalRenderAttrs
import io.github.ketraterm.render.api.TerminalRenderCellFlags
import io.github.ketraterm.render.api.TerminalRenderUnderline
import io.github.ketraterm.ui.swing.api.CellSelection
import io.github.ketraterm.ui.swing.api.SwingHyperlinkPresentation
import io.github.ketraterm.ui.swing.api.SwingHyperlinkStyle
import io.github.ketraterm.ui.swing.render.*
import io.github.ketraterm.ui.swing.search.TerminalSearchViewportHighlights
import io.github.ketraterm.ui.swing.settings.SwingSearchMatchStyle
import io.github.ketraterm.ui.swing.settings.SwingSelectionStyle
import io.github.ketraterm.ui.swing.settings.SwingSettings
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerminalTextRunStyleTest {
    private val settings = SwingSettings.create { it.minimumContrastRatio = 1.0 }

    @Test
    fun `default contrast adjusts resolved search and selection text while explicit opt out preserves it`() {
        val cache = renderCache(TestRenderFrame.text("A"))
        val color = 0xff222222.toInt()
        val highlights =
            TerminalSearchViewportHighlights().apply {
                reset(1)
                add(0, 0, 1, false)
                finish()
            }
        val defaults =
            SwingSettings.create {
                it.searchMatchStyle = SwingSearchMatchStyle(TEST_BLACK, foreground = color)
                it.selectionStyle = SwingSelectionStyle(TEST_BLACK, foreground = color)
            }
        val run = TerminalTextRunStyle()
        for (selection in longArrayOf(CellSelection.NO_RANGE, CellSelection.packRange(0, 1))) {
            run.configureRow(
                true,
                cache.hyperlinkIds,
                null,
                defaults,
                selectionRange = selection,
                searchHighlights = highlights,
                columns = 1,
            )
            run.begin(cache, cache.palette, 0, 0)
            assertTrue(SwingColors.contrastRatio(run.foreground, TEST_BLACK) >= 3.0)

            run.configureRow(
                true,
                cache.hyperlinkIds,
                null,
                defaults.copy {
                    it.minimumContrastRatio = 1.0
                },
                selectionRange = selection,
                searchHighlights = highlights,
                columns = 1,
            )
            run.begin(cache, cache.palette, 0, 0)
            assertEquals(color, run.foreground)
        }
    }

    @Test
    fun `search text splits runs at match boundaries and active null preserves underlying text`() {
        val cache = renderCache(TestRenderFrame.text("AAAA"))
        val highlights =
            TerminalSearchViewportHighlights().apply {
                reset(1)
                add(0, 1, 2, false)
                add(0, 2, 3, true)
                finish()
            }
        val configured =
            settings.copy {
                it.searchMatchStyle = SwingSearchMatchStyle(0, foreground = TEST_RED)
                it.searchActiveMatchStyle = SwingSearchMatchStyle(0)
            }
        val run = TerminalTextRunStyle()
        run.configureRow(true, cache.hyperlinkIds, null, configured, searchHighlights = highlights, columns = 4)
        run.begin(cache, cache.palette, 0, 0)
        assertEquals(cache.palette.defaultForeground, run.foreground)
        assertFalse(run.matches(cache, cache.palette, 0, 1))
        run.begin(cache, cache.palette, 0, 1)
        assertEquals(TEST_RED, run.foreground)
        assertFalse(run.matches(cache, cache.palette, 0, 2))
        run.begin(cache, cache.palette, 0, 2)
        assertEquals(cache.palette.defaultForeground, run.foreground)
        assertTrue(run.matches(cache, cache.palette, 0, 3))

        val reloaded = configured.copy { it.searchActiveMatchStyle = SwingSearchMatchStyle(0, foreground = TEST_GREEN) }
        run.configureRow(true, cache.hyperlinkIds, null, reloaded, searchHighlights = highlights, columns = 4)
        run.begin(cache, cache.palette, 0, 2)
        assertEquals(TEST_GREEN, run.foreground)
        highlights.reset(1)
        run.configureRow(true, cache.hyperlinkIds, null, reloaded, searchHighlights = highlights, columns = 4)
        run.begin(cache, cache.palette, 0, 2)
        assertEquals(cache.palette.defaultForeground, run.foreground)
        run.configureRow(true, cache.hyperlinkIds, null, settings, searchHighlights = highlights, columns = 4)
        run.begin(cache, cache.palette, 0, 1)
        assertEquals(cache.palette.defaultForeground, run.foreground)
    }

    @Test
    fun `search foreground replaces link text while selection and application roles retain priority`() {
        val cache = renderCache(TestRenderFrame.text("A"))
        cache.hyperlinkIds.fill(1)
        val highlights =
            TerminalSearchViewportHighlights().apply {
                reset(1)
                add(0, 0, 1, false)
                finish()
            }
        val configured =
            settings.copy {
                it.osc8HyperlinkPresentation =
                    SwingHyperlinkPresentation(normal = SwingHyperlinkStyle(foregroundArgb = TEST_GREEN), isVisible = true)
                it.searchMatchStyle = SwingSearchMatchStyle(TEST_BLACK, foreground = TEST_RED)
                it.selectionStyle = SwingSelectionStyle(TEST_BLACK, foreground = TEST_BLUE)
            }
        val run = TerminalTextRunStyle()
        run.configureRow(true, cache.hyperlinkIds, null, configured, searchHighlights = highlights, columns = 1)
        run.begin(cache, cache.palette, 0, 0)
        assertEquals(TEST_RED, run.foreground)
        run.configureRow(
            true,
            cache.hyperlinkIds,
            null,
            configured,
            searchHighlights = highlights,
            columns = 1,
            selectionRange = CellSelection.packRange(0, 1),
        )
        run.begin(cache, cache.palette, 0, 0)
        assertEquals(TEST_BLUE, run.foreground)
        val applicationPalette =
            cache.palette.copyWithColorBehavior(
                TerminalColorBehavior.OVERRIDE_SELECTION_FOREGROUND,
                selectionForeground = TEST_WHITE,
            )
        run.begin(cache, applicationPalette, 0, 0)
        assertEquals(TEST_WHITE, run.foreground)
        run.configureRow(
            true,
            cache.hyperlinkIds,
            null,
            configured.copy { it.selectionStyle = SwingSelectionStyle(TEST_BLACK) },
            searchHighlights = highlights,
            columns = 1,
            selectionRange = CellSelection.packRange(0, 1),
        )
        run.begin(cache, cache.palette, 0, 0)
        assertEquals(TEST_RED, run.foreground, "Inherited selection text keeps the underlying search color")
    }

    @Test
    fun `search foreground receives opt in contrast without making concealed or blink hidden text visible`() {
        val cache = renderCache(TestRenderFrame.text("A"))
        val highlights =
            TerminalSearchViewportHighlights().apply {
                reset(1)
                add(0, 0, 1, true)
                finish()
            }
        val configured =
            settings.copy {
                it.searchActiveMatchStyle = SwingSearchMatchStyle(TEST_WHITE, foreground = TEST_WHITE)
                it.minimumContrastRatio = 4.5
            }
        val run = TerminalTextRunStyle()
        run.configureRow(true, cache.hyperlinkIds, null, configured, searchHighlights = highlights, columns = 1)
        run.begin(cache, cache.palette, 0, 0)
        assertTrue(SwingColors.contrastRatio(run.foreground, TEST_WHITE) >= 4.5)
        cache.attrWords[0] = TerminalRenderAttrs.pack(invisible = true)
        run.begin(cache, cache.palette, 0, 0)
        assertTrue(run.textHidden)
        cache.attrWords[0] = TerminalRenderAttrs.pack(blink = true)
        run.configureRow(false, cache.hyperlinkIds, null, configured, searchHighlights = highlights, columns = 1)
        run.begin(cache, cache.palette, 0, 0)
        assertTrue(run.textHidden)
    }

    @Test
    fun `contrast uses search and selection layers and changes cleanly on settings reload`() {
        val cache = renderCache(TestRenderFrame.text("AA"))
        val palette = cache.palette.copy(defaultForeground = 0xff888888.toInt(), defaultBackground = 0xff000000.toInt())
        val highlights =
            TerminalSearchViewportHighlights().apply {
                reset(1)
                add(0, 0, 1, active = false)
                finish()
            }
        val configured =
            settings.copy {
                it.minimumContrastRatio = 4.5
                it.searchMatchBackground = 0xffffffff.toInt()
                it.selectionBackground = 0x80000000.toInt()
            }
        val style = TerminalTextRunStyle()
        style.configureRow(
            true,
            cache.hyperlinkIds,
            null,
            configured,
            selectionRange = CellSelection.packRange(0, 1),
            searchHighlights = highlights,
            columns = 2,
        )
        style.begin(cache, palette, 0, 0)
        val background = SwingColors.blendOver(0x80000000.toInt(), 0xffffffff.toInt())
        assertTrue(SwingColors.contrastRatio(style.foreground, background) >= 4.5)
        assertFalse(style.matches(cache, palette, 0, 1), "Different visible backgrounds can split a text run")

        style.configureRow(
            true,
            cache.hyperlinkIds,
            null,
            configured.copy { it.minimumContrastRatio = 1.0 },
            selectionRange = CellSelection.packRange(0, 1),
            searchHighlights = highlights,
            columns = 2,
        )
        style.begin(cache, palette, 0, 0)
        assertEquals(palette.defaultForeground, style.foreground)
        assertTrue(style.matches(cache, palette, 0, 1))
    }

    @Test
    fun `contrast uses hyperlink fill and cannot reveal concealed text`() {
        val cache = renderCache(TestRenderFrame.text("A"))
        cache.hyperlinkIds.fill(1)
        val configured =
            settings.copy {
                it.minimumContrastRatio = 7.0
                it.osc8HyperlinkPresentation =
                    SwingHyperlinkPresentation(
                        normal = SwingHyperlinkStyle(foregroundArgb = 0xffeeeeee.toInt(), backgroundArgb = 0xffffffff.toInt()),
                        isVisible = true,
                    )
            }
        val style = TerminalTextRunStyle()
        style.configureRow(true, cache.hyperlinkIds, null, configured)
        style.begin(cache, cache.palette, 0, 0)
        assertTrue(SwingColors.contrastRatio(style.foreground, 0xffffffff.toInt()) >= 7.0)
        cache.attrWords[0] = TerminalRenderAttrs.pack(invisible = true)
        style.begin(cache, cache.palette, 0, 0)
        assertTrue(style.textHidden)
    }

    @Test
    fun `selection foreground override is independent of fill and does not leak between rows`() {
        val cache = renderCache(TestRenderFrame.text("AAA"))
        val palette =
            cache.palette.copyWithColorBehavior(
                TerminalColorBehavior.OVERRIDE_SELECTION_FOREGROUND,
                selectionForeground = 0xff123456.toInt(),
            )
        val style = TerminalTextRunStyle()
        style.configureRow(true, cache.hyperlinkIds, null, settings, selectionRange = CellSelection.packRange(1, 2))
        style.begin(cache, palette, 0, 0)
        assertEquals(palette.defaultForeground, style.foreground)
        assertFalse(style.matches(cache, palette, 0, 1))
        style.begin(cache, palette, 0, 1)
        assertEquals(palette.selectionForeground, style.foreground)
        assertFalse(style.matches(cache, palette, 0, 2))

        style.configureRow(true, cache.hyperlinkIds, null, settings)
        style.begin(cache, palette, 0, 1)
        assertEquals(palette.defaultForeground, style.foreground)
        assertTrue(style.matches(cache, palette, 0, 2))
    }

    @Test
    fun `explicit selection foreground cannot reveal concealed text`() {
        val cache = renderCache(TestRenderFrame.text("A", attrs = longArrayOf(TerminalRenderAttrs.pack(invisible = true))))
        val palette =
            cache.palette.copyWithColorBehavior(
                TerminalColorBehavior.OVERRIDE_SELECTION_FOREGROUND,
                selectionForeground = 0xff123456.toInt(),
            )
        val style = TerminalTextRunStyle()
        style.configureRow(true, cache.hyperlinkIds, null, settings, selectionRange = CellSelection.packRange(0, 1))
        style.begin(cache, palette, 0, 0)

        assertTrue(style.textHidden)
    }

    @ParameterizedTest
    @ValueSource(ints = [-1, 7])
    fun `prepared styles follow normal hover active and followed state for both link sources`(id: Int) {
        val cache = renderCache(TestRenderFrame.text("link"))
        val colors = intArrayOf(0xff123456.toInt(), 0xff234567.toInt(), 0xff345678.toInt(), 0xff456789.toInt())

        fun style(index: Int) = SwingHyperlinkStyle(colors[index], colors[index], colors[index], TerminalRenderUnderline.CURLY)
        val presentation = SwingHyperlinkPresentation(style(0), style(1), style(2), style(3), true)
        val presentations = Array<SwingHyperlinkPresentation?>(cache.columns) { presentation }
        val settings =
            settings.copy { draft ->
                draft.osc8HyperlinkPresentation = presentation
            }
        cache.hyperlinkIds.fill(id)
        val run = TerminalTextRunStyle()
        var followed = 0

        fun assertStyle(
            index: Int,
            hovered: Boolean,
            active: Boolean,
        ) {
            run.configureRow(true, cache.hyperlinkIds, hyperlinkHover(if (hovered) id else 0, active), settings, presentations, followed)
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
    fun `explicit OSC8 presentation can inherit terminal styling until activation`() {
        val cache = renderCache(TestRenderFrame.text("link"))
        cache.hyperlinkIds.fill(7)
        val active = 0xff336699.toInt()
        val settings =
            settings.copy { draft ->
                draft.osc8HyperlinkPresentation = SwingHyperlinkPresentation(active = SwingHyperlinkStyle(foregroundArgb = active))
            }
        val run = TerminalTextRunStyle()
        for (hovered in listOf(false, true)) {
            run.configureRow(true, cache.hyperlinkIds, hyperlinkHover(if (hovered) 7 else 0), settings)
            run.begin(cache, cache.palette, 0, 0)
            assertEquals(cache.palette.defaultForeground, run.foreground)
            assertEquals(TerminalRenderUnderline.NONE, run.hyperlinkUnderline)
        }
        run.configureRow(true, cache.hyperlinkIds, hyperlinkHover(7, true), settings)
        run.begin(cache, cache.palette, 0, 0)
        assertEquals(active, run.foreground)
        assertEquals(TerminalRenderUnderline.NONE, run.hyperlinkUnderline)
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
                    run.configureRow(
                        true,
                        cache.hyperlinkIds,
                        hyperlinkHover(if (hovered) id else 0, active),
                        settings,
                        presentations,
                        followedId,
                    )
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
        style.configureRow(true, cache.hyperlinkIds, hyperlinkHover(7, true), settings)
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
            settings =
                settings.copy { draft ->
                    draft.hyperlinkActivationForeground = activationForeground
                },
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

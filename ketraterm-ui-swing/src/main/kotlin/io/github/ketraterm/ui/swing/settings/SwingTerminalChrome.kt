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
package io.github.ketraterm.ui.swing.settings

import io.github.ketraterm.render.api.TerminalRenderBufferKind

/**
 * EDT-owned chrome geometry shared by sizing, painting and interaction.
 *
 * Primary screen chrome composes host/user margin plus terminal-owned gutters:
 * `left margin | prompt gutter | grid | scrollbar gutter`. Alternate screen
 * sizing uses configured insets; default presentation centers the actual grid,
 * including spare pixels after cell rounding. Presentation offsets never feed
 * back into grid capacity. Explicit alternate padding remains authoritative.
 */
internal class SwingTerminalChrome {
    /** Binding-local availability, initialized from the launch expectation or latched by shell metadata. */
    var promptDecorationsAvailable: Boolean = true

    private var layoutSettings: SwingSettings? = null
    private var layoutPromptDecorationsAvailable = true
    private var alternateLeft = 0
    private var alternateRight = 0
    private var alternateTop = 0
    private var alternateBottom = 0

    /** Resolves default alternate-screen margins around the installed grid without resizing it. */
    fun updateLayout(
        settings: SwingSettings,
        metrics: SwingMetrics,
        componentWidth: Int,
        componentHeight: Int,
        columns: Int,
        rows: Int,
    ): Boolean {
        if (!settings.automaticAlternateScreenPadding) {
            val changed = layoutSettings != null
            layoutSettings = null
            return changed
        }
        val horizontal =
            maxOf(
                horizontalInset(settings, TerminalRenderBufferKind.ALTERNATE).toLong(),
                componentWidth.toLong() - columns.toLong() * metrics.cellWidth,
            )
        val vertical =
            maxOf(
                verticalInset(settings, TerminalRenderBufferKind.ALTERNATE).toLong(),
                componentHeight.toLong() - rows.toLong() * metrics.cellHeight,
            )
        val left = (horizontal / 2).toInt()
        val right = (horizontal - left).toInt()
        val top = (vertical / 2).toInt()
        val bottom = (vertical - top).toInt()
        val changed =
            layoutSettings !== settings ||
                alternateLeft != left ||
                alternateRight != right ||
                alternateTop != top ||
                alternateBottom != bottom
        layoutSettings = settings
        layoutPromptDecorationsAvailable = promptDecorationsAvailable
        alternateLeft = left
        alternateRight = right
        alternateTop = top
        alternateBottom = bottom
        return changed
    }

    private fun hasLayout(settings: SwingSettings): Boolean =
        layoutSettings === settings && layoutPromptDecorationsAvailable == promptDecorationsAvailable

    fun horizontalInset(
        settings: SwingSettings,
        activeBuffer: TerminalRenderBufferKind,
    ): Int =
        if (activeBuffer == TerminalRenderBufferKind.ALTERNATE) {
            val padding = settings.alternateScreenPaddingForPromptGutter(promptDecorationsAvailable)
            padding.left + padding.right
        } else {
            settings.padding.left + promptDecorationGutterWidth(settings, activeBuffer) + settings.padding.right
        }

    fun verticalInset(
        settings: SwingSettings,
        activeBuffer: TerminalRenderBufferKind,
    ): Int =
        if (activeBuffer == TerminalRenderBufferKind.ALTERNATE) {
            val padding = settings.alternateScreenPaddingForPromptGutter(promptDecorationsAvailable)
            padding.top + padding.bottom
        } else {
            settings.padding.top + settings.padding.bottom
        }

    fun left(
        settings: SwingSettings,
        activeBuffer: TerminalRenderBufferKind,
    ): Int =
        if (activeBuffer == TerminalRenderBufferKind.ALTERNATE) {
            if (hasLayout(settings)) alternateLeft else settings.alternateScreenPaddingForPromptGutter(promptDecorationsAvailable).left
        } else {
            settings.padding.left + promptDecorationGutterWidth(settings, activeBuffer)
        }

    fun right(
        settings: SwingSettings,
        activeBuffer: TerminalRenderBufferKind,
    ): Int =
        if (activeBuffer == TerminalRenderBufferKind.ALTERNATE) {
            if (hasLayout(settings)) alternateRight else settings.alternateScreenPaddingForPromptGutter(promptDecorationsAvailable).right
        } else {
            settings.padding.right
        }

    fun top(
        settings: SwingSettings,
        activeBuffer: TerminalRenderBufferKind,
    ): Int =
        if (activeBuffer == TerminalRenderBufferKind.ALTERNATE) {
            if (hasLayout(settings)) alternateTop else settings.alternateScreenPaddingForPromptGutter(promptDecorationsAvailable).top
        } else {
            settings.padding.top
        }

    fun bottom(
        settings: SwingSettings,
        activeBuffer: TerminalRenderBufferKind,
    ): Int =
        if (activeBuffer == TerminalRenderBufferKind.ALTERNATE) {
            if (hasLayout(settings)) alternateBottom else settings.alternateScreenPaddingForPromptGutter(promptDecorationsAvailable).bottom
        } else {
            settings.padding.bottom
        }

    fun promptDecorationGutterWidth(
        settings: SwingSettings,
        activeBuffer: TerminalRenderBufferKind,
    ): Int =
        if (activeBuffer == TerminalRenderBufferKind.ALTERNATE ||
            settings.promptDecoration != SwingPromptDecoration.GUTTER ||
            !promptDecorationsAvailable
        ) {
            0
        } else {
            settings.shellIntegrationDecorationGutterWidth
        }
}

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
 * Computes visual terminal chrome insets without changing terminal semantics.
 *
 * The alternate screen suppresses shell-integration decorations and uses the
 * bottom spacer as its symmetric left/right side inset. The visible grid must
 * therefore be recalculated when the active buffer changes; this lets
 * full-screen TUIs receive the extra columns made available by removing the
 * primary prompt gutter.
 */
internal object SwingTerminalChrome {
    fun horizontalInset(
        settings: SwingSettings,
        activeBuffer: TerminalRenderBufferKind,
    ): Int = left(settings, activeBuffer) + right(settings, activeBuffer)

    fun verticalInset(
        settings: SwingSettings,
        activeBuffer: TerminalRenderBufferKind,
    ): Int = top(settings) + bottom(settings)

    fun left(
        settings: SwingSettings,
        activeBuffer: TerminalRenderBufferKind,
    ): Int =
        if (activeBuffer == TerminalRenderBufferKind.ALTERNATE) {
            alternateScreenSideInset(settings)
        } else {
            settings.padding.left
        }

    fun right(
        settings: SwingSettings,
        activeBuffer: TerminalRenderBufferKind,
    ): Int =
        if (activeBuffer == TerminalRenderBufferKind.ALTERNATE) {
            alternateScreenSideInset(settings)
        } else {
            settings.padding.right
        }

    fun top(settings: SwingSettings): Int = settings.padding.top

    fun bottom(settings: SwingSettings): Int = settings.padding.bottom

    fun promptDecorationGutterWidth(
        settings: SwingSettings,
        activeBuffer: TerminalRenderBufferKind,
    ): Int =
        if (activeBuffer == TerminalRenderBufferKind.ALTERNATE) {
            0
        } else {
            settings.shellIntegrationDecorationGutterWidth.coerceAtMost(settings.padding.left)
        }

    private fun alternateScreenSideInset(settings: SwingSettings): Int = settings.padding.bottom
}

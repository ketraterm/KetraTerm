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
 * Primary screen chrome composes host/user margin plus terminal-owned gutters:
 * `left margin | prompt gutter | grid | scrollbar gutter`. Alternate screen
 * chrome uses its own explicit inset snapshot, so full-screen TUIs can receive
 * an edge-to-edge viewport without hidden coupling to primary-screen padding.
 */
internal object SwingTerminalChrome {
    fun horizontalInset(
        settings: SwingSettings,
        activeBuffer: TerminalRenderBufferKind,
    ): Int = left(settings, activeBuffer) + right(settings, activeBuffer)

    fun verticalInset(
        settings: SwingSettings,
        activeBuffer: TerminalRenderBufferKind,
    ): Int = top(settings, activeBuffer) + bottom(settings, activeBuffer)

    fun left(
        settings: SwingSettings,
        activeBuffer: TerminalRenderBufferKind,
    ): Int =
        if (activeBuffer == TerminalRenderBufferKind.ALTERNATE) {
            settings.alternateScreenPadding.left
        } else {
            settings.padding.left + promptDecorationGutterWidth(settings, activeBuffer)
        }

    fun right(
        settings: SwingSettings,
        activeBuffer: TerminalRenderBufferKind,
    ): Int =
        if (activeBuffer == TerminalRenderBufferKind.ALTERNATE) {
            settings.alternateScreenPadding.right
        } else {
            settings.padding.right
        }

    fun top(
        settings: SwingSettings,
        activeBuffer: TerminalRenderBufferKind,
    ): Int =
        if (activeBuffer == TerminalRenderBufferKind.ALTERNATE) {
            settings.alternateScreenPadding.top
        } else {
            settings.padding.top
        }

    fun bottom(
        settings: SwingSettings,
        activeBuffer: TerminalRenderBufferKind,
    ): Int =
        if (activeBuffer == TerminalRenderBufferKind.ALTERNATE) {
            settings.alternateScreenPadding.bottom
        } else {
            settings.padding.bottom
        }

    fun promptDecorationGutterWidth(
        settings: SwingSettings,
        activeBuffer: TerminalRenderBufferKind,
    ): Int =
        if (activeBuffer == TerminalRenderBufferKind.ALTERNATE) {
            0
        } else {
            settings.shellIntegrationDecorationGutterWidth
        }
}

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
import java.awt.Insets

/**
 * Computes visual terminal chrome insets without changing terminal semantics.
 *
 * Primary screen chrome reserves the prompt gutter and overlay scrollbar
 * gutter. Alternate screen chrome uses its own explicit inset snapshot, so
 * full-screen TUIs can receive an edge-to-edge viewport without hidden coupling
 * to primary-screen padding.
 */
internal object SwingTerminalChrome {
    fun insets(
        settings: SwingSettings,
        activeBuffer: TerminalRenderBufferKind,
    ): Insets =
        if (activeBuffer == TerminalRenderBufferKind.ALTERNATE) {
            settings.alternateScreenPadding
        } else {
            settings.padding
        }

    fun horizontalInset(
        settings: SwingSettings,
        activeBuffer: TerminalRenderBufferKind,
    ): Int {
        val insets = insets(settings, activeBuffer)
        return insets.left + insets.right
    }

    fun verticalInset(
        settings: SwingSettings,
        activeBuffer: TerminalRenderBufferKind,
    ): Int {
        val insets = insets(settings, activeBuffer)
        return insets.top + insets.bottom
    }

    fun left(
        settings: SwingSettings,
        activeBuffer: TerminalRenderBufferKind,
    ): Int = insets(settings, activeBuffer).left

    fun right(
        settings: SwingSettings,
        activeBuffer: TerminalRenderBufferKind,
    ): Int = insets(settings, activeBuffer).right

    fun top(
        settings: SwingSettings,
        activeBuffer: TerminalRenderBufferKind,
    ): Int = insets(settings, activeBuffer).top

    fun bottom(
        settings: SwingSettings,
        activeBuffer: TerminalRenderBufferKind,
    ): Int = insets(settings, activeBuffer).bottom

    fun promptDecorationGutterWidth(
        settings: SwingSettings,
        activeBuffer: TerminalRenderBufferKind,
    ): Int =
        if (activeBuffer == TerminalRenderBufferKind.ALTERNATE) {
            0
        } else {
            settings.shellIntegrationDecorationGutterWidth.coerceAtMost(settings.padding.left)
        }
}

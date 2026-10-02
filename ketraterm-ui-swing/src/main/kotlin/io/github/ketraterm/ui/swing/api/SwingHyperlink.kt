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
package io.github.ketraterm.ui.swing.api

import io.github.ketraterm.render.api.TerminalRenderUnderline

/** A UTF-16 offset in a logical line anchored at its first absolute physical row. */
data class SwingHyperlinkTextPosition(
    val absoluteRow: Long,
    val offset: Int,
) : Comparable<SwingHyperlinkTextPosition> {
    init {
        require(absoluteRow >= 0 && offset >= 0)
    }

    override fun compareTo(other: SwingHyperlinkTextPosition): Int =
        if (absoluteRow == other.absoluteRow) offset.compareTo(other.offset) else absoluteRow.compareTo(other.absoluteRow)
}

/** Half-open logical-text range; an end offset may include the line's trailing newline. */
data class SwingHyperlinkTextRange(
    val start: SwingHyperlinkTextPosition,
    val end: SwingHyperlinkTextPosition,
) {
    init {
        require(start <= end)
    }

    /** Whether this range contains all text coordinates in [other]. */
    fun contains(other: SwingHyperlinkTextRange): Boolean = start <= other.start && other.end <= end
}

/** Resolved, host-neutral colors and decoration. Null fields inherit terminal presentation. */
data class SwingHyperlinkStyle(
    val foregroundArgb: Int? = null,
    val backgroundArgb: Int? = null,
    val underlineArgb: Int? = null,
    val underlineStyle: Int? = null,
    val underlineThickness: Int = 1,
) {
    init {
        require(underlineStyle == null || underlineStyle in TerminalRenderUnderline.NONE..TerminalRenderUnderline.DASHED)
        require(underlineThickness in 1..2)
    }
}

/**
 * Prepared provider styles; theme/framework lookup happens before construction, never in paint.
 *
 * Without an explicit [hovered] style, hovering preserves [normal], or [followed]
 * for the last activated occurrence. [active] applies while hovered and eligible
 * for activation; when absent it uses [hovered] with the same fallback.
 * A missing [followed] style inherits [normal].
 */
data class SwingHyperlinkPresentation(
    val normal: SwingHyperlinkStyle? = null,
    val hovered: SwingHyperlinkStyle? = null,
    val active: SwingHyperlinkStyle? = null,
    val followed: SwingHyperlinkStyle? = null,
    val isVisible: Boolean = false,
) {
    companion object {
        /** Inherit existing detected-link presentation. */
        @JvmField val DEFAULT = SwingHyperlinkPresentation()
    }
}

/** Host activation policy, applied independently of visibility and link appearance. */
enum class SwingHyperlinkActivation {
    /** Ordinary primary-button activation. */
    DIRECT,

    /** Require the platform's navigation modifier (Ctrl, or Cmd on macOS). */
    MODIFIER,
}

/**
 * Immutable discovered occurrence, independent of viewport/action-array positions.
 *
 * [sourceRange] identifies highlighted text; [dependencyRange] includes every
 * character determining discovery or navigation, including token boundaries.
 * [consumedThrough] is the ordered-content position consumed to produce this
 * result, which may highlight an earlier line. Coordinates can precede the
 * current request; the retained owner validates them against captured source.
 * [uri] is an optional complete copyable destination, never display fragments.
 * Objects/styles/actions may allocate during discovery. Providers must not
 * mutate them after reporting; [action] runs on the EDT only on activation.
 */
class SwingHyperlink(
    val sourceRange: SwingHyperlinkTextRange,
    val dependencyRange: SwingHyperlinkTextRange,
    val action: SwingHyperlinkAction,
    val uri: String? = null,
    val presentation: SwingHyperlinkPresentation = SwingHyperlinkPresentation.DEFAULT,
    val activation: SwingHyperlinkActivation = SwingHyperlinkActivation.MODIFIER,
    val consumedThrough: SwingHyperlinkTextPosition = dependencyRange.end,
    val providerOrder: Int = 0,
) {
    init {
        require(sourceRange.start < sourceRange.end)
        require(dependencyRange.contains(sourceRange))
        require(consumedThrough >= dependencyRange.end)
        require(providerOrder >= 0)
    }
}

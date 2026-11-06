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

import io.github.ketraterm.ui.swing.suggestion.ExperimentalShellSuggestionsApi

/**
 * Immutable colors for the built-in shell suggestion popup. Custom views own their styling.
 *
 * Defaults form a dark palette. Every color is a concrete packed ARGB value and is used exactly,
 * without theme lookup, inheritance, or contrast correction during rendering.
 * Text and fills must be opaque; [border] may have alpha, including zero to omit its outline.
 * Apply changed settings with [io.github.ketraterm.ui.swing.api.SwingTerminal.reloadSettings].
 *
 * @property background popup and ordinary row background.
 * @property foreground ordinary label foreground.
 * @property selectionBackground selected row background.
 * @property selectionForeground selected label foreground outside matched fragments.
 * @property matchForeground matched fragment foreground.
 * @property selectionMatchForeground selected matched fragment foreground; defaults to [matchForeground]
 * at construction.
 * @property matchBackground matched fragment background; defaults to [background] at construction.
 * @property selectionMatchBackground selected fragment background; defaults to [selectionBackground]
 * at construction. These are independent values afterwards, including when using `copy()`.
 * @property border popup outline; zero alpha omits it.
 * @property secondaryForeground overflow counter and ordinary path-parent foreground.
 * @property argumentHintForeground ordinary argument-placeholder foreground; defaults to [secondaryForeground] at construction.
 * @property selectionArgumentHintForeground selected argument-placeholder foreground; defaults to [argumentHintForeground].
 * These are concrete independent values after construction, including when using `copy()`.
 * @throws IllegalArgumentException if a non-border color is translucent.
 */
@ExperimentalShellSuggestionsApi
public data class SwingShellSuggestionStyle
    @JvmOverloads
    public constructor(
        val background: Int = 0xFF34383F.toInt(),
        val foreground: Int = 0xFFDFE1E5.toInt(),
        val selectionBackground: Int = 0xFF3A3D44.toInt(),
        val selectionForeground: Int = 0xFFFFFFFF.toInt(),
        val matchForeground: Int = 0xFF82AFFC.toInt(),
        val selectionMatchForeground: Int = matchForeground,
        val matchBackground: Int = background,
        val selectionMatchBackground: Int = selectionBackground,
        val border: Int = 0xFF666B73.toInt(),
        val secondaryForeground: Int = 0xFFADB2BC.toInt(),
        val argumentHintForeground: Int = secondaryForeground,
        val selectionArgumentHintForeground: Int = argumentHintForeground,
    ) {
        init {
            require(
                listOf(
                    background,
                    foreground,
                    selectionBackground,
                    selectionForeground,
                    matchForeground,
                    selectionMatchForeground,
                    matchBackground,
                    selectionMatchBackground,
                    secondaryForeground,
                    argumentHintForeground,
                    selectionArgumentHintForeground,
                ).all { it ushr 24 == 0xFF },
            ) { "Suggestion text and background colors must be opaque" }
        }
    }

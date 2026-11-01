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

/**
 * Immutable explicit host selection presentation. Colors are packed ARGB values, with no sentinel
 * colors or automatic substitution. An untouched [SwingSettings] uses a palette-aware default;
 * assigning this style makes its background exact. Permitted application overrides take precedence per role.
 *
 * @property background fill blended over the existing cell background, including search/link fills.
 * Zero alpha leaves the underlying background visible.
 * @property foreground selected text override, or null to preserve the ordinary text/link color.
 * Explicit text colors must be opaque; use null to inherit rather than transparent text.
 * @property reverseVideo swaps each selected cell's original terminal foreground/background,
 * after inverse attributes, instead of using [background] and [foreground]. Images are not inverted.
 */
public data class SwingSelectionStyle
    @JvmOverloads
    public constructor(
        val background: Int = 0x66FFFFFF,
        val foreground: Int? = null,
        val reverseVideo: Boolean = false,
    ) {
        init {
            require(foreground == null || foreground ushr 24 == 0xFF) { "Selection foreground must be opaque or null" }
        }
    }

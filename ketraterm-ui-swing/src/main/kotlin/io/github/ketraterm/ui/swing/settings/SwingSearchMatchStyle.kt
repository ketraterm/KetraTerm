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
 * Immutable presentation of a normal or active search match. Colors are packed ARGB values.
 * Search presentation is local to the view and never changes stored terminal attributes.
 *
 * @property background fill blended over terminal/link backgrounds; zero alpha leaves them visible.
 * @property foreground opaque text override, or null to preserve underlying terminal/link text.
 * Selection foreground overrides take precedence; enabled minimum contrast is applied afterwards.
 * Concealed and blink-hidden text stays hidden. Intrinsic color emoji and images are not recolored.
 * @property border optional outline, one logical pixel inside each visible contiguous match segment.
 * Alpha is preserved. Null omits the outline. Search fills and outlines are behind selection, text,
 * and cursor painting; opaque selections can cover the outline. Wrapped and bidi-split segments
 * have independent outlines. Outlines never consume layout space or extend beyond matched cells.
 */
public data class SwingSearchMatchStyle
    @JvmOverloads
    public constructor(
        val background: Int,
        val foreground: Int? = null,
        val border: Int? = null,
    ) {
        init {
            require(foreground == null || foreground ushr 24 == 0xFF) { "Search foreground must be opaque or null" }
        }
    }

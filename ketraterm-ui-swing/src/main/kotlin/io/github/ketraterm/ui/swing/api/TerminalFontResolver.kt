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

import java.awt.Font

/**
 * Custom host font resolver policy.
 *
 * Allows embedding environments (like the IntelliJ platform) to supply their own
 * font lookup, caching, and fallback resolution strategies (e.g. leveraging
 * the IDE's internal font cascading/linking utilities or OS-native services).
 */
interface TerminalFontResolver {
    /**
     * Resolves a fallback font that can display the given [codePoint] with the requested
     * [style] and [size2D] size.
     * Returns null if no fallback font could be resolved.
     */
    fun resolveFallbackFont(
        codePoint: Int,
        style: Int,
        size2D: Float,
    ): Font?

    /**
     * Resolves a fallback font that can display the given [text] run with the requested
     * [style] and [size2D] size.
     * Returns null if no fallback font could be resolved.
     */
    fun resolveFallbackFont(
        text: String,
        style: Int,
        size2D: Float,
    ): Font?
}

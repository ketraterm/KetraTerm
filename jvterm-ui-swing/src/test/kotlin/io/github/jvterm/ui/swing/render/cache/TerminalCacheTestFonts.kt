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
package io.github.jvterm.ui.swing.render.cache

import java.awt.Font
import java.awt.GraphicsEnvironment

internal object TerminalCacheTestFonts {
    const val FALLBACK_ONLY_TEXT: String = "\u03A9"
    const val MISSING_CODE_POINT: Int = 0x10FFFF

    fun primary(size: Float): Font = load("fonts/Inconsolata.ttf", size)

    fun fallback(size: Float): Font = load("fonts/DroidSans.ttf", size)

    fun registerFallbackFamily(size: Float = 14f): String {
        val font = fallback(size)
        GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(font)
        return font.family
    }

    private fun load(
        path: String,
        size: Float,
    ): Font {
        val stream =
            requireNotNull(TerminalCacheTestFonts::class.java.classLoader.getResourceAsStream(path)) {
                "Missing test font resource: $path"
            }
        return stream.use { input ->
            Font.createFont(Font.TRUETYPE_FONT, input).deriveFont(Font.PLAIN, size)
        }
    }
}

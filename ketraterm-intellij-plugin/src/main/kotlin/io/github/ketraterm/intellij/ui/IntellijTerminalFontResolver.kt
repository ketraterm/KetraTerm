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

package io.github.ketraterm.intellij.ui

import com.intellij.openapi.editor.impl.ComplementaryFontsRegistry
import com.intellij.openapi.editor.colors.impl.AppFontOptions
import io.github.ketraterm.ui.swing.api.TerminalFontResolver
import java.awt.Font

/**
 * Custom host font resolver using IntelliJ Platform API.
 */
object IntellijTerminalFontResolver : TerminalFontResolver {
    override fun resolveFallbackFont(codePoint: Int, style: Int, size2D: Float): Font? {
        val preferences = AppFontOptions.getInstance().fontPreferences
        val fontInfo = ComplementaryFontsRegistry.getFontAbleToDisplay(
            codePoint,
            style,
            preferences,
            null
        )
        return fontInfo.font.deriveFont(style, size2D)
    }

    override fun resolveFallbackFont(text: String, style: Int, size2D: Float): Font? {
        if (text.isEmpty()) return null
        val codePoint = text.codePointAt(0)
        return resolveFallbackFont(codePoint, style, size2D)
    }
}

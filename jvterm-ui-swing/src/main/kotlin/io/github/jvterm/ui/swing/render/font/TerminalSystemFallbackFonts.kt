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
package io.github.jvterm.ui.swing.render.font

import java.awt.Font
import java.awt.GraphicsEnvironment
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Resolves installed system font family names away from the Swing event-dispatch thread.
 *
 * This intentionally avoids [GraphicsEnvironment.getAllFonts], which forces the
 * JVM font manager to instantiate and retain every installed physical font.
 * Rendering code turns these family names into concrete [Font] instances only
 * after primary and configured fallbacks fail for a glyph.
 */
internal interface TerminalSystemFontFamilies {
    /**
     * Returns loaded font family names, or starts background loading and returns empty.
     */
    fun familiesOrStartLoading(): List<String>
}

internal object TerminalSystemFallbackFonts : TerminalSystemFontFamilies {
    private val started = AtomicBoolean(false)
    private val loadedFamilies = AtomicReference<List<String>?>(null)
    private val executor =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "terminal-ui-system-font-loader").apply {
                isDaemon = true
            }
        }

    /**
     * Returns loaded font family names, or starts a background load and returns empty.
     */
    override fun familiesOrStartLoading(): List<String> {
        val loaded = loadedFamilies.get()
        if (loaded != null) return loaded

        if (started.compareAndSet(false, true)) {
            executor.execute {
                loadedFamilies.compareAndSet(null, loadSystemFontFamilies())
            }
        }
        return emptyList()
    }

    private fun loadSystemFontFamilies(): List<String> =
        try {
            GraphicsEnvironment
                .getLocalGraphicsEnvironment()
                .availableFontFamilyNames
                .asList()
                .distinct()
        } catch (_: RuntimeException) {
            emptyList()
        }
}

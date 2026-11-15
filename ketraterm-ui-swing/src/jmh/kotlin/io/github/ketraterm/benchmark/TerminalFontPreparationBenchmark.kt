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
package io.github.ketraterm.benchmark

import io.github.ketraterm.ui.swing.render.cache.FontCache
import io.github.ketraterm.ui.swing.render.font.TerminalSystemFontFamilies
import io.github.ketraterm.ui.swing.render.platform.TerminalPlatformEmojiRasterizer
import io.github.ketraterm.ui.swing.render.platform.WindowsColorEmojiRasterizer
import io.github.ketraterm.ui.swing.settings.SwingSettings
import org.openjdk.jmh.annotations.*
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.image.BufferedImage
import java.util.concurrent.TimeUnit

/**
 * Owner-thread preparation, excluding EDT dispatch and painting. Real installed families are loaded
 * in setup. Missing scalars cycle over 8192 private-use code points, exceeding the scalar cache;
 * repeatedMissing measures the retained negative result separately. Font reload includes new parsing
 * and allocation but does not promise a cold OS filesystem cache. Run Windows emoji methods on Windows.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
open class TerminalFontPreparationBenchmark {
    private lateinit var fonts: FontCache
    private lateinit var emoji: TerminalPlatformEmojiRasterizer
    private var next = 0

    @Setup
    fun setup() {
        val families = GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toList()
        fonts =
            FontCache(
                systemFontFamilies =
                    object : TerminalSystemFontFamilies {
                        override fun familiesOrStartLoading(): List<String> = families
                    },
            )
        fonts.update(Font(Font.MONOSPACED, Font.PLAIN, 14), emptyList(), true)
        fonts.fontForCodePoint(0x10fffd, Font.PLAIN)
        emoji = WindowsColorEmojiRasterizer.create()
        SwingSettings()
        println("Installed font families: ${families.size}; Windows color emoji available: ${emoji.available}")
    }

    @Benchmark fun ascii(): Font = fonts.fontForCodePoint('a'.code, Font.PLAIN)

    @Benchmark fun repeatedMissing(): Font = fonts.fontForCodePoint(0x10fffd, Font.PLAIN)

    @Benchmark fun distinctMissing(): Font = fonts.fontForCodePoint(0x100000 + (next++ and 8191), Font.PLAIN)

    @Benchmark fun settingsSnapshot(): SwingSettings = SwingSettings()

    @Benchmark fun emojiFontReload(): Any = WindowsColorEmojiRasterizer.create()

    @Benchmark fun emojiRasterize(): BufferedImage? = emoji.rasterize("\uD83D\uDE00", 20)
}

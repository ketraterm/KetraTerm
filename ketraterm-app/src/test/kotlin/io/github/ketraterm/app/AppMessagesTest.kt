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
package io.github.ketraterm.app

import java.text.MessageFormat
import java.text.NumberFormat
import java.util.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppMessagesTest {
    @Test
    fun `locale variants resolve UTF-8 text and fall back to the English catalogue`() {
        val translated = AppMessages(Locale.of("zz", "ZZ"))
        assertEquals("Paramètres du terminal", translated.text("settings.title"))
        assertEquals("Version d’essai : 1.2.3", translated.text("about.version", "1.2.3"))
        assertEquals("A high-performance, modern terminal emulator.", translated.text("about.description"))
        assertEquals("Terminal Settings", AppMessages(Locale.JAPANESE).text("settings.title"))
    }

    @Test
    fun `formatted messages preserve user text and use their requested locale`() {
        val locale = Locale.FRENCH
        val name = "l'élève {0}"
        val count = 12_345
        val formattedCount = NumberFormat.getIntegerInstance(locale).format(count)
        assertEquals(
            "Closing \"$name\" will kill $formattedCount running processes.",
            AppMessages(locale).text("close.multipleMessage", name, count),
        )
        assertEquals(
            "Closing \"O'Neil\" will kill its running process.",
            AppMessages(Locale.ENGLISH).text("close.singleMessage", "O'Neil"),
        )
    }

    @Test
    fun `base catalogue contains nonblank text and valid message patterns`() {
        val bundle = ResourceBundle.getBundle("io.github.ketraterm.app.messages", Locale.ROOT)
        assertTrue(bundle.keySet().isNotEmpty())
        for (key in bundle.keySet()) {
            val text = bundle.getString(key)
            assertTrue(text.isNotBlank(), "Empty message: $key")
            MessageFormat(text, Locale.ROOT)
        }
    }
}

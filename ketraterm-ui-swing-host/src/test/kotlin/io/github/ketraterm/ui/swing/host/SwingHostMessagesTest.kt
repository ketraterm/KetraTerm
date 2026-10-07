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
package io.github.ketraterm.ui.swing.host

import java.util.*
import kotlin.test.Test
import kotlin.test.assertEquals

class SwingHostMessagesTest {
    @Test
    fun propertyBundlesOverridePartialTextAndFallBackToEnglish() {
        val bundle = ResourceBundle.getBundle("io.github.ketraterm.ui.swing.host.LocalizedTerminalMessages", Locale.FRENCH)
        val messages = SwingHostMessages.forLocale(Locale.FRENCH, bundle)
        assertEquals("Rechercher", messages.message("search.placeholder"))
        assertEquals("Rechercher dans le terminal", messages.message("search.queryToolTip"))
        assertEquals("4 résultats ; actif 2", messages.message("search.matchCounter", 2, 4))
        assertEquals("Copier", messages.message("menu.copy"))
        assertEquals("Paste", messages.message("menu.paste"))
        assertEquals("Accès au presse-papiers", messages.message("clipboard.title"))
        assertEquals(
            "Depuis Shell, écrire un caractère dans presse-papiers ?",
            SwingClipboardPrompts.writeQuestion("Shell", "x", messages = messages),
        )
        assertEquals(
            "Depuis Shell, écrire 2 caractères dans presse-papiers ?",
            SwingClipboardPrompts.writeQuestion("Shell", "xy", messages = messages),
        )
    }

    @Test
    fun selectedLocaleFormatsCountersWithoutGroupingAndEnglishFallbackKeepsUserTextLiteral() {
        val messages = SwingHostMessages.forLocale(Locale.forLanguageTag("ar-u-nu-arab"))
        assertEquals("٢/١٠٠٠", messages.message("search.matchCounter", 2, 1000))
        val clipboard = SwingHostMessages.forLocale(Locale.ENGLISH)
        assertEquals(
            "Allow an application in O'Brien {0} to write 2 characters to the clipboard?",
            SwingClipboardPrompts.writeQuestion("O'Brien {0}", "xy", messages = clipboard),
        )
    }
}

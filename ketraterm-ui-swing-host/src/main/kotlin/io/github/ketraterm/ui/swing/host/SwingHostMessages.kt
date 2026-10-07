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

import java.text.MessageFormat
import java.util.*

/**
 * Message-bundle boundary for optional Swing host chrome and clipboard consent.
 *
 * Keys and English defaults live in `io/github/ketraterm/ui/swing/host/SwingHostMessages.properties`.
 * Hosts may bridge their own message framework with a lambda, or use [forLocale] with a custom bundle.
 * Messages are plain text. Complete patterns allow localized word order, plural forms and numbers;
 * clipboard messages receive only terminal names and character counts, never clipboard contents.
 * Components resolve static text at construction and dynamic text on UI changes, outside painting.
 * Implementations must support their host's calling threads; UI and consent calls occur on the EDT.
 */
public fun interface SwingHostMessages {
    /** Resolves [key] and inserts [arguments] without treating argument text as a message pattern. */
    public fun message(
        key: String,
        vararg arguments: Any,
    ): String

    public companion object {
        private const val BASE_NAME = "io.github.ketraterm.ui.swing.host.SwingHostMessages"
        private val control = ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES)
        private val english = ResourceBundle.getBundle(BASE_NAME, Locale.ROOT, control)

        /**
         * Creates a JDK bundle facade for [locale]. Missing custom keys use the English catalog.
         * Formatted messages use [MessageFormat]; the requested locale controls number formatting.
         * The locale is captured once, so independent hosts can use different languages concurrently.
         */
        @JvmStatic
        @JvmOverloads
        public fun forLocale(
            locale: Locale = Locale.getDefault(Locale.Category.DISPLAY),
            bundle: ResourceBundle = ResourceBundle.getBundle(BASE_NAME, locale, control),
        ): SwingHostMessages =
            SwingHostMessages { key, arguments ->
                val pattern = if (bundle.containsKey(key)) bundle.getString(key) else english.getString(key)
                if (arguments.isEmpty()) pattern else MessageFormat(pattern, locale).format(arguments)
            }
    }
}

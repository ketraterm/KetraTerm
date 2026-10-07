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

import java.text.MessageFormat
import java.util.*

/**
 * Host message lookup for reusable Swing presentation.
 *
 * Hosts may delegate to their existing bundle facade. Keys and complete patterns
 * are defined in `io.github.ketraterm.ui.swing.suggestion.SwingCompletionMessages`.
 * Values are plain text; popup markup is escaped by the view. Lookups run on the
 * EDT while presentation state changes, outside repeated terminal painting.
 */
public fun interface SwingTerminalMessages {
    /** Returns the message for [key], formatting its complete pattern with [arguments] when supplied. */
    public fun message(
        key: String,
        vararg arguments: Any,
    ): String

    public companion object {
        /**
         * Creates a UTF-8 resource-bundle lookup with English fallback for missing keys.
         *
         * Patterns use [MessageFormat] only when arguments are supplied; static
         * text is returned verbatim. The explicit [locale] controls formatting.
         * The returned lookup is thread-safe for shared component owners.
         */
        @JvmStatic
        @JvmOverloads
        public fun forLocale(
            locale: Locale = Locale.getDefault(Locale.Category.DISPLAY),
            bundle: ResourceBundle =
                ResourceBundle.getBundle(
                    "io.github.ketraterm.ui.swing.suggestion.SwingCompletionMessages",
                    locale,
                    ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES),
                ),
        ): SwingTerminalMessages {
            val fallback = ResourceBundle.getBundle("io.github.ketraterm.ui.swing.suggestion.SwingCompletionMessages", Locale.ROOT)
            return SwingTerminalMessages { key, arguments ->
                val pattern = if (bundle.containsKey(key)) bundle.getString(key) else fallback.getString(key)
                if (arguments.isEmpty()) pattern else MessageFormat(pattern, locale).format(arguments)
            }
        }
    }
}

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
import java.util.*

/** Loads standalone UI text for one display locale, falling back to the English base catalogue. */
internal class AppMessages(
    private val locale: Locale = Locale.getDefault(Locale.Category.DISPLAY),
) {
    private val bundle =
        ResourceBundle.getBundle(
            "io.github.ketraterm.app.messages",
            locale,
            ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES),
        )

    fun text(key: String): String = bundle.getString(key)

    fun text(
        key: String,
        vararg arguments: Any?,
    ): String = MessageFormat(bundle.getString(key), locale).format(arguments)
}

/** Stable text snapshot shared by the standalone application's UI. */
internal val appMessages: AppMessages = AppMessages()

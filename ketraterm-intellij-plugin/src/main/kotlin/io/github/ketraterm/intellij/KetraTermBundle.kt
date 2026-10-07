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
package io.github.ketraterm.intellij

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey
import java.util.*

private const val BUNDLE = "messages.MyMessageBundle"

/**
 * Typed access point for localized KetraTerm plugin messages.
 */
internal object KetraTermBundle {
    private val instance = DynamicBundle(KetraTermBundle::class.java, BUNDLE)

    /** Current IDE UI language, which can differ from the JVM default locale. */
    val locale: Locale get() = DynamicBundle.getLocale()

    /**
     * Resolves a localized message by key.
     *
     * @param key resource-bundle key.
     * @param params optional formatting parameters.
     * @return localized message text.
     */
    @JvmStatic
    fun message(
        key:
            @PropertyKey(resourceBundle = BUNDLE)
            String,
        vararg params: Any?,
    ): @Nls String = instance.getMessage(key, *params)
}

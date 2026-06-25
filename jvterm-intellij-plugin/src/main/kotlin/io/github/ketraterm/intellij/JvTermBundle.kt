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
import java.util.function.Supplier

private const val BUNDLE = "messages.MyMessageBundle"

/**
 * Typed access point for localized JvTerm plugin messages.
 */
internal object JvTermBundle {
    private val instance = DynamicBundle(JvTermBundle::class.java, BUNDLE)

    /**
     * Resolves a localized message by key.
     *
     * @param key resource-bundle key.
     * @param params optional formatting parameters.
     * @return localized message text.
     */
    @JvmStatic
    fun message(
        key: @PropertyKey(resourceBundle = BUNDLE) String,
        vararg params: Any?,
    ): @Nls String = instance.getMessage(key, *params)

    /**
     * Resolves a localized message lazily.
     *
     * @param key resource-bundle key.
     * @param params optional formatting parameters.
     * @return supplier that resolves localized message text.
     */
    @JvmStatic
    fun lazyMessage(
        @PropertyKey(resourceBundle = BUNDLE) key: String,
        vararg params: Any?,
    ): Supplier<@Nls String> = instance.getLazyMessage(key, *params)
}

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

import io.github.ketraterm.intellij.KetraTermBundle
import io.github.ketraterm.ui.swing.host.SwingHostMessages

/** Resolves reusable Swing chrome through the IDE UI language and language-pack bundles. */
internal object IntellijSwingHostMessages : SwingHostMessages {
    override fun message(
        key: String,
        vararg arguments: Any,
    ): String = KetraTermBundle.message(key, *arguments)
}

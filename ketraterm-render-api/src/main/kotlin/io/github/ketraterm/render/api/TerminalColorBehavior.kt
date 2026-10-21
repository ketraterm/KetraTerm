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
package io.github.ketraterm.render.api

/** Primitive rendering flags for dynamic colors and explicit selection presentation. */
public object TerminalColorBehavior {
    /** Resolve cursor text from the original cell background. */
    public const val DYNAMIC_CURSOR_FOREGROUND: Int = 1

    /** Resolve cursor fill, outline, bar or underline from the original cell foreground. */
    public const val DYNAMIC_CURSOR_BACKGROUND: Int = 1 shl 1

    /** Resolve selected text from the original cell background; requires [OVERRIDE_SELECTION_FOREGROUND]. */
    public const val DYNAMIC_SELECTION_FOREGROUND: Int = 1 shl 2

    /** Resolve selection fill from the original cell foreground; requires [OVERRIDE_SELECTION_BACKGROUND]. */
    public const val DYNAMIC_SELECTION_BACKGROUND: Int = 1 shl 3

    /** Render selected text using the palette's fixed or dynamic selection foreground. */
    public const val OVERRIDE_SELECTION_FOREGROUND: Int = 1 shl 4

    /** Render selection fill using the palette's fixed or dynamic selection background. */
    public const val OVERRIDE_SELECTION_BACKGROUND: Int = 1 shl 5

    internal const val ALL: Int = (1 shl 6) - 1
}

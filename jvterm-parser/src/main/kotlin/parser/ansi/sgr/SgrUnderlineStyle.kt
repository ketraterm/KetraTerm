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
package com.gagik.parser.ansi.sgr

internal object SgrUnderlineStyle {
    const val NONE: Int = 0
    const val SINGLE: Int = 1
    const val DOUBLE: Int = 2
    const val CURLY: Int = 3
    const val DOTTED: Int = 4
    const val DASHED: Int = 5
}

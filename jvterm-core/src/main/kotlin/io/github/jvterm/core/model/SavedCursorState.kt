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
package io.github.jvterm.core.model

/**
 * Pre-allocated mutable slot for DECSC/DECRC (ESC 7 / ESC 8).
 *
 * Mutated in-place on every save to produce zero heap allocation.
 * [isSaved] is false until the first DECSC is issued.
 */
internal class SavedCursorState {
    var col: Int = 0
    var row: Int = 0
    var attr: Long = 0
    var extendedAttr: Long = 0
    var pendingWrap: Boolean = false
    var isOriginMode: Boolean = false
    var isSaved: Boolean = false

    fun clear() {
        col = 0
        row = 0
        attr = 0
        extendedAttr = 0
        pendingWrap = false
        isOriginMode = false
        isSaved = false
    }
}

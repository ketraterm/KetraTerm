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
package io.github.ketraterm.completion.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TerminalCompletionModelsTest {
    @Test
    fun `request accepts cursor at UTF-16 scalar boundaries`() {
        val request = TerminalCompletionRequest(commandLine = "a\uD83D\uDE02", cursorOffset = 3)

        assertEquals(3, request.cursorOffset)
    }

    @Test
    fun `request rejects cursor that splits surrogate pair`() {
        assertFailsWith<IllegalArgumentException> {
            TerminalCompletionRequest(commandLine = "a\uD83D\uDE02", cursorOffset = 2)
        }
    }
}

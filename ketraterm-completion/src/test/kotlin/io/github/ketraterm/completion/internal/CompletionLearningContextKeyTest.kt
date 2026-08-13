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
package io.github.ketraterm.completion.internal

import kotlin.test.Test
import kotlin.test.assertEquals

class CompletionLearningContextKeyTest {
    @Test
    fun `canonical context treats trailing directory separator as identity`() {
        assertEquals(
            CompletionLearningContextKey.of("profile", "file:///repo"),
            CompletionLearningContextKey.of("profile", "file:///repo/"),
        )
    }

    @Test
    fun `specificity prefers directory-only context over profile-only context`() {
        val request = CompletionLearningContextKey.of("profile", "file:///repo")
        val values =
            mapOf(
                CompletionLearningContextKey.of(null, null) to "global",
                CompletionLearningContextKey.of("profile", null) to "profile",
                CompletionLearningContextKey.of(null, "file:///repo") to "directory",
            )

        val match = requireNotNull(request.mostSpecific(values::get))

        assertEquals("directory", match.value)
        assertEquals(CompletionLearningContextKey.of(null, "file:///repo"), match.context)
    }
}

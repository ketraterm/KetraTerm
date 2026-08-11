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
package io.github.ketraterm.intellij.services

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Regression tests for IntelliJ-native project path matching. */
class IntellijProjectFileNameMatcherTest {
    @Test
    fun `matches a non-contiguous filename query like Search Everywhere`() {
        val matcher = IntellijFuzzyMatcher("sgk")

        assertNotNull(matcher.score("settings.gradle.kts"))
        assertNull(matcher.score("build.gradle.kts"))
    }

    @Test
    fun `keeps an explicit directory prefix scoped to that path`() {
        val matcher = IntellijFuzzyMatcher("src/main/.g")

        assertNotNull(matcher.score("src/main/.generated/Hidden.kt"))
        assertNull(matcher.score("other/.generated/Hidden.kt"))
    }
}

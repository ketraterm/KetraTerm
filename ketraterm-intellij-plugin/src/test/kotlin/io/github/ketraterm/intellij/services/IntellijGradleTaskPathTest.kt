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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Unit tests for public External System data conversion into Gradle task paths. */
class IntellijGradleTaskPathTest {
    /** Verifies root and nested public module identifiers produce invocable Gradle paths. */
    @Test
    fun `builds fully qualified paths from imported module ids`() {
        assertEquals(":build", IntellijGradleTaskPath.fullyQualified("root", "build"))
        assertEquals(":app:test", IntellijGradleTaskPath.fullyQualified(":app", "test"))
        assertEquals(":app:feature:check", IntellijGradleTaskPath.fullyQualified("root:app:feature", "check"))
    }

    /** Verifies already-qualified Gradle task names are retained without duplicate module prefixes. */
    @Test
    fun `retains already qualified task paths`() {
        assertEquals(":included:verify", IntellijGradleTaskPath.fullyQualified(":ignored", ":included:verify"))
    }

    /** Verifies malformed imported model values are not offered as terminal completion. */
    @Test
    fun `rejects blank module or task data`() {
        assertNull(IntellijGradleTaskPath.fullyQualified("", "build"))
        assertNull(IntellijGradleTaskPath.fullyQualified(":app", "  "))
    }
}

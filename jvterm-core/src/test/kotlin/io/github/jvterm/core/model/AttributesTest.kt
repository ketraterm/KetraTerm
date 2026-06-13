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

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Attributes")
class AttributesTest {
    @Test
    fun `indexed colors reuse cached descriptors`() {
        assertAll(
            { assertSame(AttributeColor.indexed(0), AttributeColor.indexed(0)) },
            { assertSame(AttributeColor.indexed(255), AttributeColor.indexed(255)) },
            { assertEquals(AttributeColorKind.INDEXED, AttributeColor.indexed(42).kind) },
            { assertEquals(42, AttributeColor.indexed(42).value) },
        )
    }

    @Test
    fun `indexed colors reject out-of-range palette indexes`() {
        assertAll(
            { assertThrows(IllegalArgumentException::class.java) { AttributeColor.indexed(-1) } },
            { assertThrows(IllegalArgumentException::class.java) { AttributeColor.indexed(256) } },
        )
    }
}

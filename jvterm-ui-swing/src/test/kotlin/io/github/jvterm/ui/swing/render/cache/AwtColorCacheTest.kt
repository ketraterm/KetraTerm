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
package io.github.jvterm.ui.swing.render.cache

import kotlin.test.*

class AwtColorCacheTest {
    @Test
    fun reusesColorInstancesForRepeatedArgbValues() {
        val cache = AwtColorCache()

        assertSame(
            cache.color(0xFF010203.toInt()),
            cache.color(0xFF010203.toInt()),
        )
    }

    @Test
    fun evictsLeastRecentlyUsedColorWhenCapacityIsReached() {
        val cache = AwtColorCache(capacity = 2)
        val first = cache.color(0xFF010203.toInt())
        val second = cache.color(0xFF040506.toInt())

        assertSame(first, cache.color(0xFF010203.toInt()))
        cache.color(0xFF070809.toInt())

        assertSame(first, cache.color(0xFF010203.toInt()))
        assertNotSame(second, cache.color(0xFF040506.toInt()))
    }

    @Test
    fun keepsStorageBoundedForUniqueTruecolorStream() {
        val cache = AwtColorCache(capacity = 4)

        repeat(128) { index ->
            cache.color(0xFF000000.toInt() or index)
        }

        assertEquals(4, cache.cachedColorCount())
    }

    @Test
    fun rejectsNonPositiveCapacity() {
        assertFailsWith<IllegalArgumentException> {
            AwtColorCache(capacity = 0)
        }
    }

    private fun AwtColorCache.cachedColorCount(): Int {
        val field = AwtColorCache::class.java.getDeclaredField("size")
        field.isAccessible = true
        return field.getInt(this)
    }
}

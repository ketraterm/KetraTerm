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
package io.github.ketraterm.core.graphics

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class PlacementIndexTest {
    @Test fun `growth deletion and replacement preserve colliding unsigned keys`() {
        val index = PlacementIndex()
        val keys = LongArray(4096) { ((it % 256).toLong() shl 32) or (4294967295L - it) }
        for (i in keys.indices) index.put(keys[i], i)
        for (i in keys.indices step 3) index.remove(keys[i])
        for (i in keys.indices) assertEquals(if (i % 3 == 0) -1 else i, index.get(keys[i]))
        for (i in keys.indices) index.put(keys[i], keys.size - i)
        for (i in keys.indices) assertEquals(keys.size - i, index.get(keys[i]))
        for (key in keys) index.remove(key)
        for (key in keys) assertEquals(-1, index.get(key))
    }
}

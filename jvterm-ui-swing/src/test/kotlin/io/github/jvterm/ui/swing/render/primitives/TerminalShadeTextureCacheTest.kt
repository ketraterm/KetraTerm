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
package io.github.jvterm.ui.swing.render.primitives

import io.github.jvterm.ui.swing.render.primitives.TerminalBlockElementGlyphs.SHADE_LIGHT
import io.github.jvterm.ui.swing.render.primitives.TerminalBlockElementGlyphs.SHADE_MEDIUM
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class TerminalShadeTextureCacheTest {
    @Test
    fun `texture reuses cached instances for identical kind and color`() {
        val cache = TerminalShadeTextureCache()
        val argb = 0xFF123456.toInt()

        val first = cache.texture(SHADE_MEDIUM, argb)
        val second = cache.texture(SHADE_MEDIUM, argb)

        assertSame(first, second, "Cache must return the exact same TexturePaint instance to prevent allocation")
    }

    @Test
    fun `texture isolates instances by color and shade kind`() {
        val cache = TerminalShadeTextureCache()
        val argb1 = 0xFF111111.toInt()
        val argb2 = 0xFF222222.toInt()

        val light1 = cache.texture(SHADE_LIGHT, argb1)
        val light2 = cache.texture(SHADE_LIGHT, argb2)
        val medium1 = cache.texture(SHADE_MEDIUM, argb1)

        assertNotSame(light1, light2, "Different colors must yield different textures")
        assertNotSame(light1, medium1, "Different shade kinds must yield different textures")
    }

    @Test
    fun `texture matrix dimensions match required pixel step resolutions`() {
        val cache = TerminalShadeTextureCache()
        val argb = 0xFFFFFFFF.toInt()

        val light = cache.texture(SHADE_LIGHT, argb)
        val medium = cache.texture(SHADE_MEDIUM, argb)

        // SHADE_LIGHT uses a 4x4 matrix for 25% density
        assertEquals(4.0, light.anchorRect.width, 0.0)
        assertEquals(4.0, light.anchorRect.height, 0.0)

        // SHADE_MEDIUM uses a 2x2 matrix for 50% density
        assertEquals(2.0, medium.anchorRect.width, 0.0)
        assertEquals(2.0, medium.anchorRect.height, 0.0)
    }
}

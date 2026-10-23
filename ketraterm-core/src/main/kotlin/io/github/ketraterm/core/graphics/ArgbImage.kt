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

import io.github.ketraterm.render.api.TerminalRenderImage

/**
 * Immutable render resource taking exclusive ownership of a newly decoded pixel array.
 * The admission engine supplies exactly width * height row-major, unassociated sRGB
 * ARGB pixels and must never mutate them again. Construction does not copy; consumers
 * receive copies through [copyArgb] and may retain this resource beyond a frame lease.
 */
internal class ArgbImage(
    override val width: Int,
    override val height: Int,
    private val pixels: IntArray,
) : TerminalRenderImage {
    override fun copyArgb(
        destination: IntArray,
        offset: Int,
    ) {
        require(offset >= 0 && offset <= destination.size && pixels.size <= destination.size - offset)
        pixels.copyInto(destination, offset)
    }
}

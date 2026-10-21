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
package io.github.ketraterm.render.api

/**
 * Immutable, retainable image resource. Unlike borrowed frames, this object may
 * outlive a frame callback. Identity denotes a particular pixel version.
 * Dimensions and pixels are immutable; source-retention metadata may change.
 * Pixels are unassociated 8-bit sRGB ARGB; implementations never expose mutable storage.
 */
public interface TerminalRenderImage {
    /**
     * Whether the originating image store still retains this pixel version for
     * future placements, including offscreen placements. Once false, it stays false;
     * [copyArgb], dimensions and pixels remain valid for existing consumers.
     *
     * Renderers may discard a cached raster when this is false and the resource is
     * absent from their installed frame. A frame can still reference a retired
     * resource, so this property must not suppress its rendering. Implementations
     * tracking retirement must make changes visible across threads. The default
     * is true for providers without retirement tracking.
     *
     * @return true while the source retains the resource, or when retention is unknown.
     */
    public val isRetainedBySource: Boolean get() = true

    /** Positive width of the immutable pixel raster, in source pixels. */
    public val width: Int

    /** Positive height of the immutable pixel raster, in source pixels. */
    public val height: Int

    /**
     * Copies all [width] × [height] row-major pixels into [destination] at [offset].
     * The destination needs that many entries after a nonnegative offset. Preparing
     * a renderer raster once per resource identity avoids copying pixels every frame.
     *
     * @param destination caller-owned pixel array, requiring [width] × [height] entries after [offset].
     * @param offset first destination index; zero by default.
     * @throws IllegalArgumentException if [offset] is negative, exceeds the array size,
     * or leaves fewer entries than the image's pixel count.
     */
    public fun copyArgb(
        destination: IntArray,
        offset: Int = 0,
    )
}

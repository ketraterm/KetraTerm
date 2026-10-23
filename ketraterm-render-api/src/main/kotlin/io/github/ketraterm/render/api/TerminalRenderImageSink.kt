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

/** Receives placements in back-to-front order without allocating placement objects. */
public fun interface TerminalRenderImageSink {
    /**
     * Destination and clipping coordinates are fractional cells relative to the
     * requested viewport; source coordinates are image pixels. Clip bounds are
     * inclusive top/exclusive bottom. The immutable [image] may be retained.
     * Placements may extend outside the viewport; intersect their destination with
     * the viewport and vertical clip bounds without rescaling the source crop.
     * The positive source rectangle lies within the image. Destination width and
     * height describe the scaled crop before clipping, not the underlying raster.
     * Negative z is below text; z below -1073741824 is also below nondefault backgrounds.
     *
     * @param image immutable pixel version, which may be retained beyond the callback.
     * @param column left destination edge in fractional viewport cells.
     * @param row top destination edge in fractional viewport cells, possibly negative.
     * @param width positive scaled destination width in cells, before clipping.
     * @param height positive scaled destination height in cells, before clipping.
     * @param sourceX nonnegative left crop edge in source pixels.
     * @param sourceY nonnegative top crop edge in source pixels.
     * @param sourceWidth positive crop width within [image].
     * @param sourceHeight positive crop height within [image].
     * @param clipTop inclusive vertical clip edge in fractional viewport cells.
     * @param clipBottom exclusive vertical clip edge in fractional viewport cells.
     * @param zIndex signed stacking layer relative to cell backgrounds and text.
     */
    public fun accept(
        image: TerminalRenderImage,
        column: Double,
        row: Double,
        width: Double,
        height: Double,
        sourceX: Int,
        sourceY: Int,
        sourceWidth: Int,
        sourceHeight: Int,
        clipTop: Double,
        clipBottom: Double,
        zIndex: Int,
    )
}

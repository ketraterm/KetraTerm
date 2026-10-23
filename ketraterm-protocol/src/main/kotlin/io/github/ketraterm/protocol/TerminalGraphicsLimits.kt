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
package io.github.ketraterm.protocol

/**
 * KetraTerm admission limits, not Kitty wire constants or a total JVM memory cap.
 * Retained counts cover both screens and history; external consumers may retain
 * immutable image versions after core releases them.
 */
public object TerminalGraphicsLimits {
    /** Control-header bytes, excluding APC introducer, separator and terminator. */
    public const val HEADER_BYTES: Int = 1024

    /** Base64 payload bytes in one APC chunk, independent of ingress read size. */
    public const val CHUNK_BYTES: Int = 128 * 1024

    /** Cumulative Base64 payload bytes in one upload. */
    public const val ENCODED_BYTES: Int = 48 * 1024 * 1024

    /** Decoded bytes retained while assembling one upload. */
    public const val TRANSFER_BYTES: Int = 33 * 1024 * 1024

    /** Maximum width or height in source pixels, checked before raster allocation. */
    public const val DIMENSION: Int = 8192

    /** Maximum source pixel count for one image, in addition to the edge limit. */
    public const val PIXELS: Int = 8_388_608

    /** Canonical ARGB bytes retained by core; a view's raster cache has the same separate budget. */
    public const val RETAINED_BYTES: Int = 64 * 1024 * 1024

    /** Retained image records, including images without placements. */
    public const val IMAGES: Int = 256

    /** Retained placement records; many placements may reference one image. */
    public const val PLACEMENTS: Int = 65_536
}

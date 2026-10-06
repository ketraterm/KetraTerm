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
package io.github.ketraterm.parser.spi

/**
 * A command sink that accepts printable ASCII in borrowed slices.
 *
 * Parsers use this capability to batch independent ASCII graphemes. Sinks implementing
 * only [TerminalCommandSink] continue to receive individual [writeCodepoint] calls.
 */
public interface TerminalAsciiCommandSink : TerminalCommandSink {
    /**
     * Writes independent printable ASCII graphemes in order at the cursor position.
     *
     * Every byte in the specified slice is in `0x20..0x7e` and has already passed charset
     * mapping and grapheme segmentation. The sink owns cell width and all grid behavior.
     * The array is borrowed only for this call and must be consumed synchronously, not retained.
     * An empty slice has no effect.
     *
     * @param bytes The backing array containing printable ASCII bytes.
     * @param offset The first byte in the slice.
     * @param length The number of bytes in the slice.
     */
    public fun writeAscii(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    )
}

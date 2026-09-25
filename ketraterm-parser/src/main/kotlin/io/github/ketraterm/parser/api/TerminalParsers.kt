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
package io.github.ketraterm.parser.api

import io.github.ketraterm.parser.impl.TerminalParser
import io.github.ketraterm.parser.spi.TerminalCommandSink

/**
 * Factory for terminal output parsers.
 */
object TerminalParsers {
    /**
     * Creates a new instance of [TerminalOutputParser] that routes parsed
     * commands to the specified [sink].
     *
     * Retains the first 32 codepoints of each grapheme, including its base. Excess
     * continuations advance segmentation context but are not emitted, so they do
     * not create cells or contribute to stored, rendered, or copied text.
     *
     * @param sink The command sink where parsed terminal commands will be delivered.
     * @param clipboardWriteLimitBytes Supplies the permitted decoded-byte budget when OSC 52
     * write data starts. Zero keeps the ordinary 4 KiB envelope bound. A positive budget
     * permits bounded, temporary Base64 collection beyond that bound; it does not authorize
     * a clipboard operation. The sink must validate the complete data and current permission.
     * The callback runs synchronously at most once per write and must return a nonnegative value.
     * Command/selection headers remain bounded by 4 KiB. Encoded capacity is computed with
     * checked-width arithmetic and capped at `Int.MAX_VALUE - 8` for JVM array indexing.
     * Budget changes affect the next write; a completed command still needs current host validation.
     * @return A newly initialized [TerminalOutputParser] instance.
     */
    @JvmStatic
    @JvmOverloads
    fun create(
        sink: TerminalCommandSink,
        clipboardWriteLimitBytes: () -> Int = { 0 },
    ): TerminalOutputParser = TerminalParser(sink, clipboardWriteLimitBytes = clipboardWriteLimitBytes)
}

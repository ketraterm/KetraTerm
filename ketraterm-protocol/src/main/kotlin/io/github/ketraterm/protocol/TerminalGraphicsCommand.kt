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
 * Borrowed Kitty graphics controls. Values retain their wire units and omission;
 * character-valued controls use their ASCII code. IDs are unsigned 32-bit values
 * represented as [Long]. Valid only during the receiving command callback.
 * Keys retain the Kitty protocol's case-sensitive spelling: for example `i`
 * selects an image ID while `I` selects an image number. Coordinates and sizes
 * have not been converted from their wire units. Consumers apply operation
 * defaults with [valueOr] and must not retain this reusable view.
 */
public interface TerminalGraphicsCommand {
    /**
     * Reads a case-sensitive wire control without applying an operation default.
     *
     * @param key Kitty control letter; unrecognized characters are treated as absent.
     * @return the wire value, or [OMITTED] when the control is absent or unknown.
     */
    public fun value(key: Char): Long

    /**
     * Resolves an omitted control without collapsing an explicit zero.
     *
     * @param key case-sensitive Kitty control letter.
     * @param default value to use only when [value] returns [OMITTED].
     * @return the supplied control value, or [default] when absent or unknown.
     */
    public fun valueOr(
        key: Char,
        default: Long,
    ): Long = value(key).let { if (it == OMITTED) default else it }

    /** Sentinel shared by control producers and consumers. */
    public companion object {
        /** Outside every permitted signed or unsigned control range. */
        public const val OMITTED: Long = Long.MIN_VALUE
    }
}

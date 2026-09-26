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
 * Validated OSC 52 selectors in request order, without duplicates.
 * Empty selection means the host clipboard; primary, secondary and cut-buffer
 * selectors remain distinct even when a host cannot supply them.
 */
@JvmInline
value class TerminalClipboardSelection private constructor(
    val value: String,
) {
    companion object {
        /** Returns null for an unknown selector. No unchecked text can enter a reply. */
        fun parse(value: String): TerminalClipboardSelection? {
            if (value.isEmpty()) return TerminalClipboardSelection("c")
            var seen = 0
            val selectors = StringBuilder(ALPHABET.length)
            for (selector in value) {
                val index = ALPHABET.indexOf(selector)
                if (index < 0) return null
                val bit = 1 shl index
                if (seen and bit == 0) {
                    seen = seen or bit
                    selectors.append(selector)
                }
            }
            return TerminalClipboardSelection(selectors.toString())
        }

        private const val ALPHABET = "cpqs01234567"
    }
}

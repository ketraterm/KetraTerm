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
package io.github.ketraterm.session

/**
 * One shell command line to submit after the first complete interactive prompt.
 *
 * Text is interpreted by the selected shell, without host-side expansion or quoting.
 * Use a shell script for multiline programs. Control characters are rejected so
 * submission cannot contain extra Enter keys or terminal escape sequences.
 *
 * @property text exact command line, including intentional leading/trailing spaces.
 */
data class TerminalStartupCommand(
    val text: String,
) {
    init {
        require(text.isNotBlank()) { "Startup command must not be blank" }
        require(text.length <= MAX_LENGTH) { "Startup command must not exceed $MAX_LENGTH characters" }
        require(text.none(Char::isISOControl)) { "Startup command must be one line without control characters" }
    }

    companion object {
        /** Maximum command length in UTF-16 code units. */
        const val MAX_LENGTH: Int = 16_384

        /** Returns no command for blank settings; otherwise validates and preserves [text]. */
        fun fromText(text: String): TerminalStartupCommand? = text.takeUnless(String::isBlank)?.let(::TerminalStartupCommand)
    }
}

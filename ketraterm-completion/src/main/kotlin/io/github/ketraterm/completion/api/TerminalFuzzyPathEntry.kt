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
package io.github.ketraterm.completion.api

/**
 * One host-indexed path available to fuzzy path completion.
 *
 * [path] is a shell-facing lexical path relative to the request's current
 * directory, or an absolute path when a host cannot safely relativize it. It
 * always uses forward slashes; the shared source adapts the replacement to the
 * active shell token's separator and quoting style.
 *
 * @property path non-empty lexical path without a trailing separator.
 * @property isDirectory whether this entry represents a directory.
 */
data class TerminalFuzzyPathEntry(
    val path: String,
    val isDirectory: Boolean,
) {
    init {
        require(path.isNotBlank()) { "path must not be blank" }
        require(!path.endsWith('/')) { "path must not end with '/', was $path" }
        require(!path.endsWith('\\')) { "path must not end with '\\', was $path" }
    }
}

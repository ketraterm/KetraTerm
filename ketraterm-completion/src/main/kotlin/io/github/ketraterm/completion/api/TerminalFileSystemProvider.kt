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
 * Single file system entry description returned by [TerminalFileSystemProvider].
 *
 * @property name base name of the file or directory (e.g. "src" or "README.md").
 * @property isDirectory `true` if the entry represents a directory.
 */
data class TerminalFileEntry(
    val name: String,
    val isDirectory: Boolean,
) {
    init {
        require(name.isNotEmpty()) { "name must not be empty" }
        require(name != "." && name != "..") { "name must not be dot or dot-dot self-references" }
    }
}

/**
 * Host-implemented file system provider for sandbox-aware path completion.
 *
 * Since the completions module stays pure and I/O-free, hosts must implement
 * this interface to resolve local or remote folder paths safely.
 */
fun interface TerminalFileSystemProvider {
    /**
     * Lists child entries within the folder specified by [directoryUri].
     *
     * @param directoryUri absolute directory URI (e.g., "file:///home/user/src/").
     * @return list of directory children, or an empty list if directory is missing
     * or cannot be read.
     */
    fun listDirectory(directoryUri: String): List<TerminalFileEntry>
}

/**
 * Canonicalizes a URI string to a standard file path scheme representation.
 */
fun canonicalizeDirectoryUri(uriStr: String): String =
    try {
        val uri = java.net.URI(uriStr)
        val scheme = uri.scheme
        if (scheme != null && scheme.equals("file", ignoreCase = true)) {
            "file:" + (uri.path ?: "")
        } else {
            uri.toString()
        }
    } catch (_: Exception) {
        uriStr
    }

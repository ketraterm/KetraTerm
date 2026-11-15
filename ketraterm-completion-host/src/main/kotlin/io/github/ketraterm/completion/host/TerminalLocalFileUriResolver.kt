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
package io.github.ketraterm.completion.host

import io.github.ketraterm.completion.api.ExperimentalTerminalCompletionApi
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.FileSystemNotFoundException
import java.nio.file.Path
import java.nio.file.Paths

/** Safe resolver for authoritative local `file` working-directory URIs. */
@ExperimentalTerminalCompletionApi
public object TerminalLocalFileUriResolver {
    /**
     * Resolves [value] without reinterpreting remote file authorities locally.
     *
     * Empty authorities and `localhost` are accepted. Other schemes,
     * authorities, queries, fragments, and malformed values return `null`.
     * Windows network roots hidden in the decoded path are also rejected.
     *
     * @param value candidate working-directory URI.
     * @return normalized absolute local path, or `null` when unsupported.
     */
    @JvmStatic
    public fun resolve(value: String?): Path? {
        if (value == null) return null
        return try {
            val uri = URI(value)
            if (!uri.scheme.equals("file", ignoreCase = true)) return null
            if (uri.rawQuery != null || uri.rawFragment != null) return null
            val authority = uri.authority
            if (!authority.isNullOrEmpty() && !authority.equals("localhost", ignoreCase = true)) return null
            val decodedPath = uri.path ?: return null
            // Windows accepts both separators, including percent-decoded backslashes.
            if (java.io.File.separatorChar == '\\' && decodedPath.replace('\\', '/').startsWith("//")) return null
            val localUri = URI("file", null, decodedPath, null)
            val path = Paths.get(localUri).toAbsolutePath().normalize()
            if (path.fileSystem.separator == "\\" && path.root?.toString()?.startsWith("\\\\") == true) return null
            path
        } catch (_: URISyntaxException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: FileSystemNotFoundException) {
            null
        }
    }
}

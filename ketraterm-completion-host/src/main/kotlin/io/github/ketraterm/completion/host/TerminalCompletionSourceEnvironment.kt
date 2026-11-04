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
import io.github.ketraterm.completion.api.TerminalCompletionContext
import io.github.ketraterm.completion.api.TerminalCompletionRequest
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** Selects a local source directory while preserving the original editing and learning request. */
@ExperimentalTerminalCompletionApi
public object TerminalCompletionSourceEnvironment {
    /**
     * Resolves completed root Git `-C` values in input order for local source evaluation.
     * Relative values build on the preceding directory; empty values leave it unchanged.
     * Only options before the active token in [context] participate.
     *
     * Explicit malformed or remote directory metadata returns `null`, even with an
     * absolute `-C` value. [fallbackDirectory] is considered only when metadata is absent.
     * Without directory overrides the original request is returned, leaving each
     * source's absent-directory behavior intact. No filesystem access is performed.
     *
     * @param request immutable original request; never mutated.
     * @param context context resolved from [request] using the engine's command catalog.
     * @param fallbackDirectory captured host directory for relative Git overrides without shell metadata.
     * @return source request, or `null` when local evaluation would lose directory authority.
     */
    @JvmStatic
    @JvmOverloads
    public fun resolve(
        request: TerminalCompletionRequest,
        context: TerminalCompletionContext,
        fallbackDirectory: Path? = null,
    ): TerminalCompletionRequest? {
        val suppliedDirectory = request.workingDirectoryUri
        val localDirectory = TerminalLocalFileUriResolver.resolve(suppliedDirectory)
        if (suppliedDirectory != null && localDirectory == null) return null
        val command = context.command?.takeIf { it.name == "git" } ?: return request
        val directoryOption = command.options.firstOrNull { "-C" in it.names } ?: return request
        val directoryChanges = context.precedingOptionValues(directoryOption)
        if (directoryChanges.isEmpty()) return request

        var directory = localDirectory ?: fallbackDirectory?.toAbsolutePath()?.normalize()
        try {
            for (change in directoryChanges) {
                if (change.isEmpty()) continue
                val path = Path.of(change)
                directory =
                    if (path.isAbsolute) {
                        path.normalize()
                    } else {
                        (directory ?: return null).resolve(path).normalize()
                    }
            }
        } catch (_: InvalidPathException) {
            return null
        }
        return directory?.let { request.copy(workingDirectoryUri = it.toUri().toString()) } ?: request
    }
}

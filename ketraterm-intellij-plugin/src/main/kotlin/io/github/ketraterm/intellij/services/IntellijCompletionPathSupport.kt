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
package io.github.ketraterm.intellij.services

import com.intellij.openapi.util.io.FileUtil
import io.github.ketraterm.completion.api.TerminalCompletionContext
import io.github.ketraterm.completion.api.TerminalCompletionRequest
import io.github.ketraterm.completion.host.TerminalLocalFileUriResolver
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** Uses the project fallback only when the shell supplied no working-directory metadata. */
internal fun resolveIntellijCompletionWorkingDirectory(
    workingDirectoryUri: String?,
    fallback: () -> Path?,
): Path? =
    if (workingDirectoryUri == null) {
        fallback()?.toAbsolutePath()?.normalize()
    } else {
        TerminalLocalFileUriResolver.resolve(workingDirectoryUri)
    }

/** Selects one local source environment without changing the original editing or learning context. */
internal fun resolveIntellijCompletionRequest(
    request: TerminalCompletionRequest,
    context: TerminalCompletionContext,
    projectDirectory: Path?,
): TerminalCompletionRequest? {
    val suppliedDirectory = request.workingDirectoryUri
    val localDirectory = TerminalLocalFileUriResolver.resolve(suppliedDirectory)
    if (suppliedDirectory != null && localDirectory == null) return null
    if (context.command?.name != "git") return request
    val directoryChanges = context.precedingOptionValues("-C")
    if (directoryChanges.isEmpty()) return request

    var directory = localDirectory ?: projectDirectory?.toAbsolutePath()?.normalize()
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

/** Returns a shell-facing forward-slash path relative to [base], or [target] when relativization is unavailable. */
internal fun toRelativeCompletionPath(
    base: Path,
    target: Path,
): String {
    val relative = runCatching { base.relativize(target) }.getOrElse { target }
    return FileUtil.toSystemIndependentName(relative.toString()).removeSuffix("/")
}

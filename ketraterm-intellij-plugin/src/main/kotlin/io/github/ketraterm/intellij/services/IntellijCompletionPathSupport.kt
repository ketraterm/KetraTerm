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
import io.github.ketraterm.completion.host.TerminalLocalFileUriResolver
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

/**
 * Selects the deepest containing root after absolute, lexical path normalization.
 * Equal-depth matches retain their iteration order; no filesystem access is performed.
 *
 * @param directory terminal directory whose owning root is needed.
 * @param roots candidate roots in preference order.
 * @param rootDirectory local directory of a root, or `null` when unavailable.
 * @return the most specific containing root, or `null` when no root contains [directory].
 */
internal fun <T> findContainingCompletionRoot(
    directory: Path,
    roots: Iterable<T>,
    rootDirectory: (T) -> Path?,
): T? {
    val normalizedDirectory = directory.toAbsolutePath().normalize()
    var selected: T? = null
    var selectedDepth = -1
    for (root in roots) {
        val path = rootDirectory(root)?.toAbsolutePath()?.normalize() ?: continue
        if (path.nameCount > selectedDepth && normalizedDirectory.startsWith(path)) {
            selected = root
            selectedDepth = path.nameCount
        }
    }
    return selected
}

/** Returns a shell-facing forward-slash path relative to [base], or [target] when relativization is unavailable. */
internal fun toRelativeCompletionPath(
    base: Path,
    target: Path,
): String {
    val relative = runCatching { base.relativize(target) }.getOrElse { target }
    return FileUtil.toSystemIndependentName(relative.toString()).removeSuffix("/")
}

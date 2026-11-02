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

/** Returns a shell-facing forward-slash path relative to [base], or [target] when relativization is unavailable. */
internal fun toRelativeCompletionPath(
    base: Path,
    target: Path,
): String {
    val relative = runCatching { base.relativize(target) }.getOrElse { target }
    return FileUtil.toSystemIndependentName(relative.toString()).removeSuffix("/")
}

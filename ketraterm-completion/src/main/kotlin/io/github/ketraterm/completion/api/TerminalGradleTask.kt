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
 * One imported Gradle task exposed by a host-owned, ready completion snapshot.
 *
 * [path] is the canonical Gradle task path, including its leading colon. Hosts
 * may provide [projectDirectory] relative to the terminal working directory so
 * the shared source can scope `-p` and `--project-dir` task completion without
 * accessing the filesystem.
 *
 * @property path canonical Gradle task path, such as `:app:run`.
 * @property description optional task documentation from the imported Gradle model.
 * @property projectDirectory optional normalized project directory relative to
 * the terminal working directory; the root project is represented by `.`.
 */
data class TerminalGradleTask
@JvmOverloads
constructor(
    val path: String,
    val description: String = "",
    val projectDirectory: String? = null,
) {
    init {
        require(path.startsWith(':') && path.length > 1) { "path must be a non-root Gradle task path, was $path" }
        require(path.none(Char::isWhitespace)) { "path must not contain whitespace, was $path" }
        require(projectDirectory?.isNotBlank() != false) { "projectDirectory must not be blank" }
    }
}

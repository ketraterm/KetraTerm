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
 * One imported Gradle task returned by a host completion loader.
 *
 * [path] is the canonical Gradle task path, including its leading colon. Hosts
 * provide [projectDirectory] so the shared source can select short task names
 * without accessing the filesystem. Hosts resolve directory aliases and build
 * ownership; for the selected project they preserve the completed `-p` or
 * `--project-dir` spelling, or use `.` when no override was supplied.
 * Fully qualified task paths remain available throughout the selected build.
 *
 * @property path canonical Gradle task path, such as `:app:run`.
 * @property description optional task documentation from the imported Gradle model.
 * @property projectDirectory optional directory-selector spelling for this task's project;
 * `.` identifies the current project without an override. Other projects may use paths
 * relative to the terminal directory. When absent, only root tasks receive short names.
 * @throws IllegalArgumentException if [path] is not a canonical non-root Gradle
 * task path or [projectDirectory] is present but blank.
 */
@ExperimentalTerminalCompletionApi
public data class TerminalGradleTask
    @JvmOverloads
    constructor(
        val path: String,
        val description: String = "",
        val projectDirectory: String? = null,
    ) {
        init {
            require(path.startsWith(':') && path.length > 1) { "path must be a non-root Gradle task path, was $path" }
            require(path.none(Char::isWhitespace)) { "path must not contain whitespace, was $path" }
            require(!path.endsWith(':') && "::" !in path) { "path must not contain empty Gradle path segments, was $path" }
            require(projectDirectory?.isNotBlank() != false) { "projectDirectory must not be blank" }
        }
    }

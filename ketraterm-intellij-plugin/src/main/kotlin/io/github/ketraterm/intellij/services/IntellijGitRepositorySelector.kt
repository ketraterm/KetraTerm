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

import git4idea.repo.GitRepository
import java.nio.file.Path

/** Selects the deepest Git repository containing a terminal working directory. */
internal fun selectIntellijGitRepository(
    repositories: List<GitRepository>,
    workingDirectory: Path,
): GitRepository? =
    repositories
        .asSequence()
        .filter { repository -> workingDirectory.startsWith(repository.root.toNioPath()) }
        .maxByOrNull { repository -> repository.root.path.length }

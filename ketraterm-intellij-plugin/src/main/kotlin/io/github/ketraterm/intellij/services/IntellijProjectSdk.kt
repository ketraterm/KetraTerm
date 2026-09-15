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

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.findDirectory
import com.intellij.openapi.vfs.toNioPathOrNull
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * Resolves the project JDK bin directory for a new terminal process.
 *
 * Matches the reworked IntelliJ terminal's JavaSDK type and VFS-first lookup,
 * with an additional fallback to no injection for malformed configured paths.
 * Call before acquiring workspace locks so project disposal cannot wait for a
 * workspace lock held by a caller waiting for IDE read access.
 */
internal fun projectSdkBinPath(project: Project): Path? =
    runReadActionBlocking {
        if (project.isDisposed) return@runReadActionBlocking null
        val sdk = ProjectRootManager.getInstance(project).projectSdk ?: return@runReadActionBlocking null
        if (sdk.sdkType.name != "JavaSDK") return@runReadActionBlocking null

        try {
            sdk.homeDirectory?.findDirectory("bin")?.toNioPathOrNull()
                ?: sdk.homePath?.let { Path.of(it, "bin") }
        } catch (_: InvalidPathException) {
            null
        }
    }

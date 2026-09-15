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

import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import io.github.ketraterm.completion.host.TerminalLocalFileUriResolver
import io.github.ketraterm.intellij.settings.KetraTermIntellijSettings
import io.github.ketraterm.workspace.TerminalProfile
import io.github.ketraterm.workspace.TerminalProfileRegistry
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** Rebuilds saved tabs using current shell settings and host-local working directories. */
internal object TerminalTabRestore {
    /**
     * Resolves a tab's current launch profile and an existing absolute working directory.
     *
     * Shell discovery and directory checks perform filesystem I/O; callers must run this off the EDT.
     * Saved tabs contain profile identities rather than commands or environment snapshots.
     */
    @RequiresBackgroundThread
    fun profile(
        basePath: String?,
        state: TerminalTabState,
        settings: KetraTermIntellijSettings.State,
        profiles: List<TerminalProfile> = TerminalProfileRegistry().availableProfiles(),
    ): TerminalProfile {
        val selectedProfile = profiles.firstOrNull { it.id == state.profileId }
        val profile =
            if (selectedProfile == null) {
                KetraTermDefaultProfileFactory.defaultProfile(basePath, settings)
            } else {
                KetraTermDefaultProfileFactory.profileForSelectedShell(basePath, selectedProfile, settings)
            }
        val workingDirectory =
            existingDirectory(state.workingDirectory)
                ?: existingDirectory(profile.workingDirectory)
                ?: existingDirectory(basePath)
                ?: Path.of(System.getProperty("user.home"))
        return profile.copy(workingDirectory = workingDirectory)
    }

    /** Captures tab identity and the latest local cwd without probing the filesystem. */
    fun snapshot(
        profile: TerminalProfile,
        customTitle: String?,
        currentWorkingDirectoryUri: String?,
    ): TerminalTabState =
        TerminalTabState(
            profileId = profile.id,
            customTitle = customTitle,
            workingDirectory =
                (TerminalLocalFileUriResolver.resolve(currentWorkingDirectoryUri) ?: profile.workingDirectory)?.toString(),
        )

    private fun existingDirectory(value: String?): Path? {
        if (value == null) return null
        val path =
            try {
                Path.of(value)
            } catch (_: InvalidPathException) {
                return null
            }
        return existingDirectory(path)
    }

    private fun existingDirectory(path: Path?): Path? = path?.takeIf { it.isAbsolute && Files.isDirectory(it) }
}

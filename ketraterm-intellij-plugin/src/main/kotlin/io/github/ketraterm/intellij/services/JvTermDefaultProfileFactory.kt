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

import com.intellij.openapi.project.Project
import io.github.ketraterm.intellij.settings.JvTermIntellijSettings
import io.github.ketraterm.intellij.settings.JvTermIntellijSettingsNormalizer
import io.github.ketraterm.workspace.TerminalProfile
import io.github.ketraterm.workspace.TerminalProfileRegistry
import java.nio.file.Path

/**
 * Creates launch profiles for IntelliJ-hosted local terminal tabs.
 */
internal object JvTermDefaultProfileFactory {
    /**
     * Creates the default profile for [project].
     *
     * The shell command comes from the host-neutral profile registry. The
     * IntelliJ-specific contribution is only the initial working directory.
     *
     * @param project current IntelliJ project.
     * @return local terminal launch profile.
     */
    fun defaultProfile(
        project: Project,
        settings: JvTermIntellijSettings.State = JvTermIntellijSettings.getInstance().state,
    ): TerminalProfile = defaultProfile(project.basePath, settings)

    /**
     * Creates a default profile for a nullable project path.
     *
     * @param basePath project base path, or `null` when the IDE has no local project path.
     * @param settings persisted IntelliJ terminal settings.
     * @return local terminal launch profile.
     */
    fun defaultProfile(
        basePath: String?,
        settings: JvTermIntellijSettings.State = JvTermIntellijSettings.State(),
    ): TerminalProfile {
        val normalized = JvTermIntellijSettingsNormalizer.normalize(settings)
        val workingDirectory = workingDirectory(basePath, normalized.startDirectory)
        return TerminalProfileRegistry()
            .configuredProfile(normalized.shellPath, workingDirectory)
            .copy(
                displayName = normalized.defaultTabName,
                environment = JvTermIntellijSettingsNormalizer.parseEnvironmentVariables(normalized.environmentVariables),
            )
    }

    /**
     * Applies IntelliJ launch settings to a shell profile selected from the shared registry.
     *
     * @param project current IntelliJ project.
     * @param profile selected discovered shell profile.
     * @param settings persisted IntelliJ terminal settings.
     * @return launch profile with IDE working directory and environment settings applied.
     */
    fun profileForSelectedShell(
        project: Project,
        profile: TerminalProfile,
        settings: JvTermIntellijSettings.State = JvTermIntellijSettings.getInstance().state,
    ): TerminalProfile = profileForSelectedShell(project.basePath, profile, settings)

    /**
     * Applies IntelliJ launch settings to a selected shell profile.
     *
     * @param basePath project base path, or `null` when the IDE has no local project path.
     * @param profile selected discovered shell profile.
     * @param settings persisted IntelliJ terminal settings.
     * @return launch profile with IDE working directory and environment settings applied.
     */
    fun profileForSelectedShell(
        basePath: String?,
        profile: TerminalProfile,
        settings: JvTermIntellijSettings.State = JvTermIntellijSettings.State(),
    ): TerminalProfile {
        val normalized = JvTermIntellijSettingsNormalizer.normalize(settings)
        return profile.copy(
            workingDirectory = workingDirectory(basePath, normalized.startDirectory),
            environment = JvTermIntellijSettingsNormalizer.parseEnvironmentVariables(normalized.environmentVariables),
        )
    }

    private fun workingDirectory(
        basePath: String?,
        configuredStartDirectory: String,
    ): Path {
        if (configuredStartDirectory.isNotBlank()) {
            return pathOrUserHome(configuredStartDirectory)
        }
        return if (basePath.isNullOrBlank()) {
            userHome()
        } else {
            pathOrUserHome(basePath)
        }
    }

    private fun pathOrUserHome(path: String): Path =
        try {
            Path.of(path)
        } catch (_: RuntimeException) {
            userHome()
        }

    private fun userHome(): Path = Path.of(System.getProperty("user.home"))
}

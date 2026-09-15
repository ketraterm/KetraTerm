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

import io.github.ketraterm.intellij.settings.KetraTermIntellijSettings
import io.github.ketraterm.workspace.TerminalProfile
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class TerminalTabRestoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `restores saved directory before configured and project directories`() {
        val savedDirectory = directory(" leading space café 日本語")
        val configuredDirectory = directory("configured")
        val projectDirectory = directory("project")

        val profile =
            TerminalTabRestore.profile(
                projectDirectory.toString(),
                TerminalTabState(workingDirectory = savedDirectory.toString()),
                settings(startDirectory = configuredDirectory.toString()),
                profiles = emptyList(),
            )

        assertEquals(savedDirectory, profile.workingDirectory)
    }

    @Test
    fun `removed file malformed and relative saved directories fall back to configured directory`() {
        val removedDirectory = directory("removed")
        Files.delete(removedDirectory)
        val file = temporaryFolder.newFile("file").toPath()
        val configuredDirectory = directory("configured")
        val existingRelativeDirectory = Path.of("")
        val invalidDirectories =
            listOf(removedDirectory.toString(), file.toString(), "invalid\u0000path", existingRelativeDirectory.toString())

        for (savedDirectory in invalidDirectories) {
            val profile =
                TerminalTabRestore.profile(
                    directoryPath("project").toString(),
                    TerminalTabState(workingDirectory = savedDirectory),
                    settings(startDirectory = configuredDirectory.toString()),
                    profiles = emptyList(),
                )

            assertEquals("Saved directory: $savedDirectory", configuredDirectory, profile.workingDirectory)
        }
    }

    @Test
    fun `invalid configured directory falls back to project directory`() {
        val projectDirectory = directory("project")
        val invalidDirectories =
            listOf(directoryPath("missing").toString(), temporaryFolder.newFile("file").toString(), ".")

        for (configuredDirectory in invalidDirectories) {
            val profile =
                TerminalTabRestore.profile(
                    projectDirectory.toString(),
                    TerminalTabState(workingDirectory = directoryPath("removed").toString()),
                    settings(startDirectory = configuredDirectory),
                    profiles = emptyList(),
                )

            assertEquals("Configured directory: $configuredDirectory", projectDirectory, profile.workingDirectory)
        }
    }

    @Test
    fun `missing invalid and relative project paths fall back to user home`() {
        for (basePath in listOf(null, directoryPath("missing").toString(), "invalid\u0000path", ".")) {
            val profile =
                TerminalTabRestore.profile(
                    basePath,
                    TerminalTabState(),
                    settings(),
                    profiles = emptyList(),
                )

            assertEquals(Path.of(System.getProperty("user.home")), profile.workingDirectory)
        }
    }

    @Test
    fun `default configured and unavailable profiles use current default shell settings`() {
        val projectDirectory = directory("project")
        val currentSettings = settings(environmentVariables = "CURRENT=value\nEMPTY=")
        val unavailableProfile = selectedProfile("different-id")

        for (profileId in listOf(null, "configured-shell", "removed-profile")) {
            val profile =
                TerminalTabRestore.profile(
                    projectDirectory.toString(),
                    TerminalTabState(profileId = profileId),
                    currentSettings,
                    profiles = listOf(unavailableProfile),
                )

            assertEquals("configured-shell", profile.id)
            assertEquals(listOf("current-default-shell"), profile.command)
            assertEquals("Current Default", profile.displayName)
            assertEquals(mapOf("CURRENT" to "value", "EMPTY" to ""), profile.environment)
            assertEquals(projectDirectory, profile.workingDirectory)
        }
    }

    @Test
    fun `selected profile keeps current shell command and receives current environment`() {
        val savedDirectory = directory("saved")
        val selected = selectedProfile()

        val profile =
            TerminalTabRestore.profile(
                directoryPath("project").toString(),
                TerminalTabState(profileId = selected.id, workingDirectory = savedDirectory.toString()),
                settings(environmentVariables = "CURRENT=value"),
                profiles = listOf(selected),
            )

        assertEquals(selected.id, profile.id)
        assertEquals(selected.command, profile.command)
        assertEquals(selected.displayName, profile.displayName)
        assertEquals(mapOf("CURRENT" to "value"), profile.environment)
        assertEquals(savedDirectory, profile.workingDirectory)
    }

    @Test
    fun `snapshot preserves custom title and decoded local cwd with spaces and Unicode`() {
        val currentDirectory = directory(" leading space café 日本語")
        val profile = selectedProfile().copy(workingDirectory = directory("launch"))

        val state = TerminalTabRestore.snapshot(profile, "My custom tab", currentDirectory.toUri().toString())

        assertEquals(profile.id, state.profileId)
        assertEquals("My custom tab", state.customTitle)
        assertEquals(currentDirectory.toString(), state.workingDirectory)
    }

    @Test
    fun `snapshot accepts localhost authority without filesystem access`() {
        val reportedDirectory = directoryPath("not-created")
        val uri = reportedDirectory.toUri().toString().replace("file:///", "file://localhost/")

        val state = TerminalTabRestore.snapshot(selectedProfile(), null, uri)

        assertFalse(Files.exists(reportedDirectory))
        assertEquals(reportedDirectory.toString(), state.workingDirectory)
        assertNull(state.customTitle)
    }

    @Test
    fun `remote malformed and absent reported directories retain launch directory`() {
        val launchDirectory = directory("launch")
        val profile = selectedProfile().copy(workingDirectory = launchDirectory)
        val unsupportedUris = listOf(null, "file://remote-server/home/user", "https://localhost/path", "file:relative", "file:///bad%zz")

        for (uri in unsupportedUris) {
            val state = TerminalTabRestore.snapshot(profile, null, uri)

            assertEquals("URI: $uri", launchDirectory.toString(), state.workingDirectory)
        }
    }

    @Test
    fun `snapshot keeps an unknown directory absent`() {
        val state = TerminalTabRestore.snapshot(selectedProfile(), null, null)

        assertNull(state.workingDirectory)
    }

    private fun directory(name: String): Path = temporaryFolder.newFolder(name).toPath()

    private fun directoryPath(name: String): Path = temporaryFolder.root.toPath().resolve(name)

    private fun settings(
        startDirectory: String = "",
        environmentVariables: String = "",
    ): KetraTermIntellijSettings.State =
        KetraTermIntellijSettings.State(
            shellPath = "current-default-shell",
            startDirectory = startDirectory,
            environmentVariables = environmentVariables,
            defaultTabName = "Current Default",
        )

    private fun selectedProfile(id: String = "selected-shell"): TerminalProfile =
        TerminalProfile(
            id = id,
            displayName = "Selected Shell",
            command = listOf("current-selected-shell", "--login"),
            environment = mapOf("OUTDATED" to "discarded"),
        )
}

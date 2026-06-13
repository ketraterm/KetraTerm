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
package io.github.jvterm.app.ui

import io.github.jvterm.app.config.JvTermSettings
import io.github.jvterm.workspace.TerminalProfileRegistry
import io.github.jvterm.workspace.config.TerminalWorkspaceConfigManager
import java.nio.file.Files
import kotlin.test.*

class SettingsModelTest {
    private lateinit var tempFile: java.nio.file.Path
    private lateinit var settings: JvTermSettings
    private lateinit var registry: TerminalProfileRegistry
    private lateinit var model: SettingsModel

    @BeforeTest
    fun setUp() {
        tempFile = Files.createTempFile("jvterm-settings-test", ".toml")
        val manager = TerminalWorkspaceConfigManager(tempFile)
        settings = JvTermSettings(manager)
        registry = TerminalProfileRegistry(executableExists = { false })
        model = SettingsModel(settings, registry)
    }

    @AfterTest
    fun tearDown() {
        Files.deleteIfExists(tempFile)
    }

    @Test
    fun testInitialStateMatchesSettings() {
        val state = model.getSettingsState()
        assertEquals(settings.theme.name, state.theme)
        assertEquals(settings.fontSize, state.fontSize)
        assertEquals(settings.columns, state.columns)
        assertEquals(settings.shellPath, state.shellPath)
        assertEquals(settings.shellRequestResizeWindow, state.shellRequestResizeWindow)
        assertFalse(model.hasChanges(state))
    }

    @Test
    fun testHasChangesWhenUiStateIsModified() {
        val state = model.getSettingsState()
        assertFalse(model.hasChanges(state))

        // Modify a field
        val modifiedState = state.copy(fontSize = state.fontSize + 2)
        assertTrue(model.hasChanges(modifiedState))

        // Revert it back
        val revertedState = modifiedState.copy(fontSize = state.fontSize)
        assertFalse(model.hasChanges(revertedState))
    }

    @Test
    fun testApplyChangesSavesToSettingsAndUpdatesSnapshot() {
        val state = model.getSettingsState()
        val modifiedState = state.copy(fontSize = 22, columns = 120, shellRequestResizeWindow = true)

        assertTrue(model.hasChanges(modifiedState))

        var applied = false
        model.applyChanges(modifiedState) {
            applied = true
        }

        assertTrue(applied)
        assertEquals(22, settings.fontSize)
        assertEquals(120, settings.columns)
        assertTrue(settings.shellRequestResizeWindow)

        // Snapshot should be updated, so it shouldn't show changes against modified state anymore
        assertFalse(model.hasChanges(modifiedState))
    }
}

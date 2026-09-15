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

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.State
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.xmlb.XmlSerializer
import org.jdom.Element
import java.util.concurrent.TimeUnit

/** Exercises the platform XML serializer and immutable snapshots without launching a shell. */
class KetraTermTerminalTabsStorageTest : BasePlatformTestCase() {
    fun testTabsUseTheProjectWorkspaceFile() {
        val component = checkNotNull(KetraTermTerminalTabsStorage::class.java.getAnnotation(State::class.java))

        assertEquals(StoragePathMacros.WORKSPACE_FILE, component.storages.single().value)
    }

    fun testEmptyWorkspaceAndMissingFieldsUseDefaults() {
        val storage = KetraTermTerminalTabsStorage()

        assertEquals(TerminalTabsState(), storage.state)
        storage.loadState(XmlSerializer.deserialize(Element("TerminalTabsState"), TerminalTabsState::class.java))

        assertEquals(TerminalTabsState(), storage.state)
        assertEquals(0L, storage.stateModificationCount)
    }

    fun testPlatformXmlRoundTripPreservesOrderSelectionAndOptionalMetadata() {
        val expected =
            TerminalTabsState(
                tabs =
                    listOf(
                        TerminalTabState("powershell", "Build <&> \"tests\"", "C:\\work\\KetraTerm"),
                        TerminalTabState("bash", null, "/work/հայերեն project"),
                        TerminalTabState(),
                    ),
                selectedTabIndex = 1,
            )
        val storage = KetraTermTerminalTabsStorage()
        storage.replace(expected)

        val serialized = XmlSerializer.serialize(storage.state)
        val restored = KetraTermTerminalTabsStorage()
        restored.loadState(XmlSerializer.deserialize(serialized, TerminalTabsState::class.java))

        assertEquals(expected, restored.state)
        assertEquals(0L, restored.stateModificationCount)
    }

    fun testLoadedAndReplacedStatesOwnTheirTabLists() {
        val tab = TerminalTabState(customTitle = "Build")
        val loadedTabs = mutableListOf(tab)
        val storage = KetraTermTerminalTabsStorage()
        storage.loadState(TerminalTabsState(loadedTabs, 0))
        val loadedSnapshot = storage.state

        loadedTabs.clear()

        assertEquals(listOf(tab), loadedSnapshot.tabs)
        val replacementTabs = mutableListOf(tab.copy(customTitle = "Tests"))
        storage.replace(TerminalTabsState(replacementTabs, 0))
        val replacementSnapshot = storage.state
        replacementTabs.clear()

        assertEquals(listOf(tab), loadedSnapshot.tabs)
        assertEquals(listOf(tab.copy(customTitle = "Tests")), replacementSnapshot.tabs)
        assertThrows(UnsupportedOperationException::class.java) {
            (replacementSnapshot.tabs as MutableList<TerminalTabState>).clear()
        }
    }

    fun testSelectionBoundsNormalizeAtLoadAndReplacement() {
        val tabs = listOf(TerminalTabState(customTitle = "First"), TerminalTabState(customTitle = "Second"))
        val storage = KetraTermTerminalTabsStorage()

        for (invalidIndex in listOf(Int.MIN_VALUE, -1, tabs.size, Int.MAX_VALUE)) {
            storage.loadState(TerminalTabsState(tabs, invalidIndex))
            assertEquals(0, storage.state.selectedTabIndex)
            storage.replace(TerminalTabsState(tabs, invalidIndex))
            assertEquals(0, storage.state.selectedTabIndex)
        }
        storage.replace(TerminalTabsState(tabs, 1))
        assertEquals(1, storage.state.selectedTabIndex)
        storage.loadState(TerminalTabsState(selectedTabIndex = Int.MAX_VALUE))
        assertEquals(TerminalTabsState(), storage.state)
        storage.replace(TerminalTabsState(selectedTabIndex = 0))
        assertEquals(TerminalTabsState(), storage.state)
    }

    fun testEquivalentCapturesDoNotMarkTheWorkspaceModified() {
        val state = TerminalTabsState(listOf(TerminalTabState(customTitle = "Build")), 0)
        val storage = KetraTermTerminalTabsStorage()
        storage.loadState(state)

        repeat(3) { storage.replace(state.copy(tabs = state.tabs.toList())) }

        assertEquals(0L, storage.stateModificationCount)
        storage.replace(state.copy(tabs = listOf(state.tabs.single().copy(customTitle = "Tests"))))
        assertEquals(1L, storage.stateModificationCount)
    }

    fun testBackgroundSavesCanReadTheSameSnapshotWithoutInitializingTerminalUi() {
        val storage = KetraTermTerminalTabsStorage()
        val expected = TerminalTabsState(listOf(TerminalTabState(workingDirectory = "/work/project")), 0)
        storage.loadState(expected)
        val snapshot = storage.state

        ApplicationManager
            .getApplication()
            .executeOnPooledThread {
                repeat(10) { assertSame(snapshot, storage.state) }
            }.get(10, TimeUnit.SECONDS)

        assertEquals(expected, storage.state)
        assertEquals(0L, storage.stateModificationCount)
    }
}

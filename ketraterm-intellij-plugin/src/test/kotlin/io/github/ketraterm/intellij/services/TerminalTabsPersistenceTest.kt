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

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.content.ContentManager
import javax.swing.JPanel

/** Verifies persistence against real IntelliJ content ordering, selection and disposal events. */
class TerminalTabsPersistenceTest : BasePlatformTestCase() {
    fun testInstallationAndDisposalBeforeInitializationPreserveLoadedState() {
        val expected = TerminalTabsState(listOf(TerminalTabState(customTitle = "Restored")), 0)
        val storage = KetraTermTerminalTabsStorage()
        storage.loadState(expected)

        repeat(3) {
            val persistence = TerminalTabsPersistence(storage, contentManager(), testRootDisposable, ::snapshot)
            assertEquals(expected, storage.state)
            persistence.dispose()
        }

        assertEquals(expected, storage.state)
        assertEquals(0L, storage.stateModificationCount)
    }

    fun testExplicitInitialCaptureUsesVisualOrderAndSelection() {
        val manager = contentManager()
        val first = addTab(manager, "First")
        val last = addTab(manager, "Last")
        val middle = newContent("Middle")
        manager.addContent(middle, 1)
        manager.setSelectedContent(last, false)
        val storage = KetraTermTerminalTabsStorage()
        val persistence = TerminalTabsPersistence(storage, manager, testRootDisposable, ::snapshot)

        persistence.capture()

        assertEquals(listOf(snapshot(first), snapshot(middle), snapshot(last)), storage.state.tabs)
        assertEquals(2, storage.state.selectedTabIndex)
    }

    fun testAddSelectAndClosePublishCurrentTabs() {
        val manager = contentManager()
        val storage = KetraTermTerminalTabsStorage()
        TerminalTabsPersistence(storage, manager, testRootDisposable, ::snapshot)

        val first = addTab(manager, "First")
        val second = addTab(manager, "Second")
        manager.setSelectedContent(second, false)

        assertEquals(listOf(snapshot(first), snapshot(second)), storage.state.tabs)
        assertEquals(1, storage.state.selectedTabIndex)

        manager.removeContent(first, true)

        assertEquals(listOf(snapshot(second)), storage.state.tabs)
        assertEquals(0, storage.state.selectedTabIndex)

        manager.removeContent(second, true)

        assertEquals(TerminalTabsState(), storage.state)
    }

    fun testUntrackedContentsDoNotShiftTheSavedSelection() {
        val manager = contentManager()
        val ignored = addTab(manager, "Other content")
        val first = addTab(manager, "First")
        val second = addTab(manager, "Second")
        val storage = KetraTermTerminalTabsStorage()
        val persistence =
            TerminalTabsPersistence(storage, manager, testRootDisposable) { content ->
                if (content === ignored) null else snapshot(content)
            }
        manager.setSelectedContent(second, false)

        persistence.capture()

        assertEquals(listOf(snapshot(first), snapshot(second)), storage.state.tabs)
        assertEquals(1, storage.state.selectedTabIndex)

        manager.setSelectedContent(ignored, false)

        assertEquals(0, storage.state.selectedTabIndex)
    }

    fun testExplicitCaptureRefreshesMetadataWithoutMutatingThePreviousSnapshot() {
        val manager = contentManager()
        val content = addTab(manager, "First")
        val storage = KetraTermTerminalTabsStorage()
        var directory = "/initial"
        val persistence =
            TerminalTabsPersistence(storage, manager, testRootDisposable) {
                TerminalTabState(customTitle = it.displayName, workingDirectory = directory)
            }
        persistence.capture()
        val previous = storage.state

        content.displayName = "Renamed"
        directory = "/changed"
        persistence.capture()

        assertEquals(TerminalTabState(customTitle = "First", workingDirectory = "/initial"), previous.tabs.single())
        assertEquals(TerminalTabState(customTitle = "Renamed", workingDirectory = "/changed"), storage.state.tabs.single())
    }

    fun testDisposalFreezesTheSnapshotBeforeContentTeardown() {
        val manager = contentManager()
        val storage = KetraTermTerminalTabsStorage()
        val parent = Disposer.newDisposable(testRootDisposable, "terminal persistence test")
        var snapshots = 0
        val persistence =
            TerminalTabsPersistence(storage, manager, parent) {
                snapshots++
                snapshot(it)
            }
        addTab(manager, "First")
        addTab(manager, "Second")
        val saved = storage.state
        val capturesBeforeDisposal = snapshots

        Disposer.dispose(parent)
        persistence.dispose()
        manager.removeAllContents(true)
        addTab(manager, "Teardown replacement")
        persistence.capture()

        assertSame(saved, storage.state)
        assertEquals(capturesBeforeDisposal, snapshots)
    }

    private fun contentManager(): ContentManager =
        ContentFactory.getInstance().createContentManager(true, project).also {
            Disposer.register(testRootDisposable, it)
        }

    private fun addTab(
        manager: ContentManager,
        title: String,
    ): Content = newContent(title).also(manager::addContent)

    private fun newContent(title: String): Content = ContentFactory.getInstance().createContent(JPanel(), title, false)

    private fun snapshot(content: Content): TerminalTabState = TerminalTabState(customTitle = content.displayName)
}

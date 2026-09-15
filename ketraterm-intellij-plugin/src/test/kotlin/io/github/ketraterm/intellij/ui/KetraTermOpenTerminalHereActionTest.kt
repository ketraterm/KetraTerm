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
package io.github.ketraterm.intellij.ui

import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.JarFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.fixtures.TempDirTestFixture
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Verifies IDE file contexts and registered menus without opening a PTY or IDE window. */
class KetraTermOpenTerminalHereActionTest : BasePlatformTestCase() {
    override fun createTempDirTestFixture(): TempDirTestFixture = TempDirTestFixtureImpl()

    fun testDirectorySelectionUsesThatDirectory() {
        val directory = myFixture.tempDirFixture.findOrCreateDir("selected/nested")

        assertSame(directory, terminalContextDirectory(directory))
        assertAvailable(event(directory, ActionPlaces.PROJECT_VIEW_POPUP))
    }

    fun testFileSelectionUsesContainingDirectoryInEditorAndTabPopups() {
        val file = myFixture.tempDirFixture.createFile("selected/nested/source.kt")

        assertSame(file.parent, terminalContextDirectory(file))
        assertAvailable(event(file, ActionPlaces.EDITOR_POPUP))
        assertAvailable(event(file, ActionPlaces.EDITOR_TAB_POPUP))
    }

    fun testLocalDirectoryRemainsAvailableDuringIndexing() {
        val directory = myFixture.tempDirFixture.findOrCreateDir("indexing")

        DumbModeTestUtils.runInDumbModeSynchronously(project) {
            assertTrue(DumbService.isDumbAware(action()))
            assertAvailable(event(directory, ActionPlaces.PROJECT_VIEW_POPUP))
        }
    }

    fun testMissingSelectionIsHiddenInFilePopups() {
        assertNull(terminalContextDirectory(null))
        assertUnavailable(event(null, ActionPlaces.PROJECT_VIEW_POPUP))
        assertUnavailable(event(null, ActionPlaces.EDITOR_POPUP))
    }

    fun testMissingAndDefaultProjectAreHiddenEvenWithLocalSelection() {
        val directory = myFixture.tempDirFixture.findOrCreateDir("project-required")

        assertUnavailable(event(directory, contextProject = null))
        assertUnavailable(event(directory, contextProject = ProjectManager.getInstance().defaultProject))
    }

    fun testDeletedFileDoesNotFallBackToItsSurvivingParent() {
        val file = myFixture.tempDirFixture.createFile("deleted/source.kt")
        val parent = file.parent
        runWriteAction { file.delete(this) }

        assertTrue(parent.isValid)
        assertNull(terminalContextDirectory(file))
        assertUnavailable(event(file))
    }

    fun testInMemoryFileIsHidden() {
        val file = LightVirtualFile("scratch.kt", "val answer = 42")

        assertNull(terminalContextDirectory(file))
        assertUnavailable(event(file, ActionPlaces.EDITOR_POPUP))
    }

    fun testArchiveEntriesAndArchiveRootAreHidden() {
        val archive = myFixture.tempDirFixture.createFile("sources.jar")
        ZipOutputStream(Files.newOutputStream(archive.toNioPath())).use { zip ->
            zip.putNextEntry(ZipEntry("source.kt"))
            zip.write("val answer = 42".toByteArray())
            zip.closeEntry()
        }
        archive.refresh(false, false)
        val root = requireNotNull(JarFileSystem.getInstance().getJarRootForLocalFile(archive))
        val entry = requireNotNull(root.findChild("source.kt"))

        assertNull(terminalContextDirectory(root))
        assertNull(terminalContextDirectory(entry))
        assertUnavailable(event(root))
        assertUnavailable(event(entry, ActionPlaces.EDITOR_POPUP))
    }

    fun testTerminalPopupKeepsItsDirectoryActionLabel() {
        val event = event(null, KetraTermTerminalActionIds.CONTEXT_MENU_PLACE)

        assertTrue("Action update should complete", ActionUtil.updateAction(action(), event).isPerformed)

        assertEquals("Open Terminal Here", event.presentation.text)
        assertFalse(event.presentation.isEnabled)
    }

    fun testRegisteredActionIsReachableExactlyOnceFromEveryFilePopup() {
        val manager = ActionManager.getInstance()
        val action = action()
        for (groupId in listOf("ProjectViewPopupMenu", "EditorPopupMenu", "EditorTabPopupMenu", "RevealGroup")) {
            val group = requireNotNull(manager.getAction(groupId))
            assertEquals(groupId, 1, occurrences(group, action, manager))
        }
    }

    private fun action(): AnAction = requireNotNull(ActionManager.getInstance().getAction(KetraTermTerminalActionIds.OPEN_TERMINAL_HERE))

    private fun event(
        file: VirtualFile?,
        place: String = ActionPlaces.PROJECT_VIEW_POPUP,
        contextProject: Project? = project,
    ): AnActionEvent {
        val context =
            SimpleDataContext
                .builder()
                .add(CommonDataKeys.PROJECT, contextProject)
                .add(CommonDataKeys.VIRTUAL_FILE, file)
                .build()
        return AnActionEvent.createEvent(context, action().templatePresentation.clone(), place, ActionUiKind.POPUP, null)
    }

    private fun assertAvailable(event: AnActionEvent) {
        assertTrue("Action update should complete", ActionUtil.updateAction(action(), event).isPerformed)

        assertTrue(event.presentation.isVisible)
        assertTrue(event.presentation.isEnabled)
        assertEquals("Open in KetraTerm", event.presentation.text)
    }

    private fun assertUnavailable(event: AnActionEvent) {
        assertTrue("Action update should complete", ActionUtil.updateAction(action(), event).isPerformed)

        assertFalse(event.presentation.isVisible)
        assertFalse(event.presentation.isEnabled)
    }

    private fun occurrences(
        current: AnAction,
        target: AnAction,
        manager: ActionManager,
        ancestors: Set<AnAction> = emptySet(),
    ): Int {
        if (current === target) return 1
        if (current !is DefaultActionGroup || current in ancestors) return 0
        val path = ancestors + current
        return current.getChildren(manager).sumOf { child -> occurrences(child, target, manager, path) }
    }
}

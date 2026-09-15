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
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Verifies the registered rename action cannot target unrelated IDE focus. */
class KetraTermRenameTabActionTest : BasePlatformTestCase() {
    fun testRenameActionIsRegisteredAndDisabledOutsideTerminal() {
        val action = requireNotNull(ActionManager.getInstance().getAction(KetraTermTerminalActionIds.RENAME_TAB))
        assertTrue(action is KetraTermRenameTabAction)
        assertEquals("Rename Tab...", action.templatePresentation.text)
        assertEquals(ActionUpdateThread.EDT, action.actionUpdateThread)
        val context = SimpleDataContext.getProjectContext(project)
        val event =
            AnActionEvent.createEvent(
                context,
                action.templatePresentation.clone(),
                ActionPlaces.MAIN_MENU,
                ActionUiKind.MAIN_MENU,
                null,
            )

        assertTrue("Action update should complete", ActionUtil.updateAction(action, event).isPerformed)

        assertFalse(event.presentation.isEnabled)
    }

    fun testTerminalPopupWithoutPaneDoesNotEnableRename() {
        val action = requireNotNull(ActionManager.getInstance().getAction(KetraTermTerminalActionIds.RENAME_TAB))
        val context = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).build()
        val event =
            AnActionEvent.createEvent(
                context,
                action.templatePresentation.clone(),
                KetraTermTerminalActionIds.CONTEXT_MENU_PLACE,
                ActionUiKind.POPUP,
                null,
            )

        assertTrue("Action update should complete", ActionUtil.updateAction(action, event).isPerformed)

        assertFalse(event.presentation.isEnabled)
    }
}

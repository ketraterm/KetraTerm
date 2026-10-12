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
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.keymap.ex.KeymapManagerEx
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.ketraterm.ui.swing.api.SwingTerminal
import io.github.ketraterm.ui.swing.host.SwingTerminalSearchAction
import io.github.ketraterm.ui.swing.host.SwingTerminalSearchBar
import java.awt.Component
import java.awt.Container
import javax.swing.JComponent
import javax.swing.JTextField
import javax.swing.JToggleButton
import javax.swing.KeyStroke

class KetraTermSearchActionsTest : BasePlatformTestCase() {
    fun testSearchActionsAreScopedToOpenBarAndFollowLiveKeymapIncludingChords() {
        val manager = KeymapManager.getInstance() as KeymapManagerEx
        val original = manager.activeKeymap
        val custom = original.deriveKeymap("KetraTerm search test")
        val terminal = SwingTerminal()
        val bar = SwingTerminalSearchBar(terminal, IntellijSwingHostMessages)
        try {
            manager.activeKeymap = custom
            KetraTermSearchActions.install(bar)
            val field = descendants(bar.component).filterIsInstance<JTextField>().single()
            for (searchAction in SwingTerminalSearchAction.entries) {
                val action = requireNotNull(ActionManager.getInstance().getAction(KetraTermSearchActions.id(searchAction)))
                assertFalse(action.templatePresentation.text.isNullOrBlank())
                val outside = event(action, terminal)
                assertTrue(ActionUtil.updateAction(action, outside).isPerformed)
                assertFalse(outside.presentation.isEnabled)
                val closed = event(action, field)
                assertTrue(ActionUtil.updateAction(action, closed).isPerformed)
                assertFalse(closed.presentation.isEnabled)
            }
            bar.open()
            val id = KetraTermSearchActions.id(SwingTerminalSearchAction.TOGGLE_REGEX)
            val action = requireNotNull(ActionManager.getInstance().getAction(id))
            val toggle = descendants(bar.component).filterIsInstance<JToggleButton>().single { it.text == ".*" }
            val replacement = KeyboardShortcut(KeyStroke.getKeyStroke("ctrl F8"), KeyStroke.getKeyStroke("R"))
            custom.removeAllActionShortcuts(id)
            custom.addShortcut(id, replacement)
            assertEquals(listOf(replacement), action.shortcutSet.shortcuts.toList())
            assertTrue(toggle.toolTipText.contains(KeymapUtil.getShortcutText(replacement)))
            val inside = event(action, field)
            assertTrue(ActionUtil.updateAction(action, inside).isPerformed)
            assertTrue(inside.presentation.isEnabled)
            action.actionPerformed(inside)
            assertTrue(toggle.isSelected)
            custom.removeAllActionShortcuts(id)
            assertEmpty(action.shortcutSet.shortcuts)
            assertFalse(toggle.toolTipText.contains(KeymapUtil.getShortcutText(replacement)))
            manager.activeKeymap = original
            assertEquals(original.getShortcuts(id).toList(), action.shortcutSet.shortcuts.toList())
        } finally {
            manager.activeKeymap = original
            bar.close()
            terminal.dispose()
        }
    }

    private fun event(
        action: AnAction,
        component: JComponent,
    ): AnActionEvent =
        AnActionEvent.createEvent(
            SimpleDataContext.builder().add(PlatformCoreDataKeys.CONTEXT_COMPONENT, component).build(),
            action.templatePresentation.clone(),
            ActionPlaces.KEYBOARD_SHORTCUT,
            ActionUiKind.NONE,
            null,
        )

    private fun descendants(c: Component): List<Component> =
        listOf(c) + if (c is Container) c.components.flatMap(::descendants) else emptyList()
}

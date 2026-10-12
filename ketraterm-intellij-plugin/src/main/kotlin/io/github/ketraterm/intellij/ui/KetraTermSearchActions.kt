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

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.project.DumbAwareAction
import io.github.ketraterm.ui.swing.host.SwingTerminalSearchAction
import io.github.ketraterm.ui.swing.host.SwingTerminalSearchBar
import javax.swing.JComponent

/** IDE keymap actions scoped to the search bar containing the context component. */
internal object KetraTermSearchActions {
    private val barKey = Any()

    fun id(action: SwingTerminalSearchAction): String =
        when (action) {
            SwingTerminalSearchAction.NEXT_MATCH -> "KetraTerm.Search.NextMatch"
            SwingTerminalSearchAction.PREVIOUS_MATCH -> "KetraTerm.Search.PreviousMatch"
            SwingTerminalSearchAction.CLOSE -> "KetraTerm.Search.Close"
            SwingTerminalSearchAction.TOGGLE_MATCH_CASE -> "KetraTerm.Search.MatchCase"
            SwingTerminalSearchAction.TOGGLE_WHOLE_WORD -> "KetraTerm.Search.WholeWord"
            SwingTerminalSearchAction.TOGGLE_REGEX -> "KetraTerm.Search.Regex"
        }

    fun install(bar: SwingTerminalSearchBar) {
        bar.component.putClientProperty(barKey, bar)
        for (action in SwingTerminalSearchAction.entries) {
            // Preserve the registered action's live keymap-backed shortcut set, including chords.
            requireNotNull(ActionManager.getInstance().getAction(id(action))).registerCustomShortcutSet(bar.component, null)
        }
        bar.setShortcutTextProvider { KeymapUtil.getFirstKeyboardShortcutText(id(it)) }
    }

    fun bar(event: AnActionEvent): SwingTerminalSearchBar? {
        var component = event.getData(PlatformCoreDataKeys.CONTEXT_COMPONENT)
        while (component != null) {
            ((component as? JComponent)?.getClientProperty(barKey) as? SwingTerminalSearchBar)?.let { return it }
            component = component.parent
        }
        return null
    }
}

/** Executes only in an open terminal search bar; ordinary IDE and shell keys remain unclaimed. */
internal abstract class KetraTermSearchAction(
    private val action: SwingTerminalSearchAction,
) : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabled = KetraTermSearchActions.bar(event)?.isOpen() == true
    }

    override fun actionPerformed(event: AnActionEvent) {
        KetraTermSearchActions.bar(event)?.performAction(action)
    }
}

internal class KetraTermNextSearchMatchAction : KetraTermSearchAction(SwingTerminalSearchAction.NEXT_MATCH)

internal class KetraTermPreviousSearchMatchAction : KetraTermSearchAction(SwingTerminalSearchAction.PREVIOUS_MATCH)

internal class KetraTermCloseSearchAction : KetraTermSearchAction(SwingTerminalSearchAction.CLOSE)

internal class KetraTermSearchMatchCaseAction : KetraTermSearchAction(SwingTerminalSearchAction.TOGGLE_MATCH_CASE)

internal class KetraTermSearchWholeWordAction : KetraTermSearchAction(SwingTerminalSearchAction.TOGGLE_WHOLE_WORD)

internal class KetraTermSearchRegexAction : KetraTermSearchAction(SwingTerminalSearchAction.TOGGLE_REGEX)

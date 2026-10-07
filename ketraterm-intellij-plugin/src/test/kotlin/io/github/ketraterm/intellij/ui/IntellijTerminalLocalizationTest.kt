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
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.ketraterm.ui.swing.host.SwingClipboardPrompts

/** Verifies IDE message-bundle wiring without a PTY or visible dialogs. */
class IntellijTerminalLocalizationTest : BasePlatformTestCase() {
    fun testRegisteredTerminalActionsResolveTheirResourceBundleLabels() {
        val labels =
            mapOf(
                KetraTermTerminalActionIds.COPY_SELECTION to "Copy",
                KetraTermTerminalActionIds.PASTE_CLIPBOARD to "Paste",
                KetraTermTerminalActionIds.OPEN_SEARCH to "Find",
                KetraTermTerminalActionIds.REQUEST_SUGGESTIONS to "Request Terminal Suggestions",
                KetraTermTerminalActionIds.SELECT_ALL to "Select All",
                KetraTermTerminalActionIds.CLEAR_SCREEN to "Clear",
                KetraTermTerminalActionIds.NEW_TAB to "New Tab",
                KetraTermTerminalActionIds.CLOSE_TAB to "Close Tab",
                KetraTermTerminalActionIds.RENAME_TAB to "Rename Tab...",
                KetraTermTerminalActionIds.OPEN_TERMINAL_HERE to "Open in KetraTerm",
                KetraTermTerminalActionIds.SCROLL_PAGE_UP to "Scroll Terminal Page Up",
                KetraTermTerminalActionIds.SCROLL_PAGE_DOWN to "Scroll Terminal Page Down",
            )
        for ((id, label) in labels) {
            val presentation = requireNotNull(ActionManager.getInstance().getAction(id)).templatePresentation
            assertEquals(id, label, presentation.text)
            assertFalse(id, presentation.description.isNullOrBlank())
        }
    }

    fun testSearchTextResolvesStatusAndCountMessages() {
        assertEquals("Search terminal output", IntellijSwingHostMessages.message("search.queryToolTip"))
        assertEquals("Searching…", IntellijSwingHostMessages.message("search.searching"))
        assertEquals("0/0", IntellijSwingHostMessages.message("search.matchCounter", 0, 0))
        assertEquals("2/1000", IntellijSwingHostMessages.message("search.matchCounter", 2, 1000))
    }

    fun testClipboardBundleMessagesKeepPayloadPrivateAndCountCodePoints() {
        val singular = SwingClipboardPrompts.writeConfirmation("shell", "😀", messages = IntellijSwingHostMessages)
        assertEquals("Clipboard Access", singular.title)
        assertEquals("Allow an application in shell to write 1 character to the IDE clipboard?", singular.message)
        assertEquals(listOf("Allow once", "Deny"), singular.options)
        assertEquals(1, singular.defaultOption)

        val plural = SwingClipboardPrompts.writeQuestion("shell", "😀x", messages = IntellijSwingHostMessages)
        assertEquals("Allow an application in shell to write 2 characters to the IDE clipboard?", plural)
        assertEquals(
            "Allow an application in this terminal to clear the IDE clipboard?",
            SwingClipboardPrompts.writeQuestion("  ", "", messages = IntellijSwingHostMessages),
        )
        assertFalse(plural.contains("😀"))
    }
}

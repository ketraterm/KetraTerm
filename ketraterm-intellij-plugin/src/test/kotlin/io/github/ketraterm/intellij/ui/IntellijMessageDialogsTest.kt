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

import com.intellij.openapi.ui.messages.MessageDialog
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.UiInterceptors
import io.github.ketraterm.ui.swing.host.SwingClipboardPrompts
import io.github.ketraterm.ui.swing.host.SwingClipboardReadPrompt.Decision
import io.github.ketraterm.ui.swing.host.SwingDialogRequest

class IntellijMessageDialogsTest : BasePlatformTestCase() {
    fun testOrdinaryMessagesUseTheSamePresenter() {
        for (severity in SwingDialogRequest.Severity.entries) {
            val request = SwingDialogRequest("Operation status", "A message\nAnother line", severity)
            intercept { dialog ->
                assertEquals(request.title, dialog.title)
                dialog.close(0)
            }
            assertEquals(0, IntellijMessageDialogs.show(project, request))
        }
    }

    override fun tearDown() {
        try {
            UiInterceptors.clear()
        } finally {
            super.tearDown()
        }
    }

    fun testReadMapsStandardDialogChoicesAndWindowClose() {
        for (exitCode in listOf(0, 1, 2, -1)) {
            lateinit var dialog: MessageDialog
            intercept { dialog = it }
            val decisions = mutableListOf<Int?>()
            IntellijMessageDialogs.showModeless(project, request(), decisions::add).use {
                assertEquals(SwingClipboardPrompts.TITLE, dialog.title)
                assertFalse(dialog.isDisposed)
                dialog.close(exitCode)
                assertEquals(listOf(exitCode.takeIf { it >= 0 }), decisions)
            }
            assertTrue(dialog.isDisposed)
        }
    }

    fun testCancellationHandleDisposesDialogAndDeniesOnlyOnce() {
        lateinit var dialog: MessageDialog
        intercept { dialog = it }
        val decisions = mutableListOf<Int?>()
        val handle = IntellijMessageDialogs.showModeless(project, request(), decisions::add)
        handle.close()
        handle.close()
        assertTrue(dialog.isDisposed)
        assertEquals(listOf<Int?>(null), decisions)
    }

    fun testWriteUsesSameDialogAndRequiresExplicitAllow() {
        for (exitCode in listOf(0, 1, -1)) {
            intercept { dialog ->
                assertEquals(SwingClipboardPrompts.TITLE, dialog.title)
                dialog.close(exitCode)
            }
            assertEquals(
                exitCode == 0,
                (
                    IntellijMessageDialogs.show(project, SwingClipboardPrompts.writeConfirmation("Terminal", "value")) ==
                        0
                ),
            )
        }
    }

    private fun request() =
        SwingDialogRequest(
            SwingClipboardPrompts.TITLE,
            "A harmless request",
            SwingDialogRequest.Severity.WARNING,
            Decision.entries.map { it.label },
            defaultOption = Decision.DENY.ordinal,
        )

    private fun intercept(block: (MessageDialog) -> Unit) {
        UiInterceptors.register(
            object : UiInterceptors.UiInterceptor<MessageDialog>(MessageDialog::class.java) {
                override fun doIntercept(component: MessageDialog) = block(component)
            },
        )
    }
}

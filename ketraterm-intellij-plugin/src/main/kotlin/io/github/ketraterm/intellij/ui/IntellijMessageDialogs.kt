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

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.messages.MessageDialog
import com.intellij.openapi.util.Disposer
import io.github.ketraterm.ui.swing.host.SwingDialogRequest
import javax.swing.SwingUtilities

/** IDE presentation of the shared message/choice contract; all operations belong to the EDT. */
internal object IntellijMessageDialogs {
    fun show(
        project: Project,
        request: SwingDialogRequest,
    ): Int? {
        check(SwingUtilities.isEventDispatchThread())
        val dialog = dialog(project, request)
        try {
            dialog.show()
            return dialog.exitCode.takeIf { it in request.options.indices }
        } finally {
            if (!dialog.isDisposed) dialog.close(-1)
        }
    }

    fun showModeless(
        project: Project,
        request: SwingDialogRequest,
        decide: (Int?) -> Unit,
    ): AutoCloseable {
        check(SwingUtilities.isEventDispatchThread())
        val dialog = dialog(project, request)
        dialog.isModal = false
        Disposer.register(dialog.disposable) {
            decide(dialog.exitCode.takeIf { it in request.options.indices })
        }
        try {
            dialog.show()
        } catch (failure: Throwable) {
            if (!dialog.isDisposed) dialog.close(-1)
            throw failure
        }
        return AutoCloseable {
            check(SwingUtilities.isEventDispatchThread())
            if (!dialog.isDisposed) dialog.close(-1)
        }
    }

    private fun dialog(
        project: Project,
        request: SwingDialogRequest,
    ): MessageDialog =
        MessageDialog(
            project,
            request.htmlMessage(),
            request.title,
            request.options.toTypedArray(),
            request.defaultOption,
            when (request.severity) {
                SwingDialogRequest.Severity.INFORMATION -> Messages.getInformationIcon()
                SwingDialogRequest.Severity.WARNING -> Messages.getWarningIcon()
                SwingDialogRequest.Severity.ERROR -> Messages.getErrorIcon()
            },
            false,
        )
}

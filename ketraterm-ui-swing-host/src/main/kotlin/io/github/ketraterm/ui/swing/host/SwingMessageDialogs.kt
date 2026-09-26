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
package io.github.ketraterm.ui.swing.host

import java.awt.Component
import java.awt.KeyboardFocusManager.BACKWARD_TRAVERSAL_KEYS
import java.awt.KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS
import java.awt.event.KeyEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.KeyStroke
import javax.swing.SwingUtilities

/** Standard Swing message/choice dialogs. All operations and callbacks belong to the EDT. */
object SwingMessageDialogs {
    /** Shows a modal dialog and returns its option index, or null on dismissal. */
    fun show(
        parent: Component,
        request: SwingDialogRequest,
    ): Int? {
        check(SwingUtilities.isEventDispatchThread())
        val pane = optionPane(request)
        val dialog = pane.createDialog(parent, request.title)
        try {
            dialog.isVisible = true
            return request.options.indexOf(pane.value).takeIf { it >= 0 }
        } finally {
            dialog.dispose()
        }
    }

    /**
     * Shows with normal platform focus on the initial choice, without blocking
     * the owner. Completion happens once; closing the returned handle disposes
     * the dialog and reports dismissal.
     */
    fun showModeless(
        parent: Component,
        request: SwingDialogRequest,
        decide: (Int?) -> Unit,
    ): AutoCloseable {
        check(SwingUtilities.isEventDispatchThread())
        val pane = optionPane(request)
        val dialog = pane.createDialog(parent, request.title)
        dialog.isModal = false
        var completed = false

        fun complete(option: Int?) {
            if (completed) return
            completed = true
            dialog.dispose()
            decide(option)
        }
        pane.addPropertyChangeListener(JOptionPane.VALUE_PROPERTY) {
            if (pane.value != JOptionPane.UNINITIALIZED_VALUE) {
                complete(request.options.indexOf(pane.value).takeIf { it >= 0 })
            }
        }
        dialog.addWindowListener(
            object : WindowAdapter() {
                override fun windowClosed(event: WindowEvent) = complete(null)
            },
        )
        try {
            dialog.isVisible = true
        } catch (failure: Throwable) {
            dialog.dispose()
            throw failure
        }
        return AutoCloseable {
            check(SwingUtilities.isEventDispatchThread())
            complete(null)
        }
    }

    private fun optionPane(request: SwingDialogRequest): JOptionPane =
        JOptionPane(
            JLabel(request.htmlMessage()),
            when (request.severity) {
                SwingDialogRequest.Severity.INFORMATION -> JOptionPane.INFORMATION_MESSAGE
                SwingDialogRequest.Severity.WARNING -> JOptionPane.WARNING_MESSAGE
                SwingDialogRequest.Severity.ERROR -> JOptionPane.ERROR_MESSAGE
            },
            JOptionPane.DEFAULT_OPTION,
            null,
            request.options.toTypedArray(),
            request.options[request.defaultOption],
        ).apply {
            // Extend standard focus traversal; the platform still owns cycling,
            // disabled-button skipping, Tab, Enter and Escape.
            setFocusTraversalKeys(
                FORWARD_TRAVERSAL_KEYS,
                getFocusTraversalKeys(FORWARD_TRAVERSAL_KEYS) +
                    setOf(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0), KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0)),
            )
            setFocusTraversalKeys(
                BACKWARD_TRAVERSAL_KEYS,
                getFocusTraversalKeys(BACKWARD_TRAVERSAL_KEYS) +
                    setOf(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0), KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0)),
            )
        }
}

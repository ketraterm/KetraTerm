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

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.awt.BorderLayout
import java.awt.GridLayout
import java.awt.KeyboardFocusManager
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import javax.swing.*
import kotlin.coroutines.resume

/**
 * Nonmodal clipboard consent owned by one terminal pane. All operations belong
 * to the EDT. Showing it never requests focus; the host installs [component] in
 * its pane and routes terminal Escape to [dismiss]. Close with the pane.
 *
 * Cancellation hides the prompt. Blocking persists for this pane's lifetime,
 * including later requests admitted with Allow. No clipboard content is shown.
 * @param focusTarget terminal component to refocus only when a decision control held focus.
 */
class SwingClipboardReadPrompt(
    private val focusTarget: JComponent,
) : AutoCloseable {
    private var pending: CancellableContinuation<Boolean>? = null
    private var blocked = false
    private var closed = false
    private val question =
        JTextArea(3, 38).apply {
            isEditable = false
            isFocusable = false
            lineWrap = true
            wrapStyleWord = true
            isOpaque = false
            font = JLabel().font
        }

    /** Host-owned overlay chrome, hidden outside a pending request. */
    val component: JPanel =
        JPanel(BorderLayout(0, 6)).apply {
            check(SwingUtilities.isEventDispatchThread())
            border =
                BorderFactory.createCompoundBorder(
                    BorderFactory.createEtchedBorder(),
                    BorderFactory.createEmptyBorder(10, 10, 10, 10),
                )
            add(question, BorderLayout.NORTH)
            add(JLabel("This request expires automatically."), BorderLayout.CENTER)
            add(
                JPanel(GridLayout(0, 1, 0, 4)).apply {
                    add(JButton("Allow once").apply { addActionListener { complete(true) } })
                    add(JButton("Deny").apply { addActionListener { complete(false) } })
                    add(
                        JButton("Block for this terminal").apply {
                            addActionListener {
                                if (pending?.isActive == true) {
                                    blocked = true
                                    complete(false)
                                }
                            }
                        },
                    )
                },
                BorderLayout.SOUTH,
            )
            getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                "denyClipboardRead",
            )
            actionMap.put(
                "denyClipboardRead",
                object : AbstractAction() {
                    override fun actionPerformed(event: ActionEvent) {
                        dismiss()
                    }
                },
            )
            isVisible = false
        }

    /** Whether this pane has been blocked or closed. */
    val isBlocked: Boolean
        get() {
            check(SwingUtilities.isEventDispatchThread())
            return blocked || closed
        }

    /** Suspends for one decision; concurrent requests are declined without replacing it. */
    suspend fun request(message: String): Boolean {
        check(SwingUtilities.isEventDispatchThread())
        if (isBlocked || pending != null) return false
        try {
            return suspendCancellableCoroutine { continuation ->
                pending = continuation
                question.text = message
                component.isVisible = true
                component.revalidate()
            }
        } finally {
            val focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
            val restoreFocus = focused != null && SwingUtilities.isDescendingFrom(focused, component)
            pending = null
            question.text = ""
            component.isVisible = false
            component.revalidate()
            component.parent?.repaint()
            if (restoreFocus && !closed) focusTarget.requestFocusInWindow()
        }
    }

    /** Denies a pending request, returning whether Escape was consumed. */
    fun dismiss(): Boolean {
        check(SwingUtilities.isEventDispatchThread())
        if (pending == null) return false
        complete(false)
        return true
    }

    /** Cancels consent when the owning pane is disposed. */
    override fun close() {
        check(SwingUtilities.isEventDispatchThread())
        closed = true
        pending?.cancel(CancellationException("Terminal pane closed"))
    }

    private fun complete(allowed: Boolean) {
        val continuation = pending ?: return
        if (continuation.isActive) continuation.resume(allowed)
    }
}

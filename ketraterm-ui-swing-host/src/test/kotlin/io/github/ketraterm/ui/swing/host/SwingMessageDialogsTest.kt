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

import io.github.ketraterm.ui.swing.host.SwingClipboardReadPrompt.Decision
import org.junit.jupiter.api.Assumptions.assumeFalse
import java.awt.Component
import java.awt.Container
import java.awt.DefaultKeyboardFocusManager
import java.awt.GraphicsEnvironment
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.WindowEvent
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import javax.swing.*
import kotlin.test.*

class SwingMessageDialogsTest {
    @Test
    fun `arrow keys cycle dialog choices skip disabled buttons and preserve Tab without accepting`() =
        withOwner { owner ->
            val decisions = mutableListOf<Int?>()
            SwingMessageDialogs.showModeless(owner, request(), decisions::add).use {
                val dialog = owner.ownedWindows.filterIsInstance<JDialog>().single { it.isVisible }
                val pane = optionPane(dialog)
                val buttons = components(pane).filterIsInstance<JButton>().associateBy { it.text }
                var focused: Component = buttons.getValue("Deny")
                // Use AWT's key processing and the real dialog's traversal policy;
                // replace only native focus transfer so desktop focus cannot race the test.
                val keyboard =
                    object : DefaultKeyboardFocusManager() {
                        override fun focusNextComponent(component: Component) {
                            focused = dialog.focusTraversalPolicy.getComponentAfter(dialog, component)
                        }

                        override fun focusPreviousComponent(component: Component) {
                            focused = dialog.focusTraversalPolicy.getComponentBefore(dialog, component)
                        }
                    }

                fun move(
                    key: Int,
                    expected: String,
                    modifiers: Int = 0,
                ) {
                    val source = focused
                    for (id in listOf(KeyEvent.KEY_PRESSED, KeyEvent.KEY_RELEASED)) {
                        val event = KeyEvent(source, id, 0, modifiers, key, KeyEvent.CHAR_UNDEFINED)
                        keyboard.processKeyEvent(source, event)
                        assertTrue(event.isConsumed)
                    }
                    assertSame(buttons.getValue(expected), focused)
                    assertEquals(JOptionPane.UNINITIALIZED_VALUE, pane.value)
                    assertTrue(decisions.isEmpty())
                }
                move(KeyEvent.VK_RIGHT, "Block for this terminal")
                move(KeyEvent.VK_RIGHT, "Allow once")
                move(KeyEvent.VK_LEFT, "Block for this terminal")
                move(KeyEvent.VK_DOWN, "Allow once")
                move(KeyEvent.VK_UP, "Block for this terminal")
                buttons.getValue("Deny").isEnabled = false
                move(KeyEvent.VK_RIGHT, "Allow once")
                move(KeyEvent.VK_RIGHT, "Block for this terminal")
                move(KeyEvent.VK_TAB, "Allow once")
                move(KeyEvent.VK_TAB, "Block for this terminal", InputEvent.SHIFT_DOWN_MASK)
            }
        }

    @Test
    fun `ordinary messages share severity mapping and disposal completes only once`() =
        withOwner { owner ->
            val kinds =
                mapOf(
                    SwingDialogRequest.Severity.INFORMATION to JOptionPane.INFORMATION_MESSAGE,
                    SwingDialogRequest.Severity.WARNING to JOptionPane.WARNING_MESSAGE,
                    SwingDialogRequest.Severity.ERROR to JOptionPane.ERROR_MESSAGE,
                )
            for ((severity, messageType) in kinds) {
                val decisions = mutableListOf<Int?>()
                val message = SwingDialogRequest("Operation status", "A message\nAnother line", severity)
                val handle = SwingMessageDialogs.showModeless(owner, message, decisions::add)
                try {
                    val dialog = owner.ownedWindows.filterIsInstance<JDialog>().single { it.isVisible }
                    val pane = optionPane(dialog)
                    assertEquals(messageType, pane.messageType)
                    assertEquals(listOf("OK"), pane.options.toList())
                    assertEquals("OK", pane.initialValue)
                    assertEquals(message.htmlMessage(), (pane.message as JLabel).text)
                } finally {
                    handle.close()
                    handle.close()
                }
                assertEquals(listOf<Int?>(null), decisions)
                assertTrue(owner.ownedWindows.none { it.isDisplayable })
            }
        }

    @Test
    fun `read requests focus with Deny as its initial choice`() =
        withOwner { owner ->
            for (decision in Decision.entries) {
                val decisions = mutableListOf<Int?>()
                SwingMessageDialogs.showModeless(owner, request(), decisions::add).use {
                    val dialog = owner.ownedWindows.filterIsInstance<JDialog>().single { it.isVisible }
                    val pane = optionPane(dialog)
                    assertEquals(request().title, dialog.title)
                    assertFalse(dialog.isModal)
                    assertTrue(dialog.isAutoRequestFocus)
                    assertEquals(JOptionPane.WARNING_MESSAGE, pane.messageType)
                    assertEquals(Decision.entries.map { it.label }, pane.options.toList())
                    assertEquals(Decision.DENY.label, pane.initialValue)
                    pane.value = decision.label
                    assertEquals(decision.ordinal, decisions.single())
                }
                assertTrue(owner.ownedWindows.none { it.isDisplayable })
            }
        }

    @Test
    fun `closing the read dialog denies consent`() =
        withOwner { owner ->
            val decisions = mutableListOf<Int?>()
            SwingMessageDialogs.showModeless(owner, request(), decisions::add).use {
                val dialog = owner.ownedWindows.filterIsInstance<JDialog>().single { it.isVisible }
                dialog.dispatchEvent(WindowEvent(dialog, WindowEvent.WINDOW_CLOSING))
                assertNull(decisions.single())
            }
        }

    @Test
    fun `write uses the same warning presentation and only explicit allow succeeds`() =
        withOwner { owner ->
            for (choice in listOf(Decision.ALLOW_ONCE.label, Decision.DENY.label, JOptionPane.CLOSED_OPTION)) {
                var assertionFailure: Throwable? = null
                SwingUtilities.invokeLater {
                    val dialog = owner.ownedWindows.filterIsInstance<JDialog>().single { it.isVisible }
                    try {
                        val pane = optionPane(dialog)
                        assertTrue(dialog.isModal)
                        assertEquals(JOptionPane.WARNING_MESSAGE, pane.messageType)
                        assertEquals(listOf(Decision.ALLOW_ONCE.label, Decision.DENY.label), pane.options.toList())
                        assertEquals(Decision.DENY.label, pane.initialValue)
                        pane.value = choice
                    } catch (failure: Throwable) {
                        assertionFailure = failure
                    } finally {
                        dialog.dispose()
                    }
                }
                assertEquals(
                    choice == Decision.ALLOW_ONCE.label,
                    (
                        SwingMessageDialogs.show(owner, SwingClipboardPrompts.writeConfirmation("Terminal", "value")) ==
                            0
                    ),
                )
                assertionFailure?.let { throw it }
            }
        }

    private fun request() =
        SwingDialogRequest(
            "Confirmation",
            "A harmless request",
            SwingDialogRequest.Severity.WARNING,
            Decision.entries.map { it.label },
            defaultOption = Decision.DENY.ordinal,
        )

    private fun optionPane(container: Container): JOptionPane = components(container).filterIsInstance<JOptionPane>().single()

    private fun components(container: Container): Sequence<Component> =
        sequence {
            for (child in container.components) {
                yield(child)
                if (child is Container) yieldAll(components(child))
            }
        }

    private fun withOwner(test: (JFrame) -> Unit) {
        assumeFalse(GraphicsEnvironment.isHeadless())
        val task =
            FutureTask {
                val owner = JFrame()
                try {
                    test(owner)
                } finally {
                    owner.dispose()
                }
            }
        SwingUtilities.invokeLater(task)
        task.get(10, TimeUnit.SECONDS)
    }
}

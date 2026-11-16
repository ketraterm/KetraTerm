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
package io.github.ketraterm.ui.swing.api

import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.transport.TerminalConnector
import io.github.ketraterm.transport.TerminalConnectorListener
import io.github.ketraterm.ui.swing.settings.SwingPadding
import io.github.ketraterm.ui.swing.settings.SwingSettings
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.ComponentEvent
import java.beans.PropertyChangeEvent
import java.beans.PropertyChangeListener
import javax.accessibility.*
import javax.swing.SwingUtilities
import kotlin.test.*

class SwingTerminalAccessibilityTest {
    private val dispatcher = StandardTestDispatcher()
    private val views = ArrayList<SwingTerminal>()
    private val sessions = ArrayList<TerminalSession>()

    @AfterEach
    fun cleanup() {
        edtCall { views.forEach(SwingTerminal::dispose) }
        sessions.forEach(TerminalSession::close)
        drain()
    }

    @Test
    fun `standard text access works off EDT and preserves host metadata and input semantics`() {
        val view = createView(12, 1)
        val accessible: Accessible = view
        val context = accessible.accessibleContext
        assertSame(context, view.accessibleContext)
        assertEquals(AccessibleRole.TEXT, context.accessibleRole)
        assertTrue(context.accessibleStateSet.contains(AccessibleState.FOCUSABLE))
        assertTrue(context.accessibleStateSet.contains(AccessibleState.MULTI_LINE))
        assertFalse(context.accessibleStateSet.contains(AccessibleState.EDITABLE))
        assertFalse(context.accessibleText is AccessibleEditableText)
        assertEquals(0, context.accessibleText.charCount)
        assertEquals(-1, context.accessibleText.caretPosition)
        edtCall {
            context.accessibleName = "Build shell"
            context.accessibleDescription = "Project tools"
            assertFalse(view.focusTraversalKeysEnabled)
        }
        val session = bind(view, 12, 1)
        output(session, "hello world")
        assertEquals("hello", context.accessibleText.getAtIndex(AccessibleText.WORD, 1))
        assertEquals(" ", context.accessibleText.getAfterIndex(AccessibleText.WORD, 1))
        assertEquals("hello", context.accessibleText.getBeforeIndex(AccessibleText.WORD, 5))
        assertNull(context.accessibleText.getAtIndex(AccessibleText.CHARACTER, -1))
        assertNull(context.accessibleText.getAfterIndex(AccessibleText.WORD, context.accessibleText.charCount))
        assertEquals("Build shell", context.accessibleName)
        edtCall { view.unbind() }
        drain()
        assertEquals(0, context.accessibleText.charCount)
        assertEquals("Project tools", context.accessibleDescription)
    }

    @Test
    fun `text caret and selection changes notify on EDT and listener removal ends delivery`() {
        val view = createView(10, 2)
        val session = bind(view, 10, 2)
        val context = view.accessibleContext
        val events = ArrayList<PropertyChangeEvent>()
        val listener =
            PropertyChangeListener {
                assertTrue(SwingUtilities.isEventDispatchThread())
                events += it
            }
        edtCall { context.addPropertyChangeListener(listener) }
        output(session, "abcde")
        assertTrue(events.any { it.propertyName == AccessibleContext.ACCESSIBLE_TEXT_PROPERTY })
        assertTrue(events.any { it.propertyName == AccessibleContext.ACCESSIBLE_CARET_PROPERTY && it.newValue == 5 })
        events.clear()
        output(session, "\u001b[2D")
        assertEquals(3, context.accessibleText.caretPosition)
        assertFalse(events.any { it.propertyName == AccessibleContext.ACCESSIBLE_TEXT_PROPERTY })
        assertTrue(events.any { it.propertyName == AccessibleContext.ACCESSIBLE_CARET_PROPERTY })
        edtCall {
            assertTrue(view.setSelection(assertNotNull(view.createSelectionRange(1, 0L, 4, 0L))))
        }
        drain()
        assertEquals(1, context.accessibleText.selectionStart)
        assertEquals(4, context.accessibleText.selectionEnd)
        assertEquals("bcd", context.accessibleText.selectedText)
        assertTrue(events.any { it.propertyName == AccessibleContext.ACCESSIBLE_SELECTION_PROPERTY })
        edtCall { view.clearSelection() }
        drain()
        assertNull(context.accessibleText.selectedText)
        assertEquals(3, context.accessibleText.selectionStart)
        edtCall { context.removePropertyChangeListener(listener) }
        events.clear()
        output(session, "Z")
        assertTrue(events.isEmpty())
    }

    @Test
    fun `UTF16 text maps wide glyphs clusters and bidi to shared visible geometry`() {
        val view = createView(16, 2)
        val session = bind(view, 16, 2)
        val content = "A界e\u0301😀אב"
        output(session, content)
        val text = view.accessibleContext.accessibleText
        assertTrue(allText(text).startsWith(content))
        assertEquals(text.getCharacterBounds(2), text.getCharacterBounds(3))
        assertEquals(text.getCharacterBounds(4), text.getCharacterBounds(5))
        edtCall {
            for ((index, column) in listOf(0 to 0, 1 to 1, 2 to 3, 4 to 4, 6 to 6, 7 to 7)) {
                val expected = Rectangle()
                assertTrue(view.copyCellBounds(column, 0, expected))
                if (index == 1 || index == 4) {
                    val trailing = Rectangle()
                    assertTrue(view.copyCellBounds(column + 1, 0, trailing))
                    expected.add(trailing)
                    assertEquals(index, text.getIndexAtPoint(center(trailing)))
                }
                assertEquals(expected, text.getCharacterBounds(index))
                assertEquals(index, text.getIndexAtPoint(center(expected)))
            }
        }
        assertEquals(-1, text.getIndexAtPoint(Point(-1, -1)))
        assertEquals(Rectangle(), text.getCharacterBounds(-1))
        assertEquals(Rectangle(), text.getCharacterBounds(text.charCount))
    }

    @Test
    fun `soft wraps join text and linear selection clips to the displayed history`() {
        val view = createView(4, 2)
        val session = bind(view, 4, 2)
        output(session, "abcdefghij")
        val text = view.accessibleContext.accessibleText
        assertEquals("efghij  ", allText(text))
        edtCall {
            assertTrue(view.setSelection(assertNotNull(view.createSelectionRange(1, 0L, 2, 2L))))
        }
        drain()
        assertEquals("efghij", text.selectedText)
        edtCall { view.scrollToScrollbackOffset(1) }
        drain()
        assertEquals("abcdefgh", allText(text))
        assertEquals("bcdefgh", text.selectedText)
        assertEquals(-1, text.caretPosition)
        edtCall {
            assertTrue(view.setSelection(assertNotNull(view.createSelectionRange(1, 0L, 2, 1L, isBlock = true))))
        }
        drain()
        assertNull(text.selectedText)
        assertEquals(text.selectionStart, text.selectionEnd)
    }

    @Test
    fun `resize buffer switch closure and rebinding cannot expose stale text`() {
        val view = createView(8, 2)
        val session = bind(view, 8, 2)
        val context = view.accessibleContext
        output(session, "primary")
        output(session, "\u001b[?1049hALT")
        assertTrue(allText(context.accessibleText).startsWith("ALT"))
        output(session, "\u001b[?1049l")
        assertTrue(allText(context.accessibleText).startsWith("primary"))
        session.close()
        drain()
        assertTrue(allText(context.accessibleText).startsWith("primary"))
        edtCall {
            view.size = view.preferredGridSize(8, 1)
            val event = ComponentEvent(view, ComponentEvent.COMPONENT_RESIZED)
            view.componentListeners.forEach { it.componentResized(event) }
        }
        drain()
        assertEquals(8, context.accessibleText.charCount)
        val replacement = bind(view, 8, 1)
        output(replacement, "new")
        assertTrue(allText(context.accessibleText).startsWith("new"))
        edtCall { view.dispose() }
        drain()
        output(replacement, "later")
        assertEquals(0, context.accessibleText.charCount)
        assertEquals(-1, context.accessibleText.caretPosition)
    }

    @Test
    fun `a listener can dispose during text delivery without stale caret or selection events`() {
        val view = createView(10, 2)
        val session = bind(view, 10, 2)
        val context = view.accessibleContext
        val carets = ArrayList<Int>()
        edtCall {
            context.addPropertyChangeListener {
                if (it.propertyName == AccessibleContext.ACCESSIBLE_TEXT_PROPERTY && context.accessibleText.charCount > 0) view.dispose()
                if (it.propertyName == AccessibleContext.ACCESSIBLE_CARET_PROPERTY) carets += it.newValue as Int
            }
        }
        output(session, "abc")
        assertEquals(0, context.accessibleText.charCount)
        assertFalse(carets.contains(3))
        assertTrue(carets.all { it == -1 })
    }

    @Test
    fun `listener registration owns observation and unchanged frames do not announce changes`() {
        val view = createView(10, 2)
        val session = bind(view, 10, 2)
        val context = view.accessibleContext
        output(session, "abc")
        assertTrue(allText(context.accessibleText).startsWith("abc"))
        var notifications = 0
        val listener =
            PropertyChangeListener {
                assertTrue(SwingUtilities.isEventDispatchThread())
                if (it.propertyName == AccessibleContext.ACCESSIBLE_TEXT_PROPERTY) notifications++
            }
        context.addPropertyChangeListener(listener)
        context.addPropertyChangeListener(listener)
        context.removePropertyChangeListener { }
        edtCall {
            session.requestRender(0)
            dispatcher.scheduler.runCurrent()
        }
        drain()
        assertEquals(0, notifications)
        output(session, "d")
        assertEquals(2, notifications)
        context.removePropertyChangeListener(listener)
        output(session, "e")
        assertEquals(3, notifications)
        context.removePropertyChangeListener(listener)
        output(session, "f")
        assertEquals(3, notifications)
        assertTrue(allText(context.accessibleText).startsWith("abcdef"))
    }

    private fun createView(
        columns: Int,
        rows: Int,
    ): SwingTerminal =
        edtCall {
            SwingTerminal(
                settingsProvider = {
                    SwingSettings.create {
                        it.columns = columns
                        it.rows = rows
                        it.padding = SwingPadding()
                        it.shellIntegrationDecorationGutterWidth = 0
                        it.cursorBlinkMillis = 0
                        it.smoothScrolling = false
                    }
                },
                hostServices = SwingHostServices.create { it.uiDispatcher = CONTROLLED_SWING_UI_DISPATCHER },
                searchDispatcher = dispatcher,
                mutationDispatcher = dispatcher,
            ).also {
                it.size = it.preferredGridSize(columns, rows)
                views += it
            }
        }

    private fun bind(
        view: SwingTerminal,
        columns: Int,
        rows: Int,
    ): TerminalSession {
        val session =
            TerminalSession.create(
                TerminalBuffers.create(columns, rows, maxHistory = 20),
                Connector,
                workerDispatcher = dispatcher,
                ioDispatcher = dispatcher,
            )
        sessions += session
        edtCall {
            session.requestRender(0)
            dispatcher.scheduler.runCurrent()
            view.bind(session)
            dispatcher.scheduler.runCurrent()
        }
        drain()
        return session
    }

    private fun output(
        session: TerminalSession,
        content: String,
    ) {
        val bytes = content.toByteArray(Charsets.UTF_8)
        edtCall {
            session.onBytes(bytes, 0, bytes.size)
            session.requestRender(0)
            dispatcher.scheduler.runCurrent()
        }
        drain()
    }

    private fun drain() {
        edtCall { dispatcher.scheduler.runCurrent() }
        edtCall { }
    }

    private fun allText(text: AccessibleText): String =
        buildString {
            for (index in 0 until text.charCount) append(text.getAtIndex(AccessibleText.CHARACTER, index))
        }

    private fun center(bounds: Rectangle): Point = Point(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2)

    private object Connector : TerminalConnector {
        override fun start(listener: TerminalConnectorListener) = Unit

        override fun write(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) = Unit

        override fun resize(
            columns: Int,
            rows: Int,
        ) = Unit

        override fun close() = Unit
    }
}

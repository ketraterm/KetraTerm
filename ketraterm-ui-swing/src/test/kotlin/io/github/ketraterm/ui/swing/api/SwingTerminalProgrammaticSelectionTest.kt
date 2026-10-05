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
import io.github.ketraterm.render.api.TerminalRenderBufferKind
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.transport.TerminalConnector
import io.github.ketraterm.transport.TerminalConnectorListener
import io.github.ketraterm.ui.swing.settings.SwingPadding
import io.github.ketraterm.ui.swing.settings.SwingSettings
import io.github.ketraterm.ui.swing.settings.TerminalClipboardHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.awt.Rectangle
import java.awt.event.ComponentEvent
import java.awt.event.MouseEvent
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

class SwingTerminalProgrammaticSelectionTest {
    @Test
    fun `host can select restore and clear without input or scrolling`() =
        fixture("first\r\nsecond\r\nthird\r\nlast") {
            val events = mutableListOf<Pair<TerminalSelectionRange?, TerminalSelectionRange?>>()
            val listener = TerminalSelectionListener { previous, current -> events += previous to current }
            view.addSelectionListener(listener)
            view.addSelectionListener(listener)
            assertTrue(events.isEmpty())
            val range = range(1, 0, 4, 1)
            val viewport = view.viewportState()
            val generation = session.renderGeneration.value
            assertTrue(view.setSelection(range))
            assertEquals("irst\nseco", copiedText())
            val saved = requireNotNull(view.currentSelectionRange())
            assertEquals(0L, saved.anchorAbsoluteRow)
            assertEquals(1L, saved.caretAbsoluteRow)
            assertTrue(view.setSelection(range))
            assertEquals(1, events.size)
            view.clearSelection()
            view.clearSelection()
            assertNull(view.currentSelectionRange())
            assertFalse(view.copySelectionToClipboard())
            assertTrue(view.setSelection(saved))
            assertEquals("irst\nseco", copiedText())
            assertEquals(3, events.size)
            assertSame(saved, events[1].first)
            assertNull(events[1].second)
            assertEquals(viewport.scrollbackOffset, view.viewportState().scrollbackOffset)
            assertEquals(generation, session.renderGeneration.value)
            view.removeSelectionListener(listener)
            view.clearSelection()
            assertEquals(3, events.size)
            assertEquals(0, connector.writes)
        }

    @Test
    fun `range remains complete outside the viewport and closed resizing preserves it`() =
        fixture("first\r\nsecond\r\nthird\r\nlast") {
            assertTrue(view.setSelection(range(0, 0, 5, 0)))
            val saved = requireNotNull(view.currentSelectionRange())
            assertNull(view.currentSelection(), "the oldest row is outside the bottom viewport")
            resize(4, 1)
            assertSame(saved, view.currentSelectionRange())
            assertEquals("first", copiedText())
            view.scrollToScrollbackOffset(Int.MAX_VALUE)
            assertSame(saved, view.currentSelectionRange())
            assertNotNull(view.currentSelection())
            settings = settings.copy { it.font = it.font.deriveFont(24f) }
            view.reloadSettings()
            assertSame(saved, view.currentSelectionRange())
            assertEquals("first", copiedText())
        }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `backward ranges preserve direction and complete wide clusters`(block: Boolean) =
        fixture("A界e\u0301Z\r\n123456") {
            assertTrue(view.setSelection(range(4, 1, 2, 0, block)))
            val saved = requireNotNull(view.currentSelectionRange())
            assertEquals(1L, saved.anchorAbsoluteRow)
            assertEquals(0L, saved.caretAbsoluteRow)
            assertEquals(4, saved.anchorColumn)
            assertEquals(2, saved.caretColumn)
            assertEquals(if (block) "界e\u0301\n34" else "界e\u0301Z\n1234", copiedText())
            view.clearSelection()
            assertTrue(view.setSelection(saved))
            assertEquals(block, view.currentSelectionRange()?.isBlock)
        }

    @Test
    fun `range boundaries and empty ranges have explicit behavior`() =
        fixture("hello") {
            assertTrue(view.setSelection(range(0, 0, 5, 0)))
            val saved = view.currentSelectionRange()
            assertThrows(IllegalArgumentException::class.java) { view.createSelectionRange(-1, 0, 1, 0) }
            assertThrows(IllegalArgumentException::class.java) { view.createSelectionRange(0, -1, 1, 0) }
            assertNull(view.createSelectionRange(Int.MAX_VALUE, 0, 1, 0))
            assertNull(view.createSelectionRange(0, Long.MAX_VALUE, 1, Long.MAX_VALUE))
            assertNull(view.createSelectionRange(0, 0, 11, 0))
            assertSame(saved, view.currentSelectionRange())
            assertTrue(view.setSelection(range(10, 0, 10, 0)))
            assertNull(view.currentSelectionRange())
            assertTrue(view.setSelection(range(0, 0, 0, 1)))
            assertEquals("hello\n", copiedText())
            assertTrue(view.setSelection(range(1, 0, 1, 1, true)))
            assertNull(view.currentSelectionRange())
        }

    @Test
    fun `restoration rejects another component and a replaced binding`() =
        fixture("hello") {
            val saved = range(0, 0, 5, 0)
            val other = SwingTerminal()
            try {
                other.bind(session)
                assertFalse(other.setSelection(saved))
            } finally {
                other.dispose()
            }
            view.unbind()
            assertNull(view.currentSelectionRange())
            assertFalse(view.setSelection(saved))
            view.bind(session)
            assertTrue(view.setSelection(range(0, 0, 2, 0)))
            val current = view.currentSelectionRange()
            assertFalse(view.setSelection(saved))
            assertSame(current, view.currentSelectionRange())
        }

    @Test
    fun `live reflow and reset invalidate restoration before a frame is published`() =
        fixture("hello", closed = false) {
            val saved = range(0, 0, 5, 0)
            assertTrue(view.setSelection(saved))
            session.resize(5, 2)
            assertFalse(view.setSelection(saved))
            publish()
            assertNull(view.currentSelectionRange())
            val resized = range(0, 0, 5, 0)
            assertTrue(view.setSelection(resized))
            feed("\u001Bc")
            assertFalse(view.setSelection(resized))
            publish()
            assertNull(view.currentSelectionRange())
        }

    @Test
    fun `buffer switches clear selection and prevent restoration to another buffer`() =
        fixture("primary", closed = false) {
            val primary = range(0, 0, 7, 0)
            assertTrue(view.setSelection(primary))
            feed("\u001B[?1049hALT")
            publish()
            assertNull(view.currentSelectionRange())
            assertFalse(view.setSelection(primary))
            val alternate = range(0, 0, 3, 0)
            assertEquals(TerminalRenderBufferKind.ALTERNATE, alternate.buffer)
            assertTrue(view.setSelection(alternate))
            assertEquals("ALT", copiedText())
            feed("\u001B[?1049l")
            publish()
            assertNull(view.currentSelectionRange())
            assertFalse(view.setSelection(alternate))
            assertFalse(view.setSelection(primary))
            assertTrue(view.setSelection(range(0, 0, 7, 0)))
            assertEquals("primary", copiedText())
        }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `eviction clips selected rows and rejects restoration of lost endpoints`(block: Boolean) =
        fixture("one\r\ntwo", closed = false, history = 1) {
            val saved = range(1, 0, 3, 1, block)
            assertTrue(view.setSelection(saved))
            feed("\r\nthree\r\nfour")
            publish()
            val clipped = requireNotNull(view.currentSelectionRange())
            assertEquals(1L, clipped.anchorAbsoluteRow)
            assertEquals(if (block) 1 else 0, clipped.anchorColumn)
            assertEquals(if (block) "wo" else "two", copiedText())
            assertFalse(view.setSelection(saved))
            assertSame(clipped, view.currentSelectionRange())
            feed("\r\nfive")
            publish()
            assertNull(view.currentSelectionRange())
            assertFalse(view.copySelectionToClipboard())
        }

    @Test
    fun `background output cannot make a saved range select replacement rows`() =
        fixture("one", closed = false, history = 0) {
            val saved = range(0, 0, 3, 0)
            val output = FutureTask { feed("\r\ntwo\r\nthree") }
            Thread(output, "selection-output").start()
            output.get(10, TimeUnit.SECONDS)
            assertFalse(view.setSelection(saved))
            assertNull(view.currentSelectionRange())
            assertFalse(view.copySelectionToClipboard())
        }

    @Test
    fun `session closure retains a saved selection without transport work`() =
        fixture("hello", closed = false) {
            val saved = range(0, 0, 5, 0)
            assertTrue(view.setSelection(saved))
            session.onClosed(0)
            dispatcher.scheduler.runCurrent()
            view.clearSelection()
            assertTrue(view.setSelection(saved))
            assertEquals("hello", copiedText())
            assertEquals(0, connector.writes)
        }

    @Test
    fun `ordinary overwrites preserve bounds and copying reads current text`() =
        fixture("hello", closed = false) {
            assertTrue(view.setSelection(range(0, 0, 5, 0)))
            val saved = view.currentSelectionRange()
            var events = 0
            view.addSelectionListener { _, _ -> events++ }
            feed("\rworld")
            assertSame(saved, view.currentSelectionRange())
            assertEquals("world", copiedText())
            assertEquals(0, events)
        }

    @Test
    fun `listeners can read terminal state remove observers and replace selection`() =
        fixture("hello") {
            val events = mutableListOf<String>()
            val replacement = range(1, 0, 3, 0)
            val removed = TerminalSelectionListener { _, _ -> fail("removed listener was called") }
            view.addSelectionListener { _, current ->
                session.readRenderFrame { assertEquals(10, it.columns) }
                events += "first:${current?.anchorColumn}"
                if (current?.anchorColumn == 0) {
                    view.removeSelectionListener(removed)
                    assertTrue(view.setSelection(replacement))
                }
            }
            view.addSelectionListener(removed)
            view.addSelectionListener { _, current -> events += "last:${current?.anchorColumn}" }
            assertTrue(view.setSelection(range(0, 0, 5, 0)))
            assertEquals(listOf("first:0", "first:1", "last:1"), events)
            assertEquals("el", copiedText())
        }

    @Test
    fun `listener cancellation preserves committed selection and disposal still cleans up`() =
        fixture("hello") {
            val failure = CancellationException("listener")
            val listener = TerminalSelectionListener { _, _ -> throw failure }
            view.addSelectionListener(listener)
            assertSame(failure, assertThrows(CancellationException::class.java) { view.setSelection(range(0, 0, 5, 0)) })
            assertEquals("hello", copiedText())
            assertSame(failure, assertThrows(IllegalStateException::class.java) { view.dispose() })
            assertFalse(view.hasActiveRenderBinding)
            assertFalse(view.isCoroutineScopeActive)
            assertNull(view.currentSelectionRange())
            view.removeSelectionListener(listener)
            assertThrows(IllegalStateException::class.java) { view.clearSelection() }
            assertThrows(IllegalStateException::class.java) { view.addSelectionListener(listener) }
        }

    @Test
    fun `ordinary listener failure does not stop later observers or frame consumption`() =
        fixture("one", closed = false, history = 0) {
            var calls = 0
            view.addSelectionListener { _, _ -> throw IllegalArgumentException("expected observer failure") }
            view.addSelectionListener { _, _ -> calls++ }
            assertTrue(view.setSelection(range(0, 0, 3, 0)))
            feed("\r\ntwo\r\nthree")
            publish()
            assertNull(view.currentSelectionRange())
            assertEquals(2, calls)
            assertTrue(view.hasActiveRenderBinding)
        }

    @Test
    fun `mouse selection and select all publish the complete shared range`() =
        fixture("hello") {
            val events = mutableListOf<TerminalSelectionRange?>()
            view.addSelectionListener { _, current -> events += current }
            val bounds = Rectangle()
            assertTrue(view.copyCellBounds(1, 0, bounds))
            val event = MouseEvent(view, MouseEvent.MOUSE_PRESSED, 0, 0, bounds.x, bounds.y, 2, false, MouseEvent.BUTTON1)
            view.mouseListeners.forEach { it.mousePressed(event) }
            assertEquals("hello", copiedText())
            assertEquals(1, events.size)
            assertEquals(0, events.single()?.anchorColumn)
            assertEquals(5, events.single()?.caretColumn)
            view.selectAll()
            assertEquals(2, events.size)
            view.clearSelection()
            assertNull(events.last())
        }

    @Test
    fun `a listener can rebind after unbind cleanup completes`() =
        fixture("hello") {
            view.setSelection(range(0, 0, 5, 0))
            var rebound = false
            val listener =
                TerminalSelectionListener { _, current ->
                    if (current == null && !rebound) {
                        rebound = true
                        view.bind(session)
                        assertTrue(view.setSelection(range(1, 0, 3, 0)))
                    }
                }
            view.addSelectionListener(listener)
            view.unbind()
            assertTrue(rebound)
            assertTrue(view.hasActiveRenderBinding)
            assertEquals("el", copiedText())
            view.removeSelectionListener(listener)
        }

    @Test
    fun `removing and registering the same listener defers it until the next event`() =
        fixture("hello") {
            var calls = 0
            val listener = TerminalSelectionListener { _, _ -> calls++ }
            val first =
                TerminalSelectionListener { _, _ ->
                    view.removeSelectionListener(listener)
                    view.addSelectionListener(listener)
                }
            view.addSelectionListener(first)
            view.addSelectionListener(listener)
            view.setSelection(range(0, 0, 5, 0))
            assertEquals(0, calls)
            view.removeSelectionListener(first)
            view.clearSelection()
            assertEquals(1, calls)
            view.removeSelectionListener(listener)
        }

    @Test
    fun `select all can be saved and restored with live scrollback`() =
        fixture("one\r\ntwo\r\nthree", closed = false) {
            publish()
            assertTrue(view.selectAll())
            val saved = requireNotNull(view.currentSelectionRange())
            val text = copiedText()
            view.clearSelection()
            assertTrue(view.setSelection(saved))
            assertEquals(text, copiedText())
            assertEquals("one\ntwo\nthree", text.trimEnd())
        }

    @Test
    fun `new selection operations reject access outside the EDT`() {
        val view = edt { SwingTerminal() }
        try {
            assertThrows(IllegalStateException::class.java) { view.currentSelectionRange() }
            assertThrows(IllegalStateException::class.java) { view.createSelectionRange(0, 0, 1, 0) }
            assertThrows(IllegalStateException::class.java) { view.clearSelection() }
            assertThrows(IllegalStateException::class.java) { view.addSelectionListener { _, _ -> } }
            assertThrows(IllegalStateException::class.java) { view.removeSelectionListener { _, _ -> } }
        } finally {
            edt { view.dispose() }
        }
    }

    private fun fixture(
        text: String,
        closed: Boolean = true,
        history: Int = 10,
        action: Fixture.() -> Unit,
    ) {
        edt {
            val fixture = Fixture(text, closed, history)
            try {
                fixture.action()
            } finally {
                fixture.view.dispose()
                fixture.session.close()
                fixture.dispatcher.scheduler.runCurrent()
            }
        }
    }

    private class Fixture(
        text: String,
        closed: Boolean,
        history: Int,
    ) {
        val dispatcher = StandardTestDispatcher()
        val connector = RecordingConnector()
        val session =
            TerminalSession.create(
                TerminalBuffers.create(10, 2, history),
                connector,
                workerDispatcher = dispatcher,
                ioDispatcher = dispatcher,
            )
        var clipboardText = ""
        var settings =
            SwingSettings.create {
                it.padding = SwingPadding(0, 0, 0, 0)
                it.alternateScreenPadding = SwingPadding(0, 0, 0, 0)
                it.shellIntegrationDecorationGutterWidth = 0
            }
        val view =
            SwingTerminal(
                { settings },
                SwingHostServices.create {
                    it.clipboardHandler =
                        object : TerminalClipboardHandler {
                            override fun copyText(text: String) {
                                clipboardText = text
                            }

                            override fun readText(): String? = null
                        }
                },
            )

        init {
            session.start(10, 2)
            feed(text)
            if (closed) session.onClosed(0)
            dispatcher.scheduler.runCurrent()
            view.size = view.preferredGridSize(10, 2)
            view.bind(session)
        }

        fun range(
            ac: Int,
            ar: Long,
            cc: Int,
            cr: Long,
            block: Boolean = false,
        ): TerminalSelectionRange = requireNotNull(view.createSelectionRange(ac, ar, cc, cr, block))

        fun copiedText(): String {
            assertTrue(view.copySelectionToClipboard())
            return clipboardText
        }

        fun feed(text: String) {
            val bytes = text.toByteArray()
            session.onBytes(bytes, 0, bytes.size)
        }

        fun publish() {
            session.requestRender(0)
            dispatcher.scheduler.runCurrent()
        }

        fun resize(
            columns: Int,
            rows: Int,
        ) {
            view.size = view.preferredGridSize(columns, rows)
            view.componentListeners.forEach { it.componentResized(ComponentEvent(view, ComponentEvent.COMPONENT_RESIZED)) }
        }
    }

    private class RecordingConnector : TerminalConnector {
        var writes = 0

        override fun start(listener: TerminalConnectorListener) = Unit

        override fun write(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) {
            writes++
        }

        override fun resize(
            columns: Int,
            rows: Int,
        ) = Unit

        override fun close() = Unit
    }

    private fun <T> edt(action: () -> T): T {
        val task = FutureTask(action)
        SwingUtilities.invokeAndWait(task)
        return task.get()
    }
}

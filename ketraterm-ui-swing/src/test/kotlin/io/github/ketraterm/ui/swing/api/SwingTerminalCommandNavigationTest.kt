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
import io.github.ketraterm.input.api.TerminalInputEncoder
import io.github.ketraterm.input.api.TerminalInputEncoderFactory
import io.github.ketraterm.input.event.TerminalFocusEvent
import io.github.ketraterm.input.event.TerminalKeyEvent
import io.github.ketraterm.input.event.TerminalMouseEvent
import io.github.ketraterm.input.event.TerminalPasteEvent
import io.github.ketraterm.parser.api.TerminalOutputParser
import io.github.ketraterm.render.api.*
import io.github.ketraterm.render.cache.TerminalRenderPublisher
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.session.TerminalShellIntegrationCommandRecord
import io.github.ketraterm.session.TerminalShellIntegrationFactory
import io.github.ketraterm.session.TerminalShellIntegrationState
import io.github.ketraterm.transport.TerminalConnector
import io.github.ketraterm.transport.TerminalConnectorListener
import io.github.ketraterm.ui.swing.settings.SwingPadding
import io.github.ketraterm.ui.swing.settings.SwingSettings
import io.github.ketraterm.ui.swing.settings.TerminalClipboardHandler
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.awt.Cursor
import java.awt.event.MouseEvent
import java.util.concurrent.LinkedBlockingQueue
import javax.swing.JComponent
import javax.swing.RepaintManager
import javax.swing.SwingUtilities

class SwingTerminalCommandNavigationTest {
    @Test
    fun `host prompt updates repaint and clear marker hover without another render frame`() {
        SwingUtilities.invokeAndWait {
            HostModelFixture().use { fixture ->
                val generation = fixture.session.renderGeneration.value
                val state = fixture.session.shellIntegrationState
                val repaints = fixture.repaints.count
                fixture.moveToPromptGutter()
                assertEquals(Cursor.DEFAULT_CURSOR, fixture.component.cursor.type)

                state.recordPromptStart(lineIdForAbsoluteRow(6))
                fixture.flush()
                assertTrue(fixture.repaints.count > repaints)
                fixture.moveToPromptGutter()

                assertEquals(Cursor.HAND_CURSOR, fixture.component.cursor.type)
                assertEquals(generation, fixture.session.renderGeneration.value)

                state.clear()
                fixture.flush()

                assertEquals(Cursor.DEFAULT_CURSOR, fixture.component.cursor.type)
                assertEquals(generation, fixture.session.renderGeneration.value)
            }
        }
    }

    @Test
    fun `host command records support navigation selection and output copy without protocol markers`() {
        SwingUtilities.invokeAndWait {
            HostModelFixture().use { fixture ->
                val state = fixture.session.shellIntegrationState
                val generation = fixture.session.renderGeneration.value
                state.recordPromptStart(lineIdForAbsoluteRow(5))
                state.recordPromptEnd(lineIdForAbsoluteRow(5))
                state.recordCommandStart(lineIdForAbsoluteRow(6), true, "host command")
                state.recordCommandFinished(lineIdForAbsoluteRow(7), 2)
                fixture.flush()
                val commandId = state.latestCommandRecordId()

                assertEquals(generation, fixture.session.renderGeneration.value)
                assertEquals(commandId, fixture.component.commandRecordAt(0, 0))
                assertEquals("row6        row7", fixture.component.commandOutputText(commandId))
                assertTrue(fixture.component.selectCommandOutput(commandId))
                assertEquals(CellSelection(0, 0, 12, 1), fixture.component.currentSelection())
                assertTrue(fixture.component.copyCommandTextToClipboard(commandId))
                assertEquals("host command", fixture.clipboard)
                assertTrue(fixture.component.copyCommandOutputToClipboard(commandId))
                assertEquals("row6        row7", fixture.clipboard)
                fixture.component.scrollToPreviousCommand()
                fixture.flush()
                assertEquals(1, fixture.component.viewportState().renderOffset)
            }
        }
    }

    @ParameterizedTest
    @EnumSource(HostBindingEnd::class)
    fun `host metadata observation ends with its binding lifecycle`(end: HostBindingEnd) {
        SwingUtilities.invokeAndWait {
            HostModelFixture().use { fixture ->
                val originalSession = fixture.session
                when (end) {
                    HostBindingEnd.UNBIND -> fixture.component.unbind()
                    HostBindingEnd.REBIND -> fixture.component.bind(fixture.replacementSession)
                    HostBindingEnd.DISPOSE -> fixture.component.dispose()
                    HostBindingEnd.SESSION_CLOSE -> originalSession.close()
                }
                fixture.flush()
                val repaints = fixture.repaints.count

                originalSession.shellIntegrationState.recordPromptStart(lineIdForAbsoluteRow(6))
                fixture.flush()

                assertEquals(repaints, fixture.repaints.count)
                if (end != HostBindingEnd.DISPOSE) {
                    fixture.moveToPromptGutter()
                    assertEquals(Cursor.DEFAULT_CURSOR, fixture.component.cursor.type)
                }
                if (end == HostBindingEnd.UNBIND || end == HostBindingEnd.DISPOSE) {
                    assertFalse(fixture.component.hasActiveRenderBinding)
                }
            }
        }
    }

    enum class HostBindingEnd {
        UNBIND,
        REBIND,
        DISPOSE,
        SESSION_CLOSE,
    }

    @Test
    fun `previous command from inside command output reveals current command prompt`() {
        val reader = CommandFrameReader()
        val session = commandSession(reader)
        val component = SwingTerminal(settingsProvider = { SwingSettings(padding = SwingPadding(0, 0, 0, 0)) })

        SwingUtilities.invokeAndWait {
            component.size = component.preferredGridSize(12, 2)
            component.bind(session)
            component.scrollToPreviousCommand()
        }
        SwingUtilities.invokeAndWait {}

        assertEquals(1, component.viewportState().renderOffset)
        session.close()
    }

    @Test
    fun `next command from inside command output reveals following command prompt`() {
        val reader = CommandFrameReader()
        val session = commandSession(reader)
        val component = SwingTerminal(settingsProvider = { SwingSettings(padding = SwingPadding(0, 0, 0, 0)) })

        SwingUtilities.invokeAndWait {
            component.size = component.preferredGridSize(12, 2)
            component.bind(session)
            component.scrollToScrollbackOffset(4)
            component.scrollToNextCommand()
        }
        SwingUtilities.invokeAndWait {}

        assertEquals(1, component.viewportState().renderOffset)
        session.close()
    }

    @Test
    fun `command navigation from prompt only row skips prompt only record`() {
        val reader = CommandFrameReader()
        val session = commandSession(reader)
        val component = SwingTerminal(settingsProvider = { SwingSettings(padding = SwingPadding(0, 0, 0, 0)) })

        SwingUtilities.invokeAndWait {
            component.size = component.preferredGridSize(12, 2)
            component.bind(session)
            component.scrollToScrollbackOffset(2)
            component.scrollToPreviousCommand()
        }

        assertEquals(5, component.viewportState().renderOffset)

        SwingUtilities.invokeAndWait {
            component.scrollToScrollbackOffset(2)
            component.scrollToNextCommand()
        }

        assertEquals(1, component.viewportState().renderOffset)
        session.close()
    }

    @Test
    fun `command navigation before first and after last command is a no op`() {
        val reader = CommandFrameReader()
        val session = commandSession(reader)
        val component = SwingTerminal(settingsProvider = { SwingSettings(padding = SwingPadding(0, 0, 0, 0)) })

        SwingUtilities.invokeAndWait {
            component.size = component.preferredGridSize(12, 2)
            component.bind(session)
            component.scrollToScrollbackOffset(6)
            component.scrollToPreviousCommand()
        }

        assertEquals(6, component.viewportState().renderOffset)

        SwingUtilities.invokeAndWait {
            component.scrollToScrollbackOffset(0)
            component.scrollToNextCommand()
        }

        assertEquals(0, component.viewportState().renderOffset)
        session.close()
    }

    @Test
    fun `command navigation ignores evicted command records`() {
        val reader = CommandFrameReader()
        val session = commandSession(reader, capacity = 1)
        val component = SwingTerminal(settingsProvider = { SwingSettings(padding = SwingPadding(0, 0, 0, 0)) })

        SwingUtilities.invokeAndWait {
            component.size = component.preferredGridSize(12, 2)
            component.bind(session)
            component.scrollToScrollbackOffset(4)
            component.scrollToPreviousCommand()
        }

        assertEquals(4, component.viewportState().renderOffset)

        SwingUtilities.invokeAndWait {
            component.scrollToNextCommand()
        }

        assertEquals(1, component.viewportState().renderOffset)
        session.close()
    }

    @Test
    fun `command hit testing returns command records for prompt and output rows`() {
        val reader = CommandFrameReader()
        val session = commandSession(reader)
        val component = SwingTerminal(settingsProvider = { SwingSettings(padding = SwingPadding(0, 0, 0, 0)) })
        val firstCommandId = session.shellIntegrationState.commandRecordIdAtLine(lineIdForAbsoluteRow(3))
        val secondCommandId = session.shellIntegrationState.commandRecordIdAtLine(lineIdForAbsoluteRow(6))

        SwingUtilities.invokeAndWait {
            component.size = component.preferredGridSize(12, 2)
            component.bind(session)

            assertEquals(secondCommandId, component.commandRecordAt(0, 0))
            assertEquals(secondCommandId, component.commandRecordAt(0, component.height - 1))

            component.scrollToScrollbackOffset(3)
            assertEquals(firstCommandId, component.commandRecordAt(0, 0))
            assertEquals(TerminalShellIntegrationCommandRecord.NONE, component.commandRecordAt(0, component.height - 1))

            component.scrollToScrollbackOffset(1)
            assertEquals(secondCommandId, component.commandRecordAt(0, 0))
            assertEquals(secondCommandId, component.commandRecordAt(0, component.height - 1))
        }

        session.close()
    }

    @Test
    fun `select command output excludes prompt input line for exclusive command start`() {
        val reader = CommandFrameReader()
        val session = commandSession(reader)
        val component = SwingTerminal(settingsProvider = { SwingSettings(padding = SwingPadding(0, 0, 0, 0)) })
        val firstCommandId = session.shellIntegrationState.commandRecordIdAtLine(lineIdForAbsoluteRow(1))

        SwingUtilities.invokeAndWait {
            component.size = component.preferredGridSize(12, 2)
            component.bind(session)

            assertEquals(true, component.selectCommandOutput(firstCommandId))
            assertEquals(3, component.viewportState().renderOffset)
            assertEquals(CellSelection(0, 0, 12, 0), component.currentSelection())
        }

        session.close()
    }

    @Test
    fun `select command output includes start line for inclusive command start`() {
        val reader = CommandFrameReader()
        val session = commandSession(reader)
        val component = SwingTerminal(settingsProvider = { SwingSettings(padding = SwingPadding(0, 0, 0, 0)) })
        val secondCommandId = session.shellIntegrationState.commandRecordIdAtLine(lineIdForAbsoluteRow(6))

        SwingUtilities.invokeAndWait {
            component.size = component.preferredGridSize(12, 2)
            component.bind(session)

            assertEquals(true, component.selectCommandOutput(secondCommandId))
            assertEquals(0, component.viewportState().renderOffset)
            assertEquals(CellSelection(0, 0, 12, 1), component.currentSelection())
        }

        session.close()
    }

    @Test
    fun `select command output at prompt only row is a no op`() {
        val reader = CommandFrameReader()
        val session = commandSession(reader)
        val component = SwingTerminal(settingsProvider = { SwingSettings(padding = SwingPadding(0, 0, 0, 0)) })

        SwingUtilities.invokeAndWait {
            component.size = component.preferredGridSize(12, 2)
            component.bind(session)
            component.scrollToScrollbackOffset(2)

            assertEquals(false, component.selectCommandOutputAt(0, 0))
            assertEquals(null, component.currentSelection())
        }

        session.close()
    }

    @Test
    fun `clicking a silent command prompt marker selects its prompt without the following prompt`() {
        val reader = CommandFrameReader()
        val session = commandSession(reader, firstCommandHasOutput = false)
        val padding = SwingPadding(8, 12, 8, 8)
        val component = SwingTerminal(settingsProvider = { SwingSettings(padding = padding) })

        SwingUtilities.invokeAndWait {
            component.size = component.preferredGridSize(12, 2)
            component.bind(session)
            component.scrollToScrollbackOffset(HISTORY_SIZE)
            val click =
                MouseEvent(
                    component,
                    MouseEvent.MOUSE_PRESSED,
                    1L,
                    0,
                    padding.left,
                    component.height - padding.bottom - 2,
                    1,
                    false,
                    MouseEvent.BUTTON1,
                )

            component.dispatchEvent(click)

            assertEquals(true, click.isConsumed)
            assertEquals(5, component.viewportState().renderOffset)
            assertEquals(CellSelection(0, 0, 12, 0), component.currentSelection())
        }

        session.close()
    }

    @Test
    fun `select command output ignores evicted command record`() {
        val reader = CommandFrameReader()
        val session = commandSession(reader, capacity = 1)
        val component = SwingTerminal(settingsProvider = { SwingSettings(padding = SwingPadding(0, 0, 0, 0)) })
        val evictedCommandId = 1

        SwingUtilities.invokeAndWait {
            component.size = component.preferredGridSize(12, 2)
            component.bind(session)

            assertEquals(false, component.selectCommandOutput(evictedCommandId))
            assertEquals(null, component.currentSelection())
        }

        session.close()
    }

    @Test
    fun `command output text preserves hard breaks and joins soft wraps`() {
        val reader = CommandFrameReader()
        val session = commandSession(reader)
        val component = SwingTerminal(settingsProvider = { SwingSettings(padding = SwingPadding(0, 0, 0, 0)) })
        val secondCommandId = session.shellIntegrationState.commandRecordIdAtLine(lineIdForAbsoluteRow(6))

        SwingUtilities.invokeAndWait {
            component.size = component.preferredGridSize(12, 2)
            component.bind(session)
            assertEquals("row6        row7", component.commandOutputText(secondCommandId))
        }

        session.close()
    }

    @Test
    fun `command output copy and export preserve spaces at soft wraps while scrolled away`() {
        val reader = CommandFrameReader(textByAbsoluteRow = mapOf(6 to "working dir ", 7 to "is project"))
        val session = commandSession(reader)
        var clipboardText: String? = null
        val component =
            SwingTerminal(
                settingsProvider = { SwingSettings(padding = SwingPadding()) },
                hostServices =
                    SwingHostServices(
                        clipboardHandler =
                            object : TerminalClipboardHandler {
                                override fun copyText(text: String) {
                                    clipboardText = text
                                }

                                override fun readText(): String? = clipboardText
                            },
                    ),
            )
        val secondCommandId = session.shellIntegrationState.commandRecordIdAtLine(lineIdForAbsoluteRow(6))
        try {
            SwingUtilities.invokeAndWait {
                component.size = component.preferredGridSize(12, 2)
                component.bind(session)
                component.scrollToScrollbackOffset(HISTORY_SIZE)
                assertEquals(HISTORY_SIZE, component.viewportState().renderOffset)

                val exported = component.commandOutputText(secondCommandId)
                assertTrue(component.copyCommandOutputToClipboard(secondCommandId))
                assertAll(
                    { assertEquals("working dir is project", exported) },
                    { assertEquals("working dir is project", clipboardText) },
                    { assertEquals(HISTORY_SIZE, component.viewportState().renderOffset) },
                )
            }
        } finally {
            SwingUtilities.invokeAndWait { component.dispose() }
            session.close()
        }
    }

    @Test
    fun `clicking prompt marker gutter selects its command block`() {
        val reader = CommandFrameReader()
        val session = commandSession(reader)
        val padding = SwingPadding(8, 12, 8, 8)
        val component = SwingTerminal(settingsProvider = { SwingSettings(padding = padding) })

        SwingUtilities.invokeAndWait {
            component.size = component.preferredGridSize(12, 2)
            component.bind(session)
            component.scrollToScrollbackOffset(1)
            val click =
                MouseEvent(
                    component,
                    MouseEvent.MOUSE_PRESSED,
                    1L,
                    0,
                    padding.left,
                    padding.top + 2,
                    1,
                    false,
                    MouseEvent.BUTTON1,
                )
            component.dispatchEvent(click)

            assertEquals(true, click.isConsumed)
            assertEquals(CellSelection(0, 0, 12, 1), component.currentSelection())
        }

        session.close()
    }

    @Test
    fun `hovering prompt marker exposes hand cursor across full gutter hit target`() {
        val reader = CommandFrameReader()
        val session = commandSession(reader)
        val padding = SwingPadding(8, 12, 8, 8)
        val component = SwingTerminal(settingsProvider = { SwingSettings(padding = padding) })

        SwingUtilities.invokeAndWait {
            component.size = component.preferredGridSize(12, 2)
            component.bind(session)
            component.scrollToScrollbackOffset(1)

            component.dispatchEvent(
                MouseEvent(
                    component,
                    MouseEvent.MOUSE_MOVED,
                    1L,
                    0,
                    padding.left,
                    padding.top + 2,
                    0,
                    false,
                ),
            )
            assertEquals(Cursor.HAND_CURSOR, component.cursor.type)

            component.dispatchEvent(
                MouseEvent(
                    component,
                    MouseEvent.MOUSE_MOVED,
                    2L,
                    0,
                    padding.left + 15,
                    padding.top + 2,
                    0,
                    false,
                ),
            )
            assertEquals(Cursor.HAND_CURSOR, component.cursor.type)

            component.dispatchEvent(
                MouseEvent(
                    component,
                    MouseEvent.MOUSE_MOVED,
                    3L,
                    0,
                    padding.left + 16,
                    padding.top + 2,
                    0,
                    false,
                ),
            )
            assertEquals(Cursor.DEFAULT_CURSOR, component.cursor.type)
        }

        session.close()
    }

    private fun commandSession(
        renderReader: TerminalRenderFrameReader,
        capacity: Int = 8,
        firstCommandHasOutput: Boolean = true,
        populate: Boolean = true,
        workerDispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
    ): TerminalSession {
        val terminal = TerminalBuffers.create(width = 12, height = 2, maxHistory = HISTORY_SIZE)
        val session =
            TerminalSession(
                terminal = terminal,
                renderPublisher = TerminalRenderPublisher(12, 2),
                renderReader = renderReader,
                responseReader = terminal,
                connector = NoOpConnector,
                parser = NoOpParser,
                inputEncoderFactory = TerminalInputEncoderFactory { _, _, _ -> object : TerminalInputEncoder by NoOpInputEncoder {} },
                shellIntegration =
                    TerminalShellIntegrationFactory.host(TerminalShellIntegrationState(capacity = capacity)),
                workerDispatcher = workerDispatcher,
                ioDispatcher = workerDispatcher,
            )
        if (populate) {
            val state = session.shellIntegrationState
            state.recordPromptStart(lineIdForAbsoluteRow(1))
            state.recordPromptEnd(lineIdForAbsoluteRow(1))
            if (firstCommandHasOutput) {
                state.recordCommandStart(lineIdForAbsoluteRow(2), includeLine = false)
                state.recordCommandFinished(lineIdForAbsoluteRow(3), exitCode = 0)
            } else {
                state.recordCommandStart(lineIdForAbsoluteRow(1), includeLine = false)
                state.recordCommandFinished(lineIdForAbsoluteRow(2), exitCode = 0)
                state.recordPromptStart(lineIdForAbsoluteRow(2))
            }
            state.recordPromptStart(lineIdForAbsoluteRow(4))
            state.recordPromptStart(lineIdForAbsoluteRow(5))
            state.recordPromptEnd(lineIdForAbsoluteRow(5))
            state.recordCommandStart(lineIdForAbsoluteRow(6), includeLine = true)
            state.recordCommandFinished(lineIdForAbsoluteRow(7), exitCode = 1)
        }
        session.renderPublisher.updateAndPublish(renderReader)
        return session
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private inner class HostModelFixture : AutoCloseable {
        private val worker = StandardTestDispatcher()
        private val dispatches = LinkedBlockingQueue<Runnable>()
        private val padding = SwingPadding(8, 12, 8, 8)
        private val previousRepaints = RepaintManager.currentManager(null)
        val repaints = RecordingRepaints(previousRepaints)
        var clipboard: String? = null
        val session = commandSession(CommandFrameReader(), populate = false, workerDispatcher = worker)
        val replacementSession = commandSession(CommandFrameReader(), populate = false, workerDispatcher = worker)
        val component =
            SwingTerminal(
                settingsProvider = { SwingSettings(padding = padding, cursorBlinkMillis = 0, useSystemFallbackFonts = false) },
                hostServices =
                    SwingHostServices(
                        uiDispatcher = { dispatches += it },
                        clipboardHandler =
                            object : TerminalClipboardHandler {
                                override fun copyText(text: String) {
                                    clipboard = text
                                }

                                override fun readText(): String? = clipboard
                            },
                    ),
            )

        init {
            check(SwingUtilities.isEventDispatchThread())
            repaints.target = component
            RepaintManager.setCurrentManager(repaints)
            component.size = component.preferredGridSize(12, 2)
            component.bind(session)
            flush()
        }

        fun flush() {
            do {
                worker.scheduler.runCurrent()
                var dispatched = false
                while (true) {
                    val runnable = dispatches.poll() ?: break
                    dispatched = true
                    runnable.run()
                }
            } while (dispatched)
        }

        fun moveToPromptGutter() {
            component.dispatchEvent(
                MouseEvent(component, MouseEvent.MOUSE_MOVED, 0, 0, padding.left, padding.top + 2, 0, false),
            )
        }

        override fun close() {
            try {
                component.dispose()
                session.close()
                replacementSession.close()
                flush()
            } finally {
                RepaintManager.setCurrentManager(previousRepaints)
            }
        }
    }

    private class RecordingRepaints(
        private val delegate: RepaintManager,
    ) : RepaintManager() {
        var target: JComponent? = null
        var count = 0

        override fun addDirtyRegion(
            component: JComponent,
            x: Int,
            y: Int,
            width: Int,
            height: Int,
        ) {
            if (component === target) count++
            delegate.addDirtyRegion(component, x, y, width, height)
        }
    }

    private class CommandFrameReader(
        private val textByAbsoluteRow: Map<Int, String> = emptyMap(),
    ) : TerminalRenderFrameReader {
        override fun readRenderFrame(consumer: TerminalRenderFrameConsumer) {
            readRenderFrame(scrollbackOffset = 0, viewportRows = 2, consumer = consumer)
        }

        override fun readRenderFrame(
            scrollbackOffset: Int,
            consumer: TerminalRenderFrameConsumer,
        ) {
            readRenderFrame(scrollbackOffset, viewportRows = 2, consumer)
        }

        override fun readRenderFrame(
            scrollbackOffset: Int,
            viewportRows: Int,
            consumer: TerminalRenderFrameConsumer,
        ) {
            consumer.accept(CommandFrame(scrollbackOffset.coerceIn(0, HISTORY_SIZE), viewportRows.coerceAtLeast(1), textByAbsoluteRow))
        }
    }

    private class CommandFrame(
        override val scrollbackOffset: Int,
        override val rows: Int,
        private val textByAbsoluteRow: Map<Int, String>,
    ) : TerminalRenderFrame {
        override val columns: Int = 12
        override val historySize: Int = HISTORY_SIZE
        override val frameGeneration: Long = 1
        override val structureGeneration: Long = 1
        override val activeBuffer: TerminalRenderBufferKind = TerminalRenderBufferKind.PRIMARY
        override val cursor: TerminalRenderCursor =
            TerminalRenderCursor(
                column = 0,
                row = 0,
                visible = false,
                blinking = false,
                shape = TerminalRenderCursorShape.BLOCK,
                generation = 1,
            )

        override fun lineGeneration(row: Int): Long = 1

        override fun lineId(row: Int): Long = lineIdForAbsoluteRow(HISTORY_SIZE - scrollbackOffset + row)

        override fun lineWrapped(row: Int): Boolean = HISTORY_SIZE - scrollbackOffset + row == 6

        override fun copyLine(
            row: Int,
            codeWords: IntArray,
            codeOffset: Int,
            attrWords: LongArray,
            attrOffset: Int,
            flags: IntArray,
            flagOffset: Int,
            extraAttrWords: LongArray?,
            extraAttrOffset: Int,
            hyperlinkIds: IntArray?,
            hyperlinkOffset: Int,
            clusterSink: TerminalRenderClusterSink?,
            clusterDataSink: TerminalRenderClusterDataSink?,
        ) {
            var column = 0
            val absoluteRow = HISTORY_SIZE - scrollbackOffset + row
            val text = textByAbsoluteRow[absoluteRow] ?: "row$absoluteRow"
            while (column < columns) {
                codeWords[codeOffset + column] = if (column < text.length) text[column].code else 0
                attrWords[attrOffset + column] = TerminalRenderAttrs.DEFAULT
                flags[flagOffset + column] =
                    if (column < text.length) TerminalRenderCellFlags.CODEPOINT else TerminalRenderCellFlags.EMPTY
                extraAttrWords?.set(extraAttrOffset + column, TerminalRenderExtraAttrs.DEFAULT)
                hyperlinkIds?.set(hyperlinkOffset + column, 0)
                column++
            }
        }
    }

    private object NoOpConnector : TerminalConnector {
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

    private object NoOpParser : TerminalOutputParser {
        override fun accept(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) = Unit

        override fun acceptByte(byteValue: Int) = Unit

        override fun endOfInput() = Unit

        override fun reset() = Unit
    }

    private object NoOpInputEncoder : TerminalInputEncoder {
        override fun setInputPolicy(policy: io.github.ketraterm.input.policy.TerminalInputPolicy) = Unit

        override fun encodeKey(event: TerminalKeyEvent) = Unit

        override fun encodePaste(event: TerminalPasteEvent) = Unit

        override fun encodeFocus(event: TerminalFocusEvent) = Unit

        override fun encodeMouse(event: TerminalMouseEvent) = Unit
    }

    private companion object {
        private const val HISTORY_SIZE = 6
        private const val FIRST_LINE_ID = 100L

        private fun lineIdForAbsoluteRow(absoluteRow: Int): Long = FIRST_LINE_ID + absoluteRow
    }
}

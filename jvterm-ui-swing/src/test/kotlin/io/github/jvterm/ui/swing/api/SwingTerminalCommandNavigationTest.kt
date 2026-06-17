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
package io.github.jvterm.ui.swing.api

import io.github.jvterm.core.TerminalBuffers
import io.github.jvterm.input.api.TerminalInputEncoder
import io.github.jvterm.input.event.TerminalFocusEvent
import io.github.jvterm.input.event.TerminalKeyEvent
import io.github.jvterm.input.event.TerminalMouseEvent
import io.github.jvterm.input.event.TerminalPasteEvent
import io.github.jvterm.parser.api.TerminalOutputParser
import io.github.jvterm.render.api.*
import io.github.jvterm.render.cache.TerminalRenderPublisher
import io.github.jvterm.session.TerminalSession
import io.github.jvterm.transport.TerminalConnector
import io.github.jvterm.transport.TerminalConnectorListener
import io.github.jvterm.ui.swing.settings.SwingSettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.awt.Insets
import javax.swing.SwingUtilities

class SwingTerminalCommandNavigationTest {
    @Test
    fun `previous command from inside command output reveals current command prompt`() {
        val reader = CommandFrameReader()
        val session = commandSession(reader)
        val component = SwingTerminal(settingsProvider = { SwingSettings(padding = Insets(0, 0, 0, 0)) })

        SwingUtilities.invokeAndWait {
            component.setSize(120, 40)
            component.bind(session)
            component.scrollToPreviousCommand()
        }

        assertEquals(1, reader.lastRequestedOffset)
        assertEquals(1, component.viewportState().renderOffset)
        session.close()
    }

    @Test
    fun `next command from inside command output reveals following command prompt`() {
        val reader = CommandFrameReader()
        val session = commandSession(reader)
        val component = SwingTerminal(settingsProvider = { SwingSettings(padding = Insets(0, 0, 0, 0)) })

        SwingUtilities.invokeAndWait {
            component.setSize(120, 40)
            component.bind(session)
            component.scrollToScrollbackOffset(4)
            component.scrollToNextCommand()
        }

        assertEquals(1, reader.lastRequestedOffset)
        assertEquals(1, component.viewportState().renderOffset)
        session.close()
    }

    @Test
    fun `command navigation from prompt only row skips prompt only record`() {
        val reader = CommandFrameReader()
        val session = commandSession(reader)
        val component = SwingTerminal(settingsProvider = { SwingSettings(padding = Insets(0, 0, 0, 0)) })

        SwingUtilities.invokeAndWait {
            component.setSize(120, 40)
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
        val component = SwingTerminal(settingsProvider = { SwingSettings(padding = Insets(0, 0, 0, 0)) })

        SwingUtilities.invokeAndWait {
            component.setSize(120, 40)
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
        val component = SwingTerminal(settingsProvider = { SwingSettings(padding = Insets(0, 0, 0, 0)) })

        SwingUtilities.invokeAndWait {
            component.setSize(120, 40)
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

    private fun commandSession(
        renderReader: TerminalRenderFrameReader,
        capacity: Int = 8,
    ): TerminalSession {
        val terminal = TerminalBuffers.create(width = 12, height = 2, maxHistory = HISTORY_SIZE)
        val session =
            TerminalSession(
                terminal = terminal,
                publisher = TerminalRenderPublisher(12, 2),
                renderReader = renderReader,
                responseReader = terminal,
                connector = NoOpConnector,
                parser = NoOpParser,
                inputEncoder = NoOpInputEncoder,
                shellIntegrationState =
                    io.github.jvterm.session
                        .TerminalShellIntegrationState(capacity = capacity),
            )
        val state = session.shellIntegrationState
        state.recordPromptStart(lineIdForAbsoluteRow(1))
        state.recordPromptEnd(lineIdForAbsoluteRow(1))
        state.recordCommandStart(lineIdForAbsoluteRow(2), includeLine = true)
        state.recordCommandFinished(lineIdForAbsoluteRow(3), exitCode = 0)
        state.recordPromptStart(lineIdForAbsoluteRow(4))
        state.recordPromptStart(lineIdForAbsoluteRow(5))
        state.recordPromptEnd(lineIdForAbsoluteRow(5))
        state.recordCommandStart(lineIdForAbsoluteRow(6), includeLine = true)
        state.recordCommandFinished(lineIdForAbsoluteRow(7), exitCode = 1)
        return session
    }

    private class CommandFrameReader : TerminalRenderFrameReader {
        @Volatile
        var lastRequestedOffset: Int = -1
            private set

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
            lastRequestedOffset = scrollbackOffset
            consumer.accept(CommandFrame(scrollbackOffset.coerceIn(0, HISTORY_SIZE), viewportRows.coerceAtLeast(1)))
        }
    }

    private class CommandFrame(
        override val scrollbackOffset: Int,
        override val rows: Int,
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

        override fun lineWrapped(row: Int): Boolean = false

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
            while (column < columns) {
                codeWords[codeOffset + column] = 0
                attrWords[attrOffset + column] = TerminalRenderAttrs.DEFAULT
                flags[flagOffset + column] = TerminalRenderCellFlags.EMPTY
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

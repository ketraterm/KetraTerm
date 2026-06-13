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
import io.github.jvterm.ui.swing.settings.TerminalSwingSettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Insets
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.SwingUtilities

class TerminalSwingTerminalSearchTest {
    @Test
    fun `ctrl shift f opens search overlay without sending terminal input`() {
        val input = RecordingInputEncoder()
        val reader = SearchFrameReader()
        val session = testSession(reader, input)
        val component = TerminalSwingTerminal(settingsProvider = { TerminalSwingSettings(padding = Insets(0, 0, 0, 0)) })

        SwingUtilities.invokeAndWait {
            component.setSize(120, 40)
            component.bind(session)
            component.keyListeners.forEach { listener ->
                listener.keyPressed(searchShortcut(component))
            }
        }

        assertTrue(component.currentSearchState().visible)
        assertEquals(0, input.keyCount)
        session.close()
    }

    @Test
    fun `search scrolls active scrollback result into viewport`() {
        val reader = SearchFrameReader()
        val session = testSession(reader)
        val component = TerminalSwingTerminal(settingsProvider = { TerminalSwingSettings(padding = Insets(0, 0, 0, 0)) })

        SwingUtilities.invokeAndWait {
            component.setSize(120, 24)
            component.bind(session)
            component.search("needle")
        }

        val state = component.currentSearchState()
        assertEquals(1, state.resultCount)
        assertEquals(0, state.activeResultIndex)
        assertTrue(reader.lastRequestedOffset > 0, "search did not request a scrollback viewport")
        session.close()
    }

    private fun testSession(
        renderReader: TerminalRenderFrameReader,
        inputEncoder: TerminalInputEncoder = NoOpInputEncoder,
    ): TerminalSession {
        val terminal = TerminalBuffers.create(width = 12, height = 1, maxHistory = 5)
        return TerminalSession(
            terminal = terminal,
            publisher = TerminalRenderPublisher(12, 1),
            renderReader = renderReader,
            responseReader = terminal,
            connector = NoOpConnector,
            parser = NoOpParser,
            inputEncoder = inputEncoder,
        )
    }

    private fun searchShortcut(component: TerminalSwingTerminal): KeyEvent =
        KeyEvent(
            component,
            KeyEvent.KEY_PRESSED,
            System.currentTimeMillis(),
            InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK,
            KeyEvent.VK_F,
            KeyEvent.CHAR_UNDEFINED,
        )

    private class SearchFrameReader : TerminalRenderFrameReader {
        @Volatile
        var lastRequestedOffset: Int = 0
            private set

        override fun readRenderFrame(consumer: TerminalRenderFrameConsumer) {
            readRenderFrame(scrollbackOffset = 0, viewportRows = 1, consumer = consumer)
        }

        override fun readRenderFrame(
            scrollbackOffset: Int,
            consumer: TerminalRenderFrameConsumer,
        ) {
            readRenderFrame(scrollbackOffset, viewportRows = 1, consumer)
        }

        override fun readRenderFrame(
            scrollbackOffset: Int,
            viewportRows: Int,
            consumer: TerminalRenderFrameConsumer,
        ) {
            lastRequestedOffset = scrollbackOffset
            consumer.accept(SearchFrame(scrollbackOffset = scrollbackOffset.coerceIn(0, 5), rows = viewportRows.coerceAtLeast(1)))
        }
    }

    private class SearchFrame(
        override val scrollbackOffset: Int,
        override val rows: Int,
    ) : TerminalRenderFrame {
        override val columns: Int = 12
        override val historySize: Int = 5
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
            val absoluteRow = historySize - scrollbackOffset + row
            val text = if (absoluteRow == 0) "needle" else ""
            var column = 0
            while (column < columns) {
                attrWords[attrOffset + column] = TerminalRenderAttrs.DEFAULT
                extraAttrWords?.set(extraAttrOffset + column, TerminalRenderExtraAttrs.DEFAULT)
                hyperlinkIds?.set(hyperlinkOffset + column, 0)
                if (column < text.length) {
                    codeWords[codeOffset + column] = text[column].code
                    flags[flagOffset + column] = TerminalRenderCellFlags.CODEPOINT
                } else {
                    codeWords[codeOffset + column] = 0
                    flags[flagOffset + column] = TerminalRenderCellFlags.EMPTY
                }
                column++
            }
        }
    }

    private class RecordingInputEncoder : TerminalInputEncoder {
        var keyCount: Int = 0
            private set

        override fun encodeKey(event: TerminalKeyEvent) {
            keyCount++
        }

        override fun encodePaste(event: TerminalPasteEvent) = Unit

        override fun encodeFocus(event: TerminalFocusEvent) = Unit

        override fun encodeMouse(event: TerminalMouseEvent) = Unit
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
}

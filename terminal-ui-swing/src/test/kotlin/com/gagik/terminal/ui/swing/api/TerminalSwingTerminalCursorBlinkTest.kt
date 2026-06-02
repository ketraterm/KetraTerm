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
package com.gagik.terminal.ui.swing.api

import com.gagik.core.TerminalBuffers
import com.gagik.parser.api.TerminalOutputParser
import com.gagik.terminal.input.api.TerminalInputEncoder
import com.gagik.terminal.input.event.TerminalFocusEvent
import com.gagik.terminal.input.event.TerminalKeyEvent
import com.gagik.terminal.input.event.TerminalMouseEvent
import com.gagik.terminal.input.event.TerminalPasteEvent
import com.gagik.terminal.render.api.*
import com.gagik.terminal.render.cache.TerminalRenderPublisher
import com.gagik.terminal.session.TerminalSession
import com.gagik.terminal.transport.TerminalConnector
import com.gagik.terminal.transport.TerminalConnectorListener
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.event.KeyEvent
import javax.swing.JFrame
import javax.swing.SwingUtilities

class TerminalSwingTerminalCursorBlinkTest {
    @Test
    fun `key events and frame updates reset cursor blinking timer`() {
        val terminal = TerminalBuffers.create(width = 3, height = 1, maxHistory = 5)
        val renderReader = SimpleFrameReader()
        val session =
            TerminalSession(
                terminal = terminal,
                publisher = TerminalRenderPublisher(3, 1),
                renderReader = renderReader,
                responseReader = terminal,
                connector = NoOpConnector,
                parser = NoOpParser,
                inputEncoder = NoOpInputEncoder,
            )
        val component = TerminalSwingTerminal()

        val frame = JFrame()
        try {
            SwingUtilities.invokeAndWait {
                frame.add(component)
                frame.pack()
                component.bind(session)
            }

            // Verify cursor timer is started when component is added
            assertTrue(component.cursorTimer.isRunning)

            // 1. Manually set cursorBlinkVisible to false to simulate a blinked-out phase
            SwingUtilities.invokeAndWait {
                component.cursorBlinkVisible = false
            }
            assertFalse(component.cursorBlinkVisible)

            // 2. Dispatch a key event and verify cursorBlinkVisible becomes true
            SwingUtilities.invokeAndWait {
                val keyEvent =
                    KeyEvent(
                        component,
                        KeyEvent.KEY_PRESSED,
                        System.currentTimeMillis(),
                        0,
                        KeyEvent.VK_A,
                        'A',
                    )
                for (listener in component.keyListeners) {
                    listener.keyPressed(keyEvent)
                }
            }
            assertTrue(component.cursorBlinkVisible)

            // 3. Set cursorBlinkVisible to false again
            SwingUtilities.invokeAndWait {
                component.cursorBlinkVisible = false
            }
            assertFalse(component.cursorBlinkVisible)

            // 4. Trigger a session dirty update to simulate a frame update
            session.requestRender(scrollbackOffset = 0)
            val deadline = System.nanoTime() + 1_000_000_000L
            while (System.nanoTime() < deadline) {
                if (session.publisher.current() != null) break
                Thread.sleep(10)
            }

            SwingUtilities.invokeAndWait {
                session.onDirty?.invoke()
            }
            SwingUtilities.invokeAndWait {
                // Drain dirty runnable
            }

            // Verify cursorBlinkVisible resets back to true on frame updates
            assertTrue(component.cursorBlinkVisible)
        } finally {
            SwingUtilities.invokeAndWait {
                frame.dispose()
            }
            session.close()
        }
    }

    private class SimpleFrameReader : TerminalRenderFrameReader {
        override fun readRenderFrame(consumer: TerminalRenderFrameConsumer) {
            readRenderFrame(scrollbackOffset = 0, consumer = consumer)
        }

        override fun readRenderFrame(
            scrollbackOffset: Int,
            consumer: TerminalRenderFrameConsumer,
        ) {
            consumer.accept(SimpleFrame())
        }

        override fun readRenderFrame(
            scrollbackOffset: Int,
            viewportRows: Int,
            consumer: TerminalRenderFrameConsumer,
        ) {
            consumer.accept(SimpleFrame())
        }
    }

    private class SimpleFrame : TerminalRenderFrame {
        override val columns: Int = 3
        override val rows: Int = 1
        override val historySize: Int = 0
        override val frameGeneration: Long = 1
        override val structureGeneration: Long = 1
        override val activeBuffer: TerminalRenderBufferKind = TerminalRenderBufferKind.PRIMARY
        override val scrollbackOffset: Int = 0
        override val cursor: TerminalRenderCursor =
            TerminalRenderCursor(
                column = 0,
                row = 0,
                visible = true,
                blinking = true,
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
}

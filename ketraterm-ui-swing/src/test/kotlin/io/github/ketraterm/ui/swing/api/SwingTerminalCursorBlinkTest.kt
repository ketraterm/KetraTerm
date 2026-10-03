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
import io.github.ketraterm.input.event.TerminalFocusEvent
import io.github.ketraterm.input.event.TerminalKeyEvent
import io.github.ketraterm.input.event.TerminalMouseEvent
import io.github.ketraterm.input.event.TerminalPasteEvent
import io.github.ketraterm.parser.api.TerminalOutputParser
import io.github.ketraterm.render.api.*
import io.github.ketraterm.render.cache.TerminalRenderPublisher
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.transport.TerminalConnector
import io.github.ketraterm.transport.TerminalConnectorListener
import io.github.ketraterm.ui.swing.render.TestRenderFrame
import io.github.ketraterm.ui.swing.settings.SwingPadding
import io.github.ketraterm.ui.swing.settings.SwingSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.awt.event.FocusEvent
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.RepaintManager
import javax.swing.SwingUtilities
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class SwingTerminalCursorBlinkTest {
    private val dispatcher = StandardTestDispatcher()

    @Test
    fun `focus reset repaints blinking text even without a terminal cursor`() {
        val terminal = TerminalBuffers.create(width = 3, height = 1, maxHistory = 1)
        val reader = TestRenderFrame.text("ABC", attrs = LongArray(3) { TerminalRenderAttrs.pack(blink = true) })
        val session =
            TerminalSession(
                terminal = terminal,
                renderPublisher = TerminalRenderPublisher(3, 1),
                renderReader = reader,
                responseReader = terminal,
                connector = NoOpConnector,
                parser = NoOpParser,
                inputEncoderFactory = { _, _, _ -> object : TerminalInputEncoder by NoOpInputEncoder {} },
                workerDispatcher = dispatcher,
            )
        session.renderPublisher.updateAndPublish(reader)
        session.use { session ->
            SwingUtilities.invokeAndWait {
                val component =
                    SwingTerminal(settingsProvider = {
                        SwingSettings.create { draft ->
                            draft.cursorBlinkMillis = 0
                            draft.padding = SwingPadding()
                            draft.shellIntegrationDecorationGutterWidth = 0
                        }
                    })
                val previousManager = RepaintManager.currentManager(component)
                val rowRepaints = ArrayList<Int>()
                val manager =
                    object : RepaintManager() {
                        override fun addDirtyRegion(
                            target: JComponent,
                            x: Int,
                            y: Int,
                            width: Int,
                            height: Int,
                        ) {
                            if (target === component && x == 0 && y == 0 && width == component.width && height > 0) {
                                rowRepaints += height
                            }
                        }
                    }
                try {
                    component.size = component.preferredGridSize(3, 1)
                    component.bind(session)
                    component.cursorBlinkVisible = false
                    RepaintManager.setCurrentManager(manager)

                    val event = FocusEvent(component, FocusEvent.FOCUS_GAINED)
                    for (listener in component.focusListeners) listener.focusGained(event)

                    assertTrue(component.cursorBlinkVisible)
                    assertTrue(rowRepaints.isNotEmpty(), "focus reset must invalidate the hidden text row")
                    assertFalse(component.cursorTimer.isRunning)
                } finally {
                    RepaintManager.setCurrentManager(previousManager)
                    component.dispose()
                }
            }
        }
    }

    @ParameterizedTest
    @EnumSource(TerminalRenderCursorShape::class)
    fun `focus transitions restore latest application cursor and preserve hidden cursors`(shape: TerminalRenderCursorShape) {
        val terminal = TerminalBuffers.create(width = 3, height = 1, maxHistory = 1)
        val session =
            TerminalSession(
                terminal = terminal,
                renderPublisher = TerminalRenderPublisher(3, 1),
                renderReader = terminal as TerminalRenderFrameReader,
                responseReader = terminal,
                connector = NoOpConnector,
                parser = NoOpParser,
                inputEncoderFactory = { _, _, _ -> object : TerminalInputEncoder by NoOpInputEncoder {} },
                workerDispatcher = dispatcher,
            )
        session.use {
            SwingUtilities.invokeAndWait {
                val settings =
                    SwingSettings.create { draft ->
                        draft.cursorBlinkMillis = 0
                        draft.cursorShape = TerminalRenderCursorShape.BAR
                        draft.padding = SwingPadding()
                        draft.shellIntegrationDecorationGutterWidth = 0
                    }
                val component = SwingTerminal(settingsProvider = { settings })
                component.size = component.preferredGridSize(3, 1)
                val cellWidth = component.width / 3
                val cellHeight = component.height

                fun paint(): BufferedImage {
                    val image = BufferedImage(component.width, component.height, BufferedImage.TYPE_INT_ARGB)
                    val graphics = image.createGraphics()
                    try {
                        component.paint(graphics)
                    } finally {
                        graphics.dispose()
                    }
                    return image
                }

                fun focus(gained: Boolean) {
                    val event = FocusEvent(component, if (gained) FocusEvent.FOCUS_GAINED else FocusEvent.FOCUS_LOST, true)
                    for (listener in component.focusListeners) {
                        if (gained) listener.focusGained(event) else listener.focusLost(event)
                    }
                }

                fun publish() {
                    session.requestRender(scrollbackOffset = 0)
                    dispatcher.scheduler.runCurrent()
                }
                try {
                    component.bind(session)
                    publish()
                    assertEquals(settings.palette.cursorBackground, paint().getRGB(0, 0))
                    assertEquals(settings.palette.defaultBackground, paint().getRGB(cellWidth / 2, cellHeight / 2))

                    focus(true)
                    component.cursorBlinkVisible = false
                    focus(false)
                    assertEquals(settings.palette.cursorBackground, paint().getRGB(0, 0))

                    terminal.positionCursor(1, 0)
                    terminal.setCursorShape(shape)
                    terminal.setCursorBlinking(true)
                    publish()
                    component.cursorBlinkVisible = false
                    assertEquals(settings.palette.defaultBackground, paint().getRGB(0, 0))
                    val cursorY = if (shape == TerminalRenderCursorShape.UNDERLINE) cellHeight - 1 else 0
                    assertEquals(settings.palette.cursorBackground, paint().getRGB(cellWidth, cursorY))
                    assertEquals(settings.palette.defaultBackground, paint().getRGB(cellWidth + cellWidth / 2, cellHeight / 2))

                    focus(true)
                    assertTrue(component.cursorBlinkVisible)
                    val focused = paint()
                    assertEquals(settings.palette.cursorBackground, focused.getRGB(cellWidth, cursorY))
                    val interiorColor =
                        when (shape) {
                            TerminalRenderCursorShape.BLOCK -> settings.palette.cursorBackground
                            TerminalRenderCursorShape.BAR, TerminalRenderCursorShape.UNDERLINE -> settings.palette.defaultBackground
                        }
                    assertEquals(interiorColor, focused.getRGB(cellWidth + cellWidth / 2, cellHeight / 2))

                    terminal.setCursorVisible(false)
                    publish()
                    for (gained in listOf(false, true)) {
                        focus(gained)
                        assertEquals(settings.palette.defaultBackground, paint().getRGB(cellWidth, cursorY))
                    }
                    assertFalse(component.cursorTimer.isRunning)
                } finally {
                    component.dispose()
                }
            }
        }
    }

    @Test
    fun `zero cursor blink setting keeps timer stopped and cursor visible`() {
        val component =
            SwingTerminal(
                settingsProvider = {
                    SwingSettings.create { draft ->
                        draft.cursorBlinkMillis = 0
                    }
                },
            )
        val frame = JFrame()

        try {
            SwingUtilities.invokeAndWait {
                component.cursorBlinkVisible = false
                frame.add(component)
                frame.pack()
            }

            assertFalse(component.cursorTimer.isRunning)
            assertTrue(component.cursorBlinkVisible)
        } finally {
            SwingUtilities.invokeAndWait {
                frame.dispose()
            }
        }
    }

    @Test
    fun `key events and frame updates reset cursor blinking timer`() {
        val terminal = TerminalBuffers.create(width = 3, height = 1, maxHistory = 5)
        val renderReader = SimpleFrameReader()
        val session =
            TerminalSession(
                terminal = terminal,
                renderPublisher = TerminalRenderPublisher(3, 1),
                renderReader = renderReader,
                responseReader = terminal,
                connector = NoOpConnector,
                parser = NoOpParser,
                inputEncoderFactory = { _, _, _ -> object : TerminalInputEncoder by NoOpInputEncoder {} },
                workerDispatcher = dispatcher,
            )
        val component = SwingTerminal()

        val frame = JFrame()
        try {
            SwingUtilities.invokeAndWait {
                frame.add(component)
                frame.pack()
                component.bind(session)
            }

            // Verify cursor timer is started when component is added
            assertTrue(component.cursorTimer.isRunning)

            // Drive blink state explicitly after verifying that binding starts the timer.
            SwingUtilities.invokeAndWait {
                component.cursorTimer.stop()
            }

            // 1. Manually set cursorBlinkVisible to false and verify, then dispatch key event and verify, then set back to false and verify
            SwingUtilities.invokeAndWait {
                component.cursorBlinkVisible = false
                assertFalse(component.cursorBlinkVisible)

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
                assertTrue(component.cursorBlinkVisible)

                component.cursorBlinkVisible = false
                assertFalse(component.cursorBlinkVisible)
            }

            SwingUtilities.invokeAndWait {
                component.cursorBlinkVisible = false
                val previousGeneration = session.renderGeneration.value
                session.requestRender(scrollbackOffset = 0)
                dispatcher.scheduler.runCurrent()
                assertTrue(session.renderGeneration.value > previousGeneration, "render was not published")
                assertTrue(component.cursorBlinkVisible)
            }
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
        override fun setInputPolicy(policy: io.github.ketraterm.input.policy.TerminalInputPolicy) = Unit

        override fun encodeKey(event: TerminalKeyEvent) = Unit

        override fun encodePaste(event: TerminalPasteEvent) = Unit

        override fun encodeFocus(event: TerminalFocusEvent) = Unit

        override fun encodeMouse(event: TerminalMouseEvent) = Unit
    }
}

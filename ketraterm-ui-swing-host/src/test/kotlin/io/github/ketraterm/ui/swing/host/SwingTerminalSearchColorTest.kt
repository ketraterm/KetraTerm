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

import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.render.api.TerminalRenderFrameConsumer
import io.github.ketraterm.render.api.TerminalRenderFrameReader
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.testkit.MockConnector
import io.github.ketraterm.ui.swing.api.SwingTerminal
import io.github.ketraterm.ui.swing.settings.SwingSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.test.StandardTestDispatcher
import java.awt.*
import java.awt.image.BufferedImage
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import java.util.regex.PatternSyntaxException
import javax.swing.JLabel
import javax.swing.JTextField
import javax.swing.SwingUtilities
import javax.swing.UIManager
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class SwingTerminalSearchColorTest {
    @Test
    fun `optional colors preserve defaults and freeze every supplied host color`() {
        val defaults = SwingTerminalSearchColors()
        assertNull(defaults.counterErrorForeground)
        assertNull(defaults.counterNoMatchForeground)
        assertNull(defaults.textFieldErrorBorder)
        assertNull(defaults.textFieldNoMatchBorder)
        assertNull(defaults.textFieldSelectionBackground)
        assertNull(defaults.textFieldSelectionForeground)
        var argb = 0x12345678
        val dynamic =
            object : Color(0) {
                override fun getRGB(): Int = argb
            }
        val draft =
            defaults.toBuilder().apply {
                counterErrorForeground = dynamic
                counterNoMatchForeground = dynamic
                textFieldErrorBorder = dynamic
                textFieldNoMatchBorder = dynamic
                textFieldSelectionBackground = dynamic
                textFieldSelectionForeground = dynamic
            }
        val colors = draft.build()
        argb = 0x76543210
        draft.counterErrorForeground = Color.RED
        val copied = colors.copy { it.foreground = Color.BLACK }
        assertEquals(0x12345678, copied.counterErrorForeground?.rgb)
        assertEquals(0x12345678, copied.counterNoMatchForeground?.rgb)
        assertEquals(0x12345678, copied.textFieldErrorBorder?.rgb)
        assertEquals(0x12345678, copied.textFieldNoMatchBorder?.rgb)
        assertEquals(0x12345678, copied.textFieldSelectionBackground?.rgb)
        assertEquals(0x12345678, copied.textFieldSelectionForeground?.rgb)
        val reset =
            colors.copy {
                it.counterErrorForeground = null
                it.counterNoMatchForeground = null
                it.textFieldErrorBorder = null
                it.textFieldNoMatchBorder = null
                it.textFieldSelectionBackground = null
                it.textFieldSelectionForeground = null
            }
        assertNull(reset.counterErrorForeground)
        assertNull(reset.counterNoMatchForeground)
        assertNull(reset.textFieldErrorBorder)
        assertNull(reset.textFieldNoMatchBorder)
        assertNull(reset.textFieldSelectionBackground)
        assertNull(reset.textFieldSelectionForeground)
    }

    @Test
    fun `pending zero match search retains normal colors until analysis completes`() =
        fixture { f ->
            f.bar.refreshColors(palette())
            f.withPendingSearch("absent") {
                assertTrue(f.terminal.currentSearchState().isSearching)
                assertEquals(0, f.terminal.currentSearchState().resultCount)
                f.assertColors(Color.WHITE, Color.BLUE)
            }
            f.settle()
            assertFalse(f.terminal.currentSearchState().isSearching)
            f.assertColors(Color.ORANGE, Color.YELLOW)
        }

    @Test
    fun `error and no match colors follow completed state and recover without another edit`() =
        fixture { f ->
            val colors = palette()
            f.bar.refreshColors(colors)
            f.assertColors(Color.WHITE, Color.BLUE)

            f.field.text = "absent"
            f.settle()
            assertFalse(f.terminal.currentSearchState().isSearching)
            assertEquals(0, f.terminal.currentSearchState().resultCount)
            f.assertColors(Color.ORANGE, Color.YELLOW)

            f.field.select(1, 4)
            val state = f.terminal.currentSearchState()
            f.bar.refreshColors(
                colors.copy {
                    it.counterNoMatchForeground = Color.CYAN
                    it.textFieldNoMatchBorder = Color.RED
                },
            )
            f.assertColors(Color.CYAN, Color.RED)
            assertEquals(state, f.terminal.currentSearchState())
            assertEquals("absent", f.field.text)
            assertEquals(1, f.field.selectionStart)
            assertEquals(4, f.field.selectionEnd)

            f.bar.refreshColors(colors)
            f.field.text = "needle"
            f.settle()
            assertEquals(1, f.terminal.currentSearchState().resultCount)
            f.assertColors(Color.WHITE, Color.BLUE)
            assertTrue(f.bar.performAction(SwingTerminalSearchAction.TOGGLE_REGEX))
            f.field.text = "["
            f.settle()
            assertIs<PatternSyntaxException>(f.terminal.currentSearchState().failure)
            f.assertColors(Color.RED, Color.MAGENTA)
            assertEquals(f.messages.message("search.invalidRegex"), f.counter.text)
            assertTrue(f.counter.toolTipText.isNotBlank())

            f.bar.refreshColors(colors.copy { it.counterErrorForeground = Color.GREEN })
            f.assertColors(Color.GREEN, Color.MAGENTA)
            f.field.text = "needle"
            f.settle()
            f.assertColors(Color.WHITE, Color.BLUE)
            f.field.text = ""
            f.settle()
            assertEquals("", f.terminal.currentSearchState().query)
            f.assertColors(Color.WHITE, Color.BLUE)
        }

    @Test
    fun `status borders take priority over focus and null restores focused styling`() =
        fixture { f ->
            val previous = KeyboardFocusManager.getCurrentKeyboardFocusManager()
            KeyboardFocusManager.setCurrentKeyboardFocusManager(
                object : DefaultKeyboardFocusManager() {
                    override fun getFocusOwner(): Component = f.field
                },
            )
            try {
                assertTrue(f.field.isFocusOwner)
                val colors = palette()
                f.bar.refreshColors(colors)
                f.assertColors(Color.WHITE, Color.GREEN)
                f.field.text = "absent"
                f.settle()
                f.assertColors(Color.ORANGE, Color.YELLOW)
                f.bar.refreshColors(
                    colors.copy {
                        it.counterNoMatchForeground = null
                        it.textFieldNoMatchBorder = null
                    },
                )
                f.assertColors(Color.WHITE, Color.GREEN)
                assertTrue(f.bar.performAction(SwingTerminalSearchAction.TOGGLE_REGEX))
                f.field.text = "["
                f.settle()
                f.bar.refreshColors(colors)
                f.assertColors(Color.RED, Color.MAGENTA)
                f.bar.refreshColors(
                    colors.copy {
                        it.counterErrorForeground = null
                        it.counterForeground = Color.CYAN
                        it.textFieldErrorBorder = null
                    },
                )
                f.assertColors(Color.CYAN, Color.GREEN)
                assertEquals(Color.WHITE, f.field.foreground, "Status colors must not replace query text")
            } finally {
                KeyboardFocusManager.setCurrentKeyboardFocusManager(previous)
            }
        }

    @Test
    fun `transparent status colors remain transparent`() =
        fixture { f ->
            val transparent = Color(0, true)
            val colors =
                palette().copy {
                    it.counterNoMatchForeground = transparent
                    it.textFieldNoMatchBorder = transparent
                    it.counterErrorForeground = transparent
                    it.textFieldErrorBorder = transparent
                }
            f.bar.refreshColors(colors)
            f.field.text = "absent"
            f.settle()
            f.assertColors(transparent, Color.BLACK)
            assertTrue(f.bar.performAction(SwingTerminalSearchAction.TOGGLE_REGEX))
            f.field.text = "["
            f.settle()
            f.assertColors(transparent, Color.BLACK)
        }

    @Test
    fun `query selection overrides clear to current look and feel and survive reopen`() =
        fixture { f ->
            val originalBackground = UIManager.getColor("TextField.selectionBackground")
            val originalForeground = UIManager.getColor("TextField.selectionForeground")
            try {
                val colors =
                    palette().copy {
                        it.textFieldSelectionBackground = Color.RED
                        it.textFieldSelectionForeground = Color.GREEN
                    }
                f.bar.refreshColors(colors)
                f.field.text = "needle"
                f.field.select(1, 4)
                f.bar.refreshColors()
                assertEquals(Color.RED, f.field.selectionColor)
                assertEquals(Color.GREEN, f.field.selectedTextColor)
                assertEquals(1, f.field.selectionStart)
                assertEquals(4, f.field.selectionEnd)
                f.bar.close()
                f.bar.open()
                assertEquals(Color.RED, f.field.selectionColor)
                assertEquals(Color.GREEN, f.field.selectedTextColor)

                var themeArgb = Color.YELLOW.rgb
                var reads = 0
                UIManager.put(
                    "TextField.selectionBackground",
                    object : Color(0) {
                        override fun getRGB(): Int {
                            reads++
                            return themeArgb
                        }
                    },
                )
                UIManager.put("TextField.selectionForeground", Color.BLUE)
                f.bar.refreshColors(
                    colors.copy {
                        it.textFieldSelectionBackground = null
                        it.textFieldSelectionForeground = null
                    },
                )
                assertEquals(Color.YELLOW, f.field.selectionColor)
                assertEquals(Color.BLUE, f.field.selectedTextColor)
                val sampledReads = reads
                themeArgb = Color.MAGENTA.rgb
                f.field.text = "needle"
                f.field.selectAll()
                f.field.caret.isSelectionVisible = true
                pixel(f.field)
                assertEquals(sampledReads, reads, "Painting must not sample a host/theme Color")
                assertEquals(Color.YELLOW, f.field.selectionColor)
                f.bar.refreshColors()
                assertEquals(Color.MAGENTA, f.field.selectionColor)
            } finally {
                UIManager.put("TextField.selectionBackground", originalBackground)
                UIManager.put("TextField.selectionForeground", originalForeground)
            }
        }

    private fun palette(): SwingTerminalSearchColors =
        SwingTerminalSearchColors.create {
            it.foreground = Color.WHITE
            it.counterForeground = Color.WHITE
            it.counterErrorForeground = Color.RED
            it.counterNoMatchForeground = Color.ORANGE
            it.textFieldBackground = Color.BLACK
            it.textFieldBorder = Color.BLUE
            it.textFieldFocusBorder = Color.GREEN
            it.textFieldErrorBorder = Color.MAGENTA
            it.textFieldNoMatchBorder = Color.YELLOW
        }

    private fun fixture(test: suspend (Fixture) -> Unit) =
        runBlocking {
            val fixture = withContext(Dispatchers.Swing) { Fixture() }
            try {
                val bound = CompletableDeferred<Unit>()
                withContext(Dispatchers.Swing) {
                    fixture.terminal.bind(fixture.session) { failure ->
                        if (failure == null) bound.complete(Unit) else bound.completeExceptionally(failure)
                    }
                }
                withTimeout(10.seconds) { bound.await() }
                fixture.dispatcher.scheduler.runCurrent()
                withContext(Dispatchers.Swing) {
                    fixture.bar.open()
                    test(fixture)
                }
            } finally {
                withContext(Dispatchers.Swing) {
                    fixture.bar.close()
                    fixture.terminal.dispose()
                }
                fixture.session.close()
                fixture.dispatcher.scheduler.runCurrent()
            }
        }

    private class Fixture {
        val dispatcher = StandardTestDispatcher()
        private val buffer = TerminalBuffers.create(80, 3).apply { writeText("needle") }
        private val beforeFrameRead = AtomicReference<(() -> Unit)?>(null)
        private val renderReader =
            object : TerminalRenderFrameReader by buffer {
                override fun readRenderFrame(
                    scrollbackOffset: Int,
                    consumer: TerminalRenderFrameConsumer,
                ) {
                    beforeFrameRead.getAndSet(null)?.invoke()
                    buffer.readRenderFrame(scrollbackOffset, consumer)
                }
            }
        val session =
            TerminalSession.create(
                buffer,
                renderReader,
                MockConnector(),
                workerDispatcher = dispatcher,
                ioDispatcher = dispatcher,
                cleanupDispatcher = dispatcher,
            )
        val terminal =
            SwingTerminal(
                {
                    SwingSettings.create {
                        it.columns = 80
                        it.rows = 3
                        it.cursorBlinkMillis = 0
                    }
                },
            )
        val messages = SwingHostMessages.forLocale(Locale.US)
        val bar = SwingTerminalSearchBar(terminal, messages)

        private fun descendants(component: Component): List<Component> =
            listOf(component) + if (component is Container) component.components.flatMap(::descendants) else emptyList()

        val field = descendants(bar.component).filterIsInstance<JTextField>().single()
        val counter = descendants(bar.component).filterIsInstance<JLabel>().single()

        init {
            terminal.size = terminal.preferredSize
        }

        suspend fun withPendingSearch(
            query: String,
            assertions: () -> Unit,
        ) {
            val entered = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            beforeFrameRead.set {
                check(!SwingUtilities.isEventDispatchThread()) { "Search analysis must read the source off the EDT" }
                entered.complete(Unit)
                release.await()
            }
            try {
                field.text = query
                withTimeout(10.seconds) { entered.await() }
                assertions()
            } finally {
                beforeFrameRead.set(null)
                release.countDown()
            }
        }

        suspend fun settle() {
            dispatcher.scheduler.runCurrent()
            // The public component owns its analysis dispatcher. Await completion without blocking the EDT.
            withTimeout(10.seconds) { terminal.searchState.first { !it.isSearching } }
        }

        fun assertColors(
            counterColor: Color,
            borderColor: Color,
        ) {
            assertEquals(counterColor, counter.foreground)
            assertEquals(borderColor.rgb, pixel(field), "Query outline")
        }
    }

    private companion object {
        fun pixel(field: JTextField): Int {
            field.setSize(300, 40)
            val image = BufferedImage(300, 40, BufferedImage.TYPE_INT_ARGB)
            val graphics = image.createGraphics()
            try {
                field.paint(graphics)
            } finally {
                graphics.dispose()
            }
            return image.getRGB(1, 20)
        }
    }
}

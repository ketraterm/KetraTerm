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
package io.github.ketraterm.ui.swing.suggestion

import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.render.api.TerminalRenderCellFlags
import io.github.ketraterm.render.cache.TerminalRenderCache
import io.github.ketraterm.session.*
import io.github.ketraterm.transport.TerminalConnector
import io.github.ketraterm.transport.TerminalConnectorListener
import io.github.ketraterm.ui.swing.render.GridPainter
import io.github.ketraterm.ui.swing.settings.SwingMetrics
import io.github.ketraterm.ui.swing.settings.SwingPadding
import io.github.ketraterm.ui.swing.settings.SwingSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.awt.AlphaComposite
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class InlineShellSuggestionTest {
    @Test
    fun `mid-command unconfirmed and out-of-grid anchors cannot produce previews`() =
        runTest {
            for (snapshot in listOf(
                TerminalShellCommandLineSnapshot("abcde", 2, 5, 0),
                TerminalShellCommandLineSnapshot("abcde", 5, 5, 0, requiresShellConfirmation = true),
                TerminalShellCommandLineSnapshot("abcde", 5, 9, 0),
            )) {
                fixture(prefix = "abcde", snapshot = snapshot) { session, _, expected ->
                    assertNull(session.captureCommandSuffixPreview(expected, "x"))
                }
            }
        }

    @Test
    fun `clearing delayed wrap at the same cursor position invalidates layout`() =
        runTest {
            fixture(prefix = "abcdef") { session, input, expected ->
                val inline =
                    InlineShellSuggestion(session, expected, assertNotNull(session.captureCommandSuffixPreview(expected, "suffix")), null)
                assertTrue(inline.reconcile(cache(session)))
                input.feed("\r\u001b[6G")
                assertFalse(inline.reconcile(cache(session)))
            }
        }

    @Test
    fun `image collision clips a glyph even when its cells are blank`() =
        runTest {
            fixture { session, _, expected ->
                val preview = assertNotNull(session.captureCommandSuffixPreview(expected, "suffix"))
                val live = TerminalRenderCache(6, 3)
                session.readRenderFrame { frame ->
                    live.accept(
                        object : io.github.ketraterm.render.api.TerminalRenderFrame by frame {
                            override fun copyImages(sink: io.github.ketraterm.render.api.TerminalRenderImageSink) {
                                val image =
                                    object : io.github.ketraterm.render.api.TerminalRenderImage {
                                        override val width = 1
                                        override val height = 1

                                        override fun copyArgb(
                                            destination: IntArray,
                                            offset: Int,
                                        ) {
                                            destination[offset] = -1
                                        }
                                    }
                                sink.accept(image, 4.0, 0.0, 1.0, 1.0, 0, 0, 1, 1, 0.0, 3.0, 0)
                            }
                        },
                    )
                }
                assertTrue(InlineShellSuggestion(session, expected, preview, null).reconcile(live))
                assertEquals('s'.code, preview.codeWords[3])
                assertTrue((4 until 18).all { preview.flags[it] and TerminalRenderCellFlags.EMPTY != 0 })
            }
        }

    @Test
    fun `directional preview cannot move into the command prefix`() =
        runTest {
            fixture(prefix = "שלום") { session, _, expected ->
                val preview = assertNotNull(session.captureCommandSuffixPreview(expected, "suffix"))
                assertFalse(InlineShellSuggestion(session, expected, preview, null).reconcile(cache(session)))
            }
        }

    @Test
    fun `suffix wraps wide and clustered text using normal grid semantics without changing live cells`() =
        runTest {
            fixture { session, input, expected ->
                val before = cache(session)
                val preview = assertNotNull(session.captureCommandSuffixPreview(expected, "ab界e\u0301👩‍💻tail"))
                val inline = InlineShellSuggestion(session, expected, preview, null)
                assertTrue(inline.reconcile(before))
                assertEquals('a'.code, preview.codeWords[3])
                assertEquals('b'.code, preview.codeWords[4])
                assertTrue(preview.flags[5] and TerminalRenderCellFlags.WRAP_PADDING != 0)
                assertEquals('界'.code, preview.codeWords[6])
                assertTrue(preview.flags[8] and TerminalRenderCellFlags.CLUSTER != 0)
                assertTrue(preview.flags[9] and TerminalRenderCellFlags.CLUSTER != 0)
                assertContentEquals(before.codeWords, cache(session).codeWords)
                assertTrue(input.writes.isEmpty())
            }
        }

    @Test
    fun `leading continuations cannot modify the last command grapheme`() =
        runTest {
            fixture { session, _, expected ->
                for (suffix in listOf("\u0301x", "\ufe0fx", "\u200dx")) {
                    assertNull(session.captureCommandSuffixPreview(expected, suffix), suffix)
                }
            }
        }

    @Test
    fun `invalid and mid-command suffixes are unavailable`() =
        runTest {
            fixture { session, _, expected ->
                for (suffix in listOf("", "\nhello", "\t", "\u001b[31mred", "\uD800", "a".repeat(16_385))) {
                    assertNull(session.captureCommandSuffixPreview(expected, suffix))
                }
                expected.cancel()
                assertNull(session.captureCommandSuffixPreview(expected, "after cancellation"))
            }
        }

    @Test
    fun `occupied shell suffix suppresses even a shorter host suggestion`() =
        runTest {
            fixture { session, input, expected ->
                val preview = assertNotNull(session.captureCommandSuffixPreview(expected, "x"))
                input.feed("\u001b[1;6HX\u001b[1;4H")
                assertFalse(InlineShellSuggestion(session, expected, preview, null).reconcile(cache(session)))
            }
        }

    @Test
    fun `obstruction on a later row clips the tail at a whole glyph`() =
        runTest {
            fixture { session, input, expected ->
                val preview = assertNotNull(session.captureCommandSuffixPreview(expected, "123界xmore"))
                input.feed("\u001b[2;2HX\u001b[1;4H")
                assertTrue(InlineShellSuggestion(session, expected, preview, null).reconcile(cache(session)))
                assertEquals("123", rowText(preview, 0).trim())
                assertTrue((6 until preview.rows * preview.columns).all { preview.flags[it] and TerminalRenderCellFlags.EMPTY != 0 })
            }
        }

    @Test
    fun `bottom clipping never scrolls away the beginning`() =
        runTest {
            fixture { session, _, expected ->
                val preview = assertNotNull(session.captureCommandSuffixPreview(expected, "abcdefghijklmnopqrstuvwxyz"))
                assertEquals("abc", rowText(preview, 0).trim())
                assertEquals("defghi", rowText(preview, 1))
                assertEquals("jklmno", rowText(preview, 2))
            }
        }

    @Test
    fun `pending wrap starts on the next row`() =
        runTest {
            fixture(prefix = "abcdef") { session, _, expected ->
                val preview = assertNotNull(session.captureCommandSuffixPreview(expected, "X界"))
                assertEquals("", rowText(preview, 0).trim())
                assertEquals('X'.code, preview.codeWords[6])
            }
        }

    @Test
    fun `margins alternate screen and disabled wrap fail closed`() =
        runTest {
            for (mode in listOf("\u001b[2;3r", "\u001b[?1049h", "\u001b[?7l", "\u001b[?6h", "\u001b[?69h")) {
                fixture { session, input, expected ->
                    input.feed(mode)
                    assertNull(session.captureCommandSuffixPreview(expected, "suffix"), mode)
                }
            }
        }

    @Test
    fun `ambiguous width follows the session policy`() =
        runTest {
            fixture { session, _, expected ->
                val preview = assertNotNull(session.captureCommandSuffixPreview(expected, "αx"))
                assertEquals('x'.code, preview.codeWords[4])
            }
            fixture(ambiguous = true) { session, _, expected ->
                val preview = assertNotNull(session.captureCommandSuffixPreview(expected, "αx"))
                assertTrue(preview.flags[3] and TerminalRenderCellFlags.WIDE_LEADING != 0)
                assertEquals('x'.code, preview.codeWords[5])
            }
        }

    @Test
    fun `ghost glyph pixels equal normal glyph pixels with the same opacity`() =
        runTest {
            fixture { session, _, expected ->
                val live = cache(session)
                val preview = assertNotNull(session.captureCommandSuffixPreview(expected, "界e\u0301x"))
                val settings =
                    SwingSettings.create {
                        it.padding = SwingPadding(0, 0, 0, 0)
                        it.shellIntegrationDecorationGutterWidth = 0
                        it.textAntialiasing = RenderingHints.VALUE_TEXT_ANTIALIAS_ON
                    }
                val actual = BufferedImage(200, 100, BufferedImage.TYPE_INT_ARGB)
                val reference = BufferedImage(200, 100, BufferedImage.TYPE_INT_ARGB)
                val g = actual.createGraphics()
                val metrics = SwingMetrics.from(g.getFontMetrics(settings.font))
                GridPainter().paint(g, live, settings, metrics, 200, 100, false, inlineSuggestion = preview)
                g.dispose()
                val r = reference.createGraphics()
                val painter = GridPainter()
                painter.paint(r, live, settings, metrics, 200, 100, false)
                // Paint only the suffix area from the ordinary grid at the identical opacity.
                r.clipRect(3 * metrics.cellWidth, 0, 3 * metrics.cellWidth, metrics.cellHeight)
                r.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.55f)
                painter.paint(r, preview, settings, metrics, 200, 100, false)
                r.dispose()
                for (y in 0 until metrics.cellHeight) {
                    for (x in 3 * metrics.cellWidth until 6 * metrics.cellWidth) {
                        assertEquals(reference.getRGB(x, y), actual.getRGB(x, y), "$x,$y")
                    }
                }
            }
        }

    private suspend fun kotlinx.coroutines.test.TestScope.fixture(
        prefix: String = "cmd",
        ambiguous: Boolean = false,
        snapshot: TerminalShellCommandLineSnapshot? = null,
        block: suspend (TerminalSession, Input, TerminalCommandEditContext) -> Unit,
    ) {
        val buffer = TerminalBuffers.create(6, 3, 0)
        buffer.setTreatAmbiguousAsWide(ambiguous)
        buffer.setCursorVisible(false)
        val input = Input()
        val model =
            TerminalShellCommandLineState(
                snapshot ?: TerminalShellCommandLineSnapshot(prefix, prefix.length, minOf(prefix.length, 5), 0),
            )
        val dispatcher = StandardTestDispatcher(testScheduler)
        TerminalSession
            .create(
                buffer,
                input,
                shellIntegration = TerminalShellIntegrationFactory.host(TerminalShellIntegrationState(), model),
                workerDispatcher = dispatcher,
                ioDispatcher = dispatcher,
                cleanupDispatcher = dispatcher,
            ).use { session ->
                session.start(6, 3)
                input.feed(prefix)
                block(session, input, assertNotNull(session.captureCommandEdit()))
            }
    }

    private fun cache(session: TerminalSession) = TerminalRenderCache(6, 3).apply { updateFrom(session) }

    private fun rowText(
        cache: TerminalRenderCache,
        row: Int,
    ): String =
        buildString {
            for (col in 0 until cache.columns) {
                val idx = cache.rowOffset(row) + col
                append(if (cache.flags[idx] and TerminalRenderCellFlags.CODEPOINT != 0) cache.codeWords[idx].toChar() else ' ')
            }
        }

    private class Input : TerminalConnector {
        lateinit var listener: TerminalConnectorListener
        val writes = ArrayList<Byte>()

        override fun start(listener: TerminalConnectorListener) {
            this.listener = listener
        }

        override fun resize(
            columns: Int,
            rows: Int,
        ) = Unit

        override fun write(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) {
            writes.addAll(bytes.copyOfRange(offset, offset + length).toList())
        }

        override fun close() = Unit

        fun feed(text: String) {
            val bytes = text.toByteArray()
            listener.onBytes(bytes, 0, bytes.size)
        }
    }
}

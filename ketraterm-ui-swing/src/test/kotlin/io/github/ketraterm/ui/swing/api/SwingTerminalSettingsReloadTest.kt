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
import io.github.ketraterm.host.HostEventSink
import io.github.ketraterm.host.HostPolicy
import io.github.ketraterm.render.api.TerminalColorPalette
import io.github.ketraterm.render.api.TerminalRenderCursorShape
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.transport.TerminalConnector
import io.github.ketraterm.transport.TerminalConnectorListener
import io.github.ketraterm.ui.swing.settings.SwingPadding
import io.github.ketraterm.ui.swing.settings.SwingSettings
import io.github.ketraterm.ui.swing.settings.TerminalTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.ConcurrentLinkedQueue
import javax.swing.SwingUtilities

class SwingTerminalSettingsReloadTest {
    @ParameterizedTest
    @ValueSource(ints = [63, 64])
    fun `off EDT rejection completes once and the same settings remain retryable`(queuedPolicies: Int) {
        Fixture().use { fixture ->
            fixture.fillQueue(queuedPolicies)
            fixture.changeSettings()
            val failures = mutableListOf<Throwable?>()
            val escaped =
                fixture.reloadOffEdt {
                    assertTrue(SwingUtilities.isEventDispatchThread())
                    failures.add(it)
                }
            assertNull(escaped)
            fixture.flush()
            assertEquals(1, failures.size)
            assertInstanceOf(IllegalStateException::class.java, failures.single())
            assertFalse(fixture.session.isClosed)

            val retried = mutableListOf<Throwable?>()
            edtCall { fixture.view.reloadSettings { retried.add(it) } }
            fixture.flush()
            assertEquals(listOf<Throwable?>(null), retried)
            fixture.assertApplied()
            assertEquals(1, failures.size)
        }
    }

    @Test
    fun `completion includes settings and matching geometry`() {
        Fixture().use { fixture ->
            fixture.changeSettings()
            var calls = 0
            edtCall {
                fixture.view.reloadSettings {
                    assertTrue(SwingUtilities.isEventDispatchThread())
                    assertNull(it)
                    fixture.assertApplied()
                    calls++
                }
                assertEquals(0, calls)
            }
            fixture.flush()
            assertEquals(1, calls)
        }
    }

    @Test
    fun `palette observer failure permits retry without replacing application colors`() {
        Fixture().use { fixture ->
            val observerFailure = IllegalStateException("Palette observer failed")
            fixture.paletteFailure = observerFailure
            fixture.changeSettings()
            val failed = mutableListOf<Throwable?>()
            edtCall { fixture.view.reloadSettings { failed.add(it) } }
            fixture.flush()
            assertEquals(1, failed.size)
            assertSame(observerFailure, failed.single())
            assertFalse(fixture.session.isClosed)
            assertEquals(fixture.settings.palette, fixture.session.palette)

            fixture.feed("\u001b]4;1;#123456\u0007")
            val retried = mutableListOf<Throwable?>()
            edtCall { fixture.view.reloadSettings { retried.add(it) } }
            fixture.flush()
            assertEquals(listOf<Throwable?>(null), retried)
            fixture.session.readRenderFrame {
                assertEquals(0xff123456.toInt(), it.palette.indexedColor(1))
                assertEquals(TerminalRenderCursorShape.UNDERLINE, it.cursor.shape)
            }

            fixture.feed("\u001b]4;1;#654321\u0007\u001b[5 q")
            val unchanged = mutableListOf<Throwable?>()
            edtCall { fixture.view.reloadSettings { unchanged.add(it) } }
            fixture.flush()
            assertEquals(listOf<Throwable?>(null), unchanged)
            fixture.assertApplicationAppearance(0xff654321.toInt())
            assertEquals(1, failed.size)
        }
    }

    @Test
    fun `geometry retry preserves application palette and cursor changes`() {
        Fixture().use { fixture ->
            fixture.fillQueue(63)
            fixture.changeSettings()
            val failed = mutableListOf<Throwable?>()
            edtCall { fixture.view.reloadSettings { failed.add(it) } }
            fixture.flush()
            assertEquals(1, failed.size)
            assertInstanceOf(IllegalStateException::class.java, failed.single())
            fixture.feed("\u001b]4;1;#123456\u0007\u001b[5 q")

            val retried = mutableListOf<Throwable?>()
            edtCall { fixture.view.reloadSettings { retried.add(it) } }
            fixture.flush()
            assertEquals(listOf<Throwable?>(null), retried)
            fixture.assertApplicationAppearance(0xff123456.toInt())
            fixture.assertGeometry()
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `pending reload completes once after disposal or replacement`(dispose: Boolean) {
        Fixture().use { fixture ->
            fixture.changeSettings()
            val obsolete = mutableListOf<Throwable?>()
            val replacement = mutableListOf<Throwable?>()
            edtCall {
                fixture.view.reloadSettings {
                    assertTrue(SwingUtilities.isEventDispatchThread())
                    obsolete.add(it)
                }
                if (dispose) {
                    fixture.view.dispose()
                } else {
                    fixture.view.bind(fixture.replacement) { replacement.add(it) }
                }
            }
            fixture.flush()
            assertEquals(1, obsolete.size)
            assertInstanceOf(CancellationException::class.java, obsolete.single())
            assertFalse(fixture.session.modeSnapshot.treatAmbiguousAsWide)
            assertFalse(fixture.session.isClosed)
            assertFalse(fixture.replacement.isClosed)
            if (!dispose) {
                assertEquals(listOf<Throwable?>(null), replacement)
                assertTrue(fixture.replacement.modeSnapshot.treatAmbiguousAsWide)
            }
        }
    }

    @Test
    fun `throwing rejection callback is not delivered twice`() {
        Fixture().use { fixture ->
            fixture.fillQueue(64)
            fixture.changeSettings()
            val callbackFailure = IllegalArgumentException("Completion failed")
            var calls = 0
            val escaped =
                fixture.reloadOffEdt {
                    assertInstanceOf(IllegalStateException::class.java, it)
                    calls++
                    throw callbackFailure
                }
            if (escaped != null) assertSame(callbackFailure, escaped)
            fixture.flush()
            assertEquals(1, calls)
            assertFalse(fixture.session.isClosed)
        }
    }

    @Test
    fun `settings provider failure completes once and allows a later reload`() {
        Fixture().use { fixture ->
            val providerFailure = IllegalArgumentException("Settings unavailable")
            fixture.settingsFailure = providerFailure
            val failed = mutableListOf<Throwable?>()
            assertNull(fixture.reloadOffEdt { failed.add(it) })
            fixture.flush()
            assertEquals(1, failed.size)
            assertSame(providerFailure, failed.single())

            fixture.changeSettings()
            val retried = mutableListOf<Throwable?>()
            edtCall { fixture.view.reloadSettings { retried.add(it) } }
            fixture.flush()
            assertEquals(listOf<Throwable?>(null), retried)
            fixture.assertApplied()
        }
    }

    @Test
    fun `completion callback can apply a newer settings snapshot`() {
        Fixture().use { fixture ->
            fixture.changeSettings()
            val completed = mutableListOf<Throwable?>()
            edtCall {
                fixture.view.reloadSettings { failure ->
                    completed.add(failure)
                    fixture.settings =
                        fixture.settings.copy {
                            it.palette = TerminalTheme.ONE_DARK.createPalette()
                            it.cursorShape = TerminalRenderCursorShape.BAR
                            it.columnSpacing += 2
                        }
                    fixture.view.reloadSettings { next -> completed.add(next) }
                }
            }
            fixture.flush()
            assertEquals(listOf<Throwable?>(null, null), completed)
            fixture.assertApplied()
            fixture.feed("\u001b]4;1;#123456\u0007\u001b[5 q")
            edtCall { fixture.view.reloadSettings { completed.add(it) } }
            fixture.flush()
            assertEquals(listOf<Throwable?>(null, null, null), completed)
            fixture.assertApplicationAppearance(0xff123456.toInt())
        }
    }

    @Test
    fun `repeated pending settings wait for their application`() {
        Fixture().use { fixture ->
            val completed = mutableListOf<Throwable?>()
            edtCall {
                fixture.settings =
                    fixture.settings.copy {
                        it.palette = TerminalTheme.NORD.createPalette()
                        it.cursorShape = TerminalRenderCursorShape.UNDERLINE
                    }
                fixture.view.reloadSettings { completed.add(it) }
                fixture.view.reloadSettings { completed.add(it) }
                assertTrue(completed.isEmpty())
            }
            fixture.flush()
            assertEquals(listOf<Throwable?>(null, null), completed)
            fixture.session.readRenderFrame {
                assertEquals(fixture.settings.palette, it.palette)
                assertEquals(fixture.settings.cursorShape, it.cursor.shape)
            }
        }
    }

    @Test
    fun `queued settings reversals preserve application order`() {
        Fixture().use { fixture ->
            val completed = mutableListOf<Throwable?>()
            edtCall {
                val first =
                    fixture.settings.copy {
                        it.palette = TerminalTheme.NORD.createPalette()
                        it.cursorShape = TerminalRenderCursorShape.UNDERLINE
                    }
                fixture.settings = first
                fixture.view.reloadSettings { completed.add(it) }
                fixture.settings =
                    first.copy {
                        it.palette = TerminalTheme.ONE_DARK.createPalette()
                        it.cursorShape = TerminalRenderCursorShape.BAR
                    }
                fixture.view.reloadSettings { completed.add(it) }
                fixture.settings = first
                fixture.view.reloadSettings { completed.add(it) }
                assertTrue(completed.isEmpty())
            }
            fixture.flush()
            assertEquals(listOf<Throwable?>(null, null, null), completed)
            fixture.session.readRenderFrame {
                assertEquals(fixture.settings.palette, it.palette)
                assertEquals(TerminalRenderCursorShape.UNDERLINE, it.cursor.shape)
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `failed view settings can be retried or reverted completely`(revert: Boolean) {
        Fixture().use { fixture ->
            val original = fixture.settings
            val listenerFailure = IllegalStateException("Font listener failed")
            var shouldThrow = true
            edtCall {
                fixture.view.addPropertyChangeListener("font") {
                    if (shouldThrow) {
                        shouldThrow = false
                        throw listenerFailure
                    }
                }
            }
            fixture.changeSettings()
            fixture.settings = fixture.settings.copy { it.font = original.font.deriveFont(original.font.size2D + 2f) }
            val failed = mutableListOf<Throwable?>()
            edtCall { fixture.view.reloadSettings { failed.add(it) } }
            fixture.flush()
            assertEquals(1, failed.size)
            assertSame(listenerFailure, failed.single())

            if (revert) fixture.settings = original
            val recovered = mutableListOf<Throwable?>()
            edtCall { fixture.view.reloadSettings { recovered.add(it) } }
            fixture.flush()
            assertEquals(listOf<Throwable?>(null), recovered)
            fixture.assertViewAppearance()
            fixture.assertGeometry()
            fixture.session.readRenderFrame {
                assertEquals(fixture.settings.palette, it.palette)
                assertEquals(fixture.settings.cursorShape, it.cursor.shape)
            }
            assertEquals(fixture.settings.treatAmbiguousAsWide, fixture.session.modeSnapshot.treatAmbiguousAsWide)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `property listener replacement prevents obsolete settings from continuing`(rebind: Boolean) {
        Fixture().use { fixture ->
            val original = fixture.settings
            fixture.changeSettings()
            fixture.settings = fixture.settings.copy { it.font = original.font.deriveFont(original.font.size2D + 2f) }
            var entered = false
            val newer = mutableListOf<Throwable?>()
            val obsolete = mutableListOf<Throwable?>()
            edtCall {
                fixture.view.addPropertyChangeListener("font") {
                    if (!entered) {
                        entered = true
                        fixture.settings =
                            fixture.settings.copy { next ->
                                next.font = original.font.deriveFont(original.font.size2D + 4f)
                                next.palette = TerminalTheme.ONE_DARK.createPalette()
                                next.cursorShape = TerminalRenderCursorShape.BAR
                                next.columnSpacing += 2
                            }
                        if (rebind) fixture.view.bind(fixture.replacement) {}
                        fixture.view.reloadSettings { newer.add(it) }
                    }
                }
                fixture.view.reloadSettings { obsolete.add(it) }
            }
            fixture.flush()
            assertTrue(entered)
            assertEquals(1, obsolete.size)
            assertInstanceOf(CancellationException::class.java, obsolete.single())
            assertEquals(listOf<Throwable?>(null), newer)
            fixture.assertViewAppearance()
            val bound = if (rebind) fixture.replacement else fixture.session
            val geometry = edtCall { fixture.view.visibleGridSize() }
            bound.readRenderFrame {
                assertEquals(fixture.settings.palette, it.palette)
                assertEquals(fixture.settings.cursorShape, it.cursor.shape)
                assertEquals(geometry.width, it.columns)
                assertEquals(geometry.height, it.rows)
            }
            if (rebind) assertFalse(fixture.session.modeSnapshot.treatAmbiguousAsWide)
            assertFalse(fixture.session.isClosed)
            assertFalse(fixture.replacement.isClosed)
        }
    }

    @Test
    fun `viewport listener reload prevents obsolete geometry admission`() {
        Fixture().use { fixture ->
            val obsolete = mutableListOf<Throwable?>()
            val newer = mutableListOf<Throwable?>()
            var notifications = 0
            edtCall {
                fixture.onViewportChanged = {
                    fixture.onViewportChanged = null
                    notifications++
                    fixture.changeSettings()
                    fixture.view.reloadSettings { newer.add(it) }
                }
                fixture.view.resizeSessionToVisibleGrid { obsolete.add(it) }
            }
            fixture.flush()
            assertEquals(1, notifications)
            assertEquals(1, obsolete.size)
            assertInstanceOf(CancellationException::class.java, obsolete.single())
            assertEquals(listOf<Throwable?>(null), newer)
            fixture.assertApplied()
        }
    }

    @Test
    fun `identical queued settings recover after the first palette observer failure`() {
        Fixture().use { fixture ->
            val observerFailure = IllegalStateException("Palette observer failed")
            fixture.paletteFailure = observerFailure
            val first = mutableListOf<Throwable?>()
            val second = mutableListOf<Throwable?>()
            edtCall {
                fixture.settings =
                    fixture.settings.copy {
                        it.palette = TerminalTheme.NORD.createPalette()
                        it.cursorShape = TerminalRenderCursorShape.UNDERLINE
                    }
                fixture.view.reloadSettings { first.add(it) }
                fixture.view.reloadSettings { second.add(it) }
                assertTrue(first.isEmpty())
                assertTrue(second.isEmpty())
            }
            fixture.flush()
            assertEquals(1, first.size)
            assertSame(observerFailure, first.single())
            assertEquals(listOf<Throwable?>(null), second)
            fixture.session.readRenderFrame {
                assertEquals(fixture.settings.palette, it.palette)
                assertEquals(TerminalRenderCursorShape.UNDERLINE, it.cursor.shape)
            }
        }
    }

    @Test
    fun `closure after settings application cancels undelivered completion`() {
        Fixture().use { fixture ->
            val completed = mutableListOf<Throwable?>()
            edtCall {
                fixture.settings =
                    fixture.settings.copy {
                        it.palette = TerminalTheme.NORD.createPalette()
                        it.cursorShape = TerminalRenderCursorShape.UNDERLINE
                    }
                fixture.view.reloadSettings { completed.add(it) }
            }
            fixture.runWorker()
            assertEquals(fixture.settings.palette, fixture.session.palette)
            assertTrue(completed.isEmpty())
            fixture.session.requestClose()
            fixture.flush()
            assertEquals(1, completed.size)
            assertInstanceOf(CancellationException::class.java, completed.single())
        }
    }

    private class Fixture : AutoCloseable {
        private val dispatcher = StandardTestDispatcher()
        private val ui = ConcurrentLinkedQueue<Runnable>()
        var settings =
            SwingSettings.create {
                it.columns = 10
                it.rows = 2
                it.padding = SwingPadding()
                it.alternateScreenPadding = SwingPadding()
                it.shellIntegrationDecorationGutterWidth = 0
                it.cursorBlinkMillis = 0
            }
        var onViewportChanged: (() -> Unit)? = null
        var settingsFailure: Throwable? = null
        var paletteFailure: Throwable? = null
        private val connector = Connector()
        val session =
            session(
                connector,
                object : HostEventSink by HostEventSink.NONE {
                    override fun paletteChanged(palette: TerminalColorPalette) {
                        val failure = paletteFailure
                        paletteFailure = null
                        if (failure != null) throw failure
                    }
                },
            )
        val replacement = session(Connector(), HostEventSink.NONE)
        val view =
            edtCall {
                SwingTerminal(
                    {
                        val failure = settingsFailure
                        settingsFailure = null
                        if (failure != null) throw failure
                        settings
                    },
                    SwingHostServices.create {
                        it.uiDispatcher = TerminalUiDispatcher { task -> ui.add(task) }
                        it.viewportListener = TerminalViewportListener { _, _, _, _, _ -> onViewportChanged?.invoke() }
                    },
                    searchDispatcher = dispatcher,
                    hyperlinkDispatcher = dispatcher,
                    suggestionDispatcher = dispatcher,
                    mutationDispatcher = dispatcher,
                ).apply { size = preferredGridSize(10, 2) }
            }

        init {
            val completed = mutableListOf<Throwable?>()
            edtCall { view.bind(session) { completed.add(it) } }
            flush()
            assertEquals(listOf<Throwable?>(null), completed)
        }

        fun changeSettings() {
            settings =
                settings.copy {
                    it.treatAmbiguousAsWide = true
                    it.palette = TerminalTheme.NORD.createPalette()
                    it.cursorShape = TerminalRenderCursorShape.UNDERLINE
                    it.columnSpacing += 4
                }
        }

        fun fillQueue(count: Int) {
            edtCall { repeat(count) { view.setHostPolicy(HostPolicy()) {} } }
        }

        fun reloadOffEdt(onComplete: (Throwable?) -> Unit): Throwable? {
            assertFalse(SwingUtilities.isEventDispatchThread())
            assertTrue(ui.isEmpty())
            view.reloadSettings(onComplete)
            return edtCall { runCatching { checkNotNull(ui.poll()).run() }.exceptionOrNull() }
        }

        fun feed(text: String) {
            val bytes = text.encodeToByteArray()
            connector.listener.onBytes(bytes, 0, bytes.size)
            flush()
        }

        fun runWorker() {
            dispatcher.scheduler.runCurrent()
        }

        fun flush() {
            edtCall {
                repeat(1_024) {
                    dispatcher.scheduler.runCurrent()
                    val task = ui.poll() ?: return@edtCall
                    task.run()
                }
                fail("UI work did not settle")
            }
        }

        fun assertApplied() {
            assertTrue(session.modeSnapshot.treatAmbiguousAsWide)
            session.readRenderFrame {
                assertEquals(settings.palette, it.palette)
                assertEquals(settings.cursorShape, it.cursor.shape)
            }
            assertGeometry()
        }

        fun assertViewAppearance() {
            edtCall {
                assertEquals(settings.font, view.font)
                assertEquals(settings.palette.defaultBackground, view.background.rgb)
                assertEquals(settings.palette.defaultForeground, view.foreground.rgb)
                assertEquals(view.preferredGridSize(settings.columns, settings.rows), view.preferredSize)
            }
        }

        fun assertGeometry() {
            val expected = edtCall { view.visibleGridSize() }
            session.readRenderFrame {
                assertEquals(expected.width, it.columns)
                assertEquals(expected.height, it.rows)
            }
        }

        fun assertApplicationAppearance(color: Int) {
            session.readRenderFrame {
                assertEquals(color, it.palette.indexedColor(1))
                assertEquals(TerminalRenderCursorShape.BAR, it.cursor.shape)
            }
        }

        override fun close() {
            edtCall { view.dispose() }
            flush()
            session.close()
            replacement.close()
            flush()
        }

        private fun session(
            connector: Connector,
            events: HostEventSink,
        ): TerminalSession =
            TerminalSession
                .create(
                    TerminalBuffers.create(10, 2),
                    connector,
                    hostEvents = events,
                    workerDispatcher = dispatcher,
                    ioDispatcher = dispatcher,
                    cleanupDispatcher = dispatcher,
                ).also { it.start(10, 2) }
    }

    private class Connector : TerminalConnector {
        lateinit var listener: TerminalConnectorListener

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
        ) = Unit

        override fun close() = Unit
    }
}

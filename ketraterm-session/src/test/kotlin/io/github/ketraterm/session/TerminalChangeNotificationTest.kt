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
package io.github.ketraterm.session

import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.host.HostControlPolicy
import io.github.ketraterm.host.HostEventSink
import io.github.ketraterm.host.HostPolicy
import io.github.ketraterm.render.api.TerminalColorPalette
import io.github.ketraterm.render.api.TerminalRenderFrameConsumer
import io.github.ketraterm.render.api.TerminalRenderFrameReader
import io.github.ketraterm.testkit.MockConnector
import io.github.ketraterm.transport.TerminalConnector
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalChangeNotificationTest {
    @Test
    fun startupCompletesResizeThatFailedBeforeConnectorStart() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val terminal = TerminalBuffers.create(10, 3)
            terminal.setInBandResizeReportingEnabled(true)
            val recorded = MockConnector()
            var failResize = true
            val connector =
                object : TerminalConnector by recorded {
                    override fun resize(
                        columns: Int,
                        rows: Int,
                    ) {
                        if (failResize) {
                            failResize = false
                            throw IllegalStateException("prestart resize failed")
                        }
                        recorded.resize(columns, rows)
                    }
                }
            TerminalSession
                .create(terminal, connector, workerDispatcher = dispatcher, ioDispatcher = dispatcher)
                .use { session ->
                    assertThrows(IllegalStateException::class.java) { session.tryResizeViewport(8, 2, 0, 80, 40) }
                    session.start(8, 2)
                    assertTrue(session.tryUpdateViewportPixels(8, 2, 80, 40))
                    session.submitBytes("after".encodeToByteArray())
                    runCurrent()
                    assertEquals("\u001b[48;3;10;0;0t\u001b[48;2;8;40;80tafter", recorded.writtenBytes.decodeToString())
                }
        }

    @ParameterizedTest
    @CsvSource("true, true", "true, false", "false, true", "false, false")
    fun resizeRetryReportsAfterConnectorOrMetadataFailure(
        connectorFailure: Boolean,
        knownPixels: Boolean,
    ) = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val terminal = TerminalBuffers.create(10, 3)
        val recorded = MockConnector()
        val failure = IllegalStateException("resize collaborator failed")
        var failConnector = connectorFailure
        var failMetadata = false
        val connector =
            object : TerminalConnector by recorded {
                override fun resize(
                    columns: Int,
                    rows: Int,
                ) {
                    if (columns == 8 && failConnector) {
                        failConnector = false
                        throw failure
                    }
                    recorded.resize(columns, rows)
                }
            }
        val reader =
            object : TerminalRenderFrameReader by terminal {
                override fun readRenderFrame(consumer: TerminalRenderFrameConsumer) {
                    if (failMetadata) {
                        failMetadata = false
                        throw failure
                    }
                    terminal.readRenderFrame(consumer)
                }
            }
        TerminalSession
            .create(terminal, reader, connector, workerDispatcher = dispatcher, ioDispatcher = dispatcher)
            .use { session ->
                session.start(10, 3)
                recorded.feedFromHost("\u001b[?2048h".encodeToByteArray())
                runCurrent()
                val pixelWidth = if (knownPixels) 80 else 0
                val pixelHeight = if (knownPixels) 40 else 0
                failMetadata = !connectorFailure
                assertSame(
                    failure,
                    assertThrows(IllegalStateException::class.java) {
                        session.tryResizeViewport(8, 2, 0, pixelWidth, pixelHeight)
                    },
                )
                assertFalse(session.tryUpdateViewportPixels(8, 2, pixelWidth, pixelHeight))
                recorded.feedFromHost("\u001b[5n".encodeToByteArray())
                runCurrent()
                assertEquals("\u001b[48;3;10;0;0t\u001b[0n", recorded.writtenBytes.decodeToString())
                assertNotNull(session.tryResizeViewport(8, 2, 0, pixelWidth, pixelHeight))
                session.submitBytes("after".encodeToByteArray())
                assertNotNull(session.tryResizeViewport(8, 2, 0, pixelWidth, pixelHeight))
                runCurrent()
                assertEquals(
                    "\u001b[48;3;10;0;0t\u001b[0n\u001b[48;2;8;$pixelHeight;${pixelWidth}tafter",
                    recorded.writtenBytes.decodeToString(),
                )
                assertSame(TerminalSessionState.Running, session.state.value)
            }
    }

    @ParameterizedTest
    @ValueSource(strings = ["same", "different", "disabled", "denied"])
    fun repeatedResizeFailureRechecksCurrentGeometrySubscriptionAndPolicy(recovery: String) =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val recorded = MockConnector()
            var failuresRemaining = 2
            val connector =
                object : TerminalConnector by recorded {
                    override fun resize(
                        columns: Int,
                        rows: Int,
                    ) {
                        if (columns == 8 && failuresRemaining > 0) {
                            failuresRemaining--
                            throw IllegalStateException("resize failed")
                        }
                        recorded.resize(columns, rows)
                    }
                }
            TerminalSession
                .create(TerminalBuffers.create(10, 3), connector, workerDispatcher = dispatcher, ioDispatcher = dispatcher)
                .use { session ->
                    session.start(10, 3)
                    recorded.feedFromHost("\u001b[?2048h".encodeToByteArray())
                    runCurrent()
                    repeat(2) {
                        assertThrows(IllegalStateException::class.java) { session.tryResizeViewport(8, 2, 0, 80, 40) }
                    }
                    when (recovery) {
                        "disabled" -> recorded.feedFromHost("\u001b[?2048l".encodeToByteArray())
                        "denied" -> session.setHostPolicy(HostPolicy(terminalResponsePolicy = HostControlPolicy.DENY))
                    }
                    val columns = if (recovery == "different") 9 else 8
                    session.tryResizeViewport(columns, 2, 0, columns * 10, 40)
                    session.submitBytes("after".encodeToByteArray())
                    runCurrent()
                    val report =
                        when (recovery) {
                            "same" -> "\u001b[48;2;8;40;80t"
                            "different" -> "\u001b[48;2;9;40;90t"
                            else -> ""
                        }
                    assertEquals("\u001b[48;3;10;0;0t${report}after", recorded.writtenBytes.decodeToString())
                }
        }

    @Test
    fun guardedPixelUpdateRejectsStaleGridAndPreservesFrameAndConnectorState() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val terminal = TerminalBuffers.create(10, 3)
            val connector = MockConnector()
            TerminalSession
                .create(terminal, connector, workerDispatcher = dispatcher, ioDispatcher = dispatcher)
                .use { session ->
                    session.start(10, 3)
                    connector.feedFromHost("hello\u001b[?2048h".encodeToByteArray())
                    runCurrent()
                    var generation = -1L
                    session.readRenderFrame { generation = it.frameGeneration }
                    assertFalse(session.tryUpdateViewportPixels(9, 3, 90, 60))
                    session.submitBytes("before".encodeToByteArray())
                    assertTrue(session.tryUpdateViewportPixels(10, 3, 100, 60))
                    session.submitBytes("after".encodeToByteArray())
                    assertTrue(session.tryUpdateViewportPixels(10, 3, 100, 60))
                    runCurrent()
                    assertEquals(
                        "\u001b[48;3;10;0;0tbefore\u001b[48;3;10;60;100tafter",
                        connector.writtenBytes.decodeToString(),
                    )
                    session.readRenderFrame { assertEquals(generation, it.frameGeneration) }
                    assertEquals("hello", terminal.getLineAsString(0))
                    assertEquals(listOf(10 to 3), connector.resizeCalls)
                    assertThrows(IllegalArgumentException::class.java) { session.tryUpdateViewportPixels(10, 3, -1, 60) }
                    session.close()
                    assertFalse(session.tryUpdateViewportPixels(10, 3, 100, 60))
                    assertThrows(IllegalArgumentException::class.java) { session.tryUpdateViewportPixels(0, 3, 100, 60) }
                }
        }

    @Test
    fun preconfiguredSubscriptionReportsAreAdmittedBeforeStartupInput() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val connector = MockConnector()
            val terminal = TerminalBuffers.create(10, 3)
            terminal.setInBandResizeReportingEnabled(true)
            TerminalSession
                .create(terminal, connector, workerDispatcher = dispatcher, ioDispatcher = dispatcher)
                .use { session ->
                    session.start(8, 2)
                    session.submitBytes("after".encodeToByteArray())
                    runCurrent()
                    assertEquals("\u001b[48;3;10;0;0t\u001b[48;2;8;0;0tafter", connector.writtenBytes.decodeToString())
                }
        }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun startupDenialDiscardsReportsGeneratedBeforeSessionOwnership(denyInitially: Boolean) =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val connector = MockConnector()
            val terminal = TerminalBuffers.create(10, 3)
            terminal.setInBandResizeReportingEnabled(true)
            val denied = HostPolicy(terminalResponsePolicy = HostControlPolicy.DENY)
            TerminalSession
                .create(
                    terminal,
                    connector,
                    hostPolicy = if (denyInitially) denied else HostPolicy(),
                    workerDispatcher = dispatcher,
                    ioDispatcher = dispatcher,
                    cleanupDispatcher = dispatcher,
                ).use { session ->
                    if (!denyInitially) session.setHostPolicy(denied)
                    session.start(8, 2)
                    session.submitBytes("input".encodeToByteArray())
                    runCurrent()
                    assertEquals("input", connector.writtenBytes.decodeToString())
                }
        }

    @Test
    fun explicitUnknownPixelsClearMeasurementWhileCellOnlyNoOpPreservesIt() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val connector = MockConnector()
            TerminalSession
                .create(TerminalBuffers.create(10, 3), connector, workerDispatcher = dispatcher, ioDispatcher = dispatcher)
                .use { session ->
                    session.start(10, 3)
                    session.tryResizeViewport(10, 3, 0, 100, 60)
                    connector.feedFromHost("\u001b[?2048h".encodeToByteArray())
                    session.tryResizeViewport(10, 3)
                    session.tryResizeViewport(10, 3, 0, 0, 0)
                    runCurrent()
                    assertEquals("\u001b[48;3;10;60;100t\u001b[48;3;10;0;0t", connector.writtenBytes.decodeToString())
                }
        }

    @Test
    fun committedThemeReportRetainsOrderWhenHostObserverThrows() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val connector = MockConnector()
            val failure = IllegalStateException("palette observer failed")
            val events =
                object : HostEventSink by HostEventSink.NONE {
                    override fun paletteChanged(palette: TerminalColorPalette): Unit = throw failure
                }
            TerminalSession
                .create(
                    TerminalBuffers.create(10, 3),
                    connector,
                    hostEvents = events,
                    workerDispatcher = dispatcher,
                    ioDispatcher = dispatcher,
                    cleanupDispatcher = dispatcher,
                ).use { session ->
                    session.start(10, 3)
                    connector.feedFromHost("\u001b[?2031h".encodeToByteArray())
                    val light = TerminalColorPalette(isDark = false)
                    assertSame(failure, assertThrows(IllegalStateException::class.java) { session.setThemePalette(light) })
                    assertEquals(light, session.palette)
                    session.submitBytes("after".encodeToByteArray())
                    runCurrent()
                    assertEquals("\u001b[?997;2nafter", connector.writtenBytes.decodeToString())
                }
        }

    @Test
    fun hostThemeNotificationsEnterWriterBeforeLaterInput() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val connector = MockConnector()
            TerminalSession
                .create(TerminalBuffers.create(10, 3), connector, workerDispatcher = dispatcher, ioDispatcher = dispatcher)
                .use { session ->
                    session.start(10, 3)
                    connector.feedFromHost("\u001b[?2031h".encodeToByteArray())
                    assertEquals(TerminalInputAdmission.ACCEPTED, session.submitBytes("before".encodeToByteArray()))
                    val light = TerminalColorPalette(isDark = false)
                    session.setThemePalette(light)
                    assertEquals(TerminalInputAdmission.ACCEPTED, session.submitBytes("after".encodeToByteArray()))
                    runCurrent()
                    assertEquals("before\u001b[?997;2nafter", connector.writtenBytes.decodeToString())

                    session.setThemePalette(light.copy(defaultForeground = 0xff123456.toInt()))
                    session.setThemePalette(light.copy(defaultForeground = 0xff123456.toInt()))
                    runCurrent()
                    assertEquals("before\u001b[?997;2nafter\u001b[?997;2n", connector.writtenBytes.decodeToString())

                    session.setHostPolicy(HostPolicy(terminalResponsePolicy = HostControlPolicy.DENY))
                    session.setThemePalette(TerminalColorPalette())
                    session.setHostPolicy(HostPolicy())
                    connector.feedFromHost("\u001b[?2031l".encodeToByteArray())
                    session.setThemePalette(light)
                    runCurrent()
                    assertEquals("before\u001b[?997;2nafter\u001b[?997;2n", connector.writtenBytes.decodeToString())
                }
        }

    @Test
    fun observerFailureRemainsPrimaryWhenNotificationAdmissionExhaustsCapacity() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val connector = MockConnector()
            val failure = IllegalStateException("palette observer failed")
            val events =
                object : HostEventSink by HostEventSink.NONE {
                    override fun paletteChanged(palette: TerminalColorPalette): Unit = throw failure
                }
            TerminalSession
                .create(
                    TerminalBuffers.create(10, 3),
                    connector,
                    hostEvents = events,
                    workerDispatcher = dispatcher,
                    ioDispatcher = dispatcher,
                    cleanupDispatcher = dispatcher,
                ).use { session ->
                    session.start(10, 3)
                    connector.feedFromHost("\u001b[?2031h".encodeToByteArray())
                    assertEquals(TerminalInputAdmission.ACCEPTED, session.submitBytes(ByteArray(8 * 1024 * 1024)))
                    assertSame(
                        failure,
                        assertThrows(IllegalStateException::class.java) { session.setThemePalette(TerminalColorPalette(isDark = false)) },
                    )
                    assertTrue(session.isClosed)
                    runCurrent()
                    val closed = session.state.value as TerminalSessionState.Closed
                    assertSame(failure.suppressed.single(), closed.event.failure)
                    runCurrent()
                    assertEquals("", connector.writtenBytes.decodeToString())
                }
        }

    @Test
    fun resizeReportsFollowConnectorResizeAndPrecedeLaterInput() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val recorded = MockConnector()
            val events = mutableListOf<String>()
            val connector =
                object : TerminalConnector by recorded {
                    override fun resize(
                        columns: Int,
                        rows: Int,
                    ) {
                        recorded.resize(columns, rows)
                        events += "resize:$columns:$rows"
                    }

                    override fun write(
                        bytes: ByteArray,
                        offset: Int,
                        length: Int,
                    ) {
                        events += bytes.decodeToString(offset, offset + length)
                        recorded.write(bytes, offset, length)
                    }
                }
            TerminalSession
                .create(TerminalBuffers.create(10, 3), connector, workerDispatcher = dispatcher, ioDispatcher = dispatcher)
                .use { session ->
                    session.start(10, 3)
                    recorded.feedFromHost("\u001b[?2048h".encodeToByteArray())
                    runCurrent()
                    events.clear()
                    session.tryResizeViewport(8, 2, 0, 80, 40)
                    assertEquals(listOf("resize:8:2"), events)
                    session.submitBytes("after".encodeToByteArray())
                    runCurrent()
                    assertEquals(listOf("resize:8:2", "\u001b[48;2;8;40;80tafter"), events)
                    assertEquals("\u001b[48;3;10;0;0t\u001b[48;2;8;40;80tafter", recorded.writtenBytes.decodeToString())

                    session.tryResizeViewport(8, 2, 0, 88, 44)
                    session.tryResizeViewport(8, 2, 0, 88, 44)
                    runCurrent()
                    assertEquals("\u001b[48;3;10;0;0t\u001b[48;2;8;40;80tafter\u001b[48;2;8;44;88t", recorded.writtenBytes.decodeToString())
                }
        }

    @Test
    fun failedConnectorResizeDoesNotLeakNotificationIntoLaterOutput() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val recorded = MockConnector()
            val failure = IllegalStateException("resize failed")
            val connector =
                object : TerminalConnector by recorded {
                    override fun resize(
                        columns: Int,
                        rows: Int,
                    ) {
                        if (columns == 8) throw failure
                        recorded.resize(columns, rows)
                    }
                }
            TerminalSession
                .create(TerminalBuffers.create(10, 3), connector, workerDispatcher = dispatcher, ioDispatcher = dispatcher)
                .use { session ->
                    session.start(10, 3)
                    recorded.feedFromHost("\u001b[?2048h".encodeToByteArray())
                    runCurrent()
                    assertSame(failure, assertThrows(IllegalStateException::class.java) { session.tryResizeViewport(8, 2, 0, 80, 40) })
                    recorded.feedFromHost("\u001b[5n".encodeToByteArray())
                    runCurrent()
                    assertEquals("\u001b[48;3;10;0;0t\u001b[0n", recorded.writtenBytes.decodeToString())
                    assertSame(TerminalSessionState.Running, session.state.value)
                }
        }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun responseDenialSuppressesImmediateAndHostGeneratedSizeReports(denyInitially: Boolean) =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val connector = MockConnector()
            val denied = HostPolicy(terminalResponsePolicy = HostControlPolicy.DENY)
            TerminalSession
                .create(
                    TerminalBuffers.create(10, 3),
                    connector,
                    hostPolicy = if (denyInitially) denied else HostPolicy(),
                    workerDispatcher = dispatcher,
                    ioDispatcher = dispatcher,
                    cleanupDispatcher = dispatcher,
                ).use { session ->
                    session.start(10, 3)
                    if (!denyInitially) session.setHostPolicy(denied)
                    connector.feedFromHost("\u001b[?2048h".encodeToByteArray())
                    session.tryResizeViewport(8, 2, 0, 80, 40)
                    runCurrent()
                    assertEquals("", connector.writtenBytes.decodeToString())

                    session.setHostPolicy(HostPolicy())
                    connector.feedFromHost("\u001b[?2048h".encodeToByteArray())
                    runCurrent()
                    assertEquals("\u001b[48;2;8;40;80t", connector.writtenBytes.decodeToString())

                    session.close()
                    assertNull(session.tryResizeViewport(7, 2, 0, 70, 40))
                    assertThrows(IllegalArgumentException::class.java) { session.tryResizeViewport(7, 2, 0, -1, 40) }
                }
        }
}

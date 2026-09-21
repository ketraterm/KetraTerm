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
import io.github.ketraterm.host.HostEventSink
import io.github.ketraterm.host.HostPolicy
import io.github.ketraterm.protocol.NotificationLevel
import io.github.ketraterm.render.api.TerminalColorPalette
import io.github.ketraterm.testkit.MockConnector
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalMetadataEventsTest {
    @Test
    fun `hyperlink lookup waits for registry mutation before resolving the retained ID`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val sink =
            object : HostEventSink by HostEventSink.NONE {
                override fun hyperlinkRegistered(
                    hyperlinkId: Int,
                    uri: String,
                    id: String?,
                ) {
                    entered.countDown()
                    release.await()
                }
            }
        val dispatcher = StandardTestDispatcher()
        TerminalSession
            .create(
                TerminalBuffers.create(10, 3),
                MockConnector(),
                sink,
                workerDispatcher = dispatcher,
                ioDispatcher = dispatcher,
            ).use { session ->
                SessionTestThread("hyperlink-registration") {
                    val bytes = "\u001B]8;id=a;https://a\u0007".encodeToByteArray()
                    session.onBytes(bytes, 0, bytes.size)
                }.use { writer ->
                    try {
                        assertTrue(entered.await(SESSION_THREAD_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                        SessionTestThread("hyperlink-resolution") {
                            assertEquals("https://a", session.hyperlinkUri(1))
                        }.use { reader ->
                            try {
                                reader.awaitBlockedBy(writer)
                            } finally {
                                release.countDown()
                            }
                            writer.awaitCompletion()
                            reader.awaitCompletion()
                        }
                    } finally {
                        release.countDown()
                    }
                }
            }
    }

    @Test
    fun `production session forwards metadata synchronously without render publication or replay`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val connector = MockConnector()
            val events = mutableListOf<String>()
            val palettes = mutableListOf<TerminalColorPalette>()
            val sink =
                object : HostEventSink by HostEventSink.NONE {
                    override fun paletteChanged(palette: TerminalColorPalette) {
                        palettes += palette
                        events += "palette"
                    }

                    override fun hyperlinkRegistered(
                        hyperlinkId: Int,
                        uri: String,
                        id: String?,
                    ) {
                        events += "registered:$hyperlinkId:$uri:$id"
                    }

                    override fun hyperlinkRemoved(hyperlinkId: Int) {
                        events += "removed:$hyperlinkId"
                    }

                    override fun hyperlinksCleared() {
                        events += "cleared"
                    }

                    override fun showNotification(
                        title: String,
                        body: String,
                        level: NotificationLevel,
                    ) {
                        events += "notification:$title:$body:$level"
                    }
                }
            TerminalSession
                .create(
                    TerminalBuffers.create(10, 3),
                    connector,
                    sink,
                    hostPolicy = HostPolicy(maxHyperlinkEntries = 1),
                    workerDispatcher = dispatcher,
                    ioDispatcher = dispatcher,
                ).use { session ->
                    session.start(10, 3)
                    assertTrue(events.isEmpty())
                    val theme = TerminalColorPalette(isDark = false)
                    val refreshedTheme = theme.copy()
                    session.setThemePalette(theme)
                    session.setThemePalette(refreshedTheme)
                    assertSame(theme, session.palette)
                    connector.feedFromHost(
                        (
                            "\u001B]4;1;#123456\u0007\u001B]8;id=a;https://a\u0007" +
                                "\u001B]8;id=b;https://b\u0007\u001B]9;hello\u0007\u001B]9;hello\u0007"
                        ).encodeToByteArray(),
                    )
                    assertNull(session.hyperlinkUri(1))
                    assertEquals("https://b", session.hyperlinkUri(2))
                    assertSame(palettes.last(), session.palette)
                    connector.feedFromHost("\u001Bc".encodeToByteArray())
                    assertNull(session.hyperlinkUri(2))
                    assertSame(refreshedTheme, session.palette)
                    assertEquals(
                        listOf(
                            "palette",
                            "palette",
                            "registered:1:https://a:a",
                            "removed:1",
                            "registered:2:https://b:b",
                            "notification::hello:INFO",
                            "notification::hello:INFO",
                            "cleared",
                            "palette",
                        ),
                        events,
                    )
                    val count = events.size
                    session.requestRender(0)
                    runCurrent()
                    assertEquals(count, events.size)
                    session.close()
                    val lateBytes = "\u001B]9;late\u0007".encodeToByteArray()
                    session.onBytes(lateBytes, 0, lateBytes.size)
                    session.setThemePalette(TerminalColorPalette())
                    assertEquals(count, events.size)
                    assertSame(refreshedTheme, session.palette)
                }
        }
}

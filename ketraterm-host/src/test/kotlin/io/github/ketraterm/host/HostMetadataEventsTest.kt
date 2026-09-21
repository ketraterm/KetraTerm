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
package io.github.ketraterm.host

import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.parser.api.TerminalParsers
import io.github.ketraterm.protocol.NotificationLevel
import io.github.ketraterm.render.api.TerminalColorPalette
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HostMetadataEventsTest {
    @Test
    fun `palette changes publish accepted immutable values without duplicates`() {
        val f = Fixture()
        val initial = f.terminal.palette
        f.accept("\u001B]4;1;#123456\u0007\u001B]4;1;#123456\u001B\\")
        val indexed = f.palettes.single()
        assertSame(indexed, f.terminal.palette)
        assertEquals(0xff123456.toInt(), indexed.indexedColor(1))
        assertEquals(0xff800000.toInt(), initial.indexedColor(1))
        f.accept("\u001B]10;#010203\u0007\u001B]11;#040506\u0007\u001B]12;#070809\u0007")
        assertEquals(4, f.palettes.size)
        val final = f.palettes.last()
        assertEquals(0xff010203.toInt(), final.defaultForeground)
        assertEquals(0xff040506.toInt(), final.defaultBackground)
        assertEquals(0xff070809.toInt(), final.cursorBackground)
        f.accept("\u001B]4;1;?\u0007\u001B]10;?\u0007\u001B[!p")
        assertEquals(4, f.palettes.size)
        f.accept("\u001Bc\u001Bc")
        assertEquals(5, f.palettes.size)
        assertSame(initial, f.palettes.last())
        assertEquals(0xff123456.toInt(), indexed.indexedColor(1))
    }

    @Test
    fun `invalid and denied controls stay silent but host themes bypass application policy`() {
        val f =
            Fixture(
                HostPolicy(
                    palettePolicy = HostControlPolicy.DENY,
                    hyperlinkPolicy = HostControlPolicy.DENY,
                    notificationPolicy = HostControlPolicy.DENY,
                ),
            )
        f.accept("\u001B]4;1;#123456\u0007\u001B]10;#123456\u0007\u001B]8;id=x;https://example.com\u0007\u001B]9;hello\u0007")
        assertTrue(f.events.isEmpty())
        val theme = TerminalColorPalette(isDark = false)
        f.sink.setThemePalette(theme)
        f.sink.setThemePalette(theme.copy())
        f.sink.resetTerminal()
        assertEquals(listOf("palette"), f.events)
        assertSame(theme, f.palettes.single())
        f.sink.setHostPolicy(HostPolicy())
        f.accept("\u001B]4;256;#123456\u0007\u001B]10;invalid\u0007")
        f.sink.setPaletteColor(-1, 0)
        f.sink.setPaletteColor(256, 0)
        f.sink.setDynamicColor(13, 0)
        assertEquals(listOf("palette"), f.events)
    }

    @Test
    fun `registry events distinguish explicit reuse anonymous links eviction and resets`() {
        val f = Fixture(HostPolicy(maxHyperlinkEntries = 2))
        f.sink.startHyperlink("https://a", "key")
        f.sink.endHyperlink()
        f.sink.startHyperlink("https://a", "key")
        f.sink.softReset()
        assertEquals(listOf("registered:1:https://a:key"), f.events)
        assertEquals("https://a", f.sink.hyperlinkUri(1))
        f.sink.startHyperlink("https://a", null)
        f.sink.startHyperlink("https://a", null)
        assertNull(f.sink.hyperlinkUri(1))
        assertEquals(
            listOf(
                "registered:1:https://a:key",
                "registered:2:https://a:null",
                "removed:1",
                "registered:3:https://a:null",
            ),
            f.events,
        )
        f.sink.resetTerminal()
        f.sink.resetTerminal()
        f.sink.startHyperlink("https://b", "key")
        assertEquals(listOf("cleared", "registered:4:https://b:key"), f.events.takeLast(2))
        assertNull(f.sink.hyperlinkUri(2))
        assertNull(f.sink.hyperlinkUri(3))
    }

    @Test
    fun `rejected links and exhausted IDs never create phantom registry events`() {
        val f = Fixture(HostPolicy(maxHyperlinkUriLength = 12, maxHyperlinkIdLength = 3))
        f.sink.startHyperlink("https://too-long.example", null)
        f.sink.startHyperlink("https://a", "long")
        assertTrue(f.events.isEmpty())
        HostCommandAdapter::class.java
            .getDeclaredField("nextHyperlinkNumericId")
            .apply { isAccessible = true }
            .setInt(f.sink, Int.MAX_VALUE)
        f.sink.startHyperlink("https://a", "a")
        f.sink.startHyperlink("https://b", "b")
        f.sink.startHyperlink("https://a", "a")
        assertEquals(listOf("registered:${Int.MAX_VALUE}:https://a:a"), f.events)
        assertEquals("https://a", f.sink.hyperlinkUri(Int.MAX_VALUE))
    }

    @Test
    fun `mixed OSC callbacks preserve order and repeated notifications at every byte split`() {
        val bytes =
            (
                "\u001B]4;1;#123456\u001B\\\u001B]8;id=k;https://a\u001B\\" +
                    "x\u001B]8;;\u0007\u001B]9;hello\u0007\u001B]9;hello\u001B\\" +
                    "\u001B]777;notify;title;body\u0007\u001Bc"
            ).encodeToByteArray()
        for (split in 0..bytes.size) {
            val f = Fixture()
            f.parser.accept(bytes, 0, split)
            f.parser.accept(bytes, split, bytes.size - split)
            assertEquals(
                listOf(
                    "palette",
                    "registered:1:https://a:k",
                    "notification::hello:INFO",
                    "notification::hello:INFO",
                    "notification:title:body:INFO",
                    "cleared",
                    "palette",
                ),
                f.events,
                "split $split",
            )
        }
    }

    private class Fixture(
        policy: HostPolicy = HostPolicy(),
    ) {
        val terminal = TerminalBuffers.create(10, 3)
        val events = mutableListOf<String>()
        val palettes = mutableListOf<TerminalColorPalette>()
        val sink =
            HostCommandAdapter(
                terminal,
                object : HostEventSink by HostEventSink.NONE {
                    override fun paletteChanged(palette: TerminalColorPalette) {
                        assertSame(terminal.palette, palette)
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
                },
                policy,
            )
        val parser = TerminalParsers.create(sink)

        fun accept(text: String) {
            parser.accept(text.encodeToByteArray())
        }
    }
}

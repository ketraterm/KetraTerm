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
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

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

    @ParameterizedTest
    @ValueSource(ints = [0, 3, 4])
    fun `lowered registry limit reconciles new and reused links at every byte split`(key: Int) {
        val bytes = "\u001B]8;id=$key;https://$key\u001B\\X".encodeToByteArray()
        for (split in 0..bytes.size) {
            val f = Fixture(HostPolicy(maxHyperlinkEntries = 4))
            repeat(4) { f.accept("\u001B]8;id=$it;https://$it\u0007") }
            f.events.clear()
            f.sink.setHostPolicy(HostPolicy(maxHyperlinkEntries = 1))
            assertTrue(f.events.isEmpty())
            repeat(4) { assertEquals("https://$it", f.sink.hyperlinkUri(it + 1)) }

            f.parser.accept(bytes, 0, split)
            f.parser.accept(bytes, split, bytes.size - split)

            val retainedId = key + 1
            assertEquals(retainedId, f.terminal.getAttrAt(0, 0)?.hyperlinkId, "split $split")
            assertEquals("https://$key", f.sink.hyperlinkUri(retainedId))
            val retiredIds = (1..4).filter { it != retainedId }
            retiredIds.forEach { assertNull(f.sink.hyperlinkUri(it)) }
            val registration = if (key == 4) listOf("registered:5:https://4:4") else emptyList()
            assertEquals(retiredIds.map { "removed:$it" } + registration, f.events, "split $split")
        }
    }

    @Test
    fun `denied and malformed opens defer trimming until an accepted open`() {
        val f = Fixture(HostPolicy(maxHyperlinkEntries = 2))
        f.accept("\u001B]8;id=a;https://a\u0007\u001B]8;id=b;https://b\u0007")
        f.events.clear()
        f.sink.setHostPolicy(HostPolicy(maxHyperlinkEntries = 1, hyperlinkPolicy = HostControlPolicy.DENY))
        f.accept("\u001B]8;id=c;https://c\u0007X")
        f.sink.setHostPolicy(HostPolicy(maxHyperlinkEntries = 1, maxHyperlinkUriLength = 9))
        f.accept("\u001B]8;id=c;https://too-long\u0007Y\u001B]8;;\u0007")
        assertTrue(f.events.isEmpty())
        assertEquals("https://a", f.sink.hyperlinkUri(1))
        assertEquals("https://b", f.sink.hyperlinkUri(2))
        assertEquals(0, f.terminal.getAttrAt(0, 0)?.hyperlinkId)
        assertEquals(0, f.terminal.getAttrAt(1, 0)?.hyperlinkId)

        f.accept("\u001B]8;id=c;https://c\u0007Z")
        assertEquals(listOf("removed:1", "removed:2", "registered:3:https://c:c"), f.events)
        f.sink.setHostPolicy(HostPolicy(maxHyperlinkEntries = Int.MAX_VALUE))
        f.accept("\u001B]8;id=d;https://d\u0007")
        assertEquals("https://c", f.sink.hyperlinkUri(3))
        assertEquals("https://d", f.sink.hyperlinkUri(4))
        assertEquals("registered:4:https://d:d", f.events.last())
    }

    @Test
    fun `failed eviction callback aborts admission with coherent indexes and permits recovery`() {
        val failure = IllegalStateException("removal failed")
        val events = mutableListOf<String>()
        var failRemoval = true
        val sink =
            HostCommandAdapter(
                TerminalBuffers.create(10, 3),
                object : HostEventSink by HostEventSink.NONE {
                    override fun hyperlinkRemoved(hyperlinkId: Int) {
                        events += "removed:$hyperlinkId"
                        if (failRemoval) throw failure
                    }

                    override fun hyperlinkRegistered(
                        hyperlinkId: Int,
                        uri: String,
                        id: String?,
                    ) {
                        events += "registered:$hyperlinkId"
                    }
                },
                HostPolicy(maxHyperlinkEntries = 3),
            )
        repeat(3) { sink.startHyperlink("https://$it", "$it") }
        events.clear()
        sink.setHostPolicy(HostPolicy(maxHyperlinkEntries = 1))
        assertSame(failure, assertThrows(IllegalStateException::class.java) { sink.startHyperlink("https://new", "new") })
        assertEquals(listOf("removed:1"), events)
        assertNull(sink.hyperlinkUri(1))
        assertNull(sink.hyperlinkUri(4))

        failRemoval = false
        sink.startHyperlink("https://0", "0")
        assertEquals(listOf("removed:1", "removed:2", "removed:3", "registered:4"), events)
        assertNull(sink.hyperlinkUri(2))
        assertNull(sink.hyperlinkUri(3))
        assertEquals("https://0", sink.hyperlinkUri(4))
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

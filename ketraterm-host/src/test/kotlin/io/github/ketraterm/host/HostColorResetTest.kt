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
import io.github.ketraterm.render.api.TerminalColorPalette
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HostColorResetTest {
    @Test
    fun `indexed reset batches preserve other overrides and publish one change at every split`() {
        for (terminator in listOf("\u0007", "\u001B\\")) {
            val bytes = "\u001B]104;0;255;0$terminator\u001B]104;0;255$terminator".encodeToByteArray()
            for (split in 0..bytes.size) {
                val f = Fixture()
                val theme = f.terminal.palette
                f.accept("\u001B]4;0;#123456;1;#abcdef;255;#654321\u0007\u001B]10;#010203\u0007")
                val overridden = f.terminal.palette
                f.palettes.clear()

                f.parser.accept(bytes, 0, split)
                f.parser.accept(bytes, split, bytes.size - split)

                val current = f.palettes.single()
                assertSame(current, f.terminal.palette)
                assertEquals(theme.indexedColor(0), current.indexedColor(0))
                assertEquals(theme.indexedColor(255), current.indexedColor(255))
                assertEquals(0xffabcdef.toInt(), current.indexedColor(1))
                assertEquals(0xff010203.toInt(), current.defaultForeground)
                assertEquals(0xff123456.toInt(), overridden.indexedColor(0))
                assertEquals(0xff654321.toInt(), overridden.indexedColor(255))
                assertEquals("", f.drainResponses())
            }
        }
    }

    @Test
    fun `all indexed reset and dynamic resets restore latest theme before queries at every split`() {
        val theme =
            TerminalColorPalette(
                defaultForeground = 0xff111213.toInt(),
                defaultBackground = 0xff212223.toInt(),
                cursorBackground = 0xff313233.toInt(),
                indexedColors = IntArray(256) { 0xff010203.toInt() },
                isDark = false,
            )
        val bytes =
            (
                "\u001B]104\u0007\u001B]110\u001B\\\u001B]111;\u0007\u001B]112\u001B\\" +
                    "\u001B]104;\u001B\\\u001B]110;\u0007\u001B]111\u0007\u001B]112;\u0007" +
                    "\u001B]4;1;?\u0007\u001B]10;?;?;?\u0007"
            ).encodeToByteArray()
        for (split in 0..bytes.size) {
            val f = Fixture()
            f.sink.setThemePalette(theme)
            f.accept("\u001B]4;1;#abcdef;255;#123456\u0007\u001B]10;#ffffff;#000000;#aabbcc\u0007")
            f.palettes.clear()

            f.parser.accept(bytes, 0, split)
            f.parser.accept(bytes, split, bytes.size - split)

            assertEquals(theme, f.terminal.palette, "split $split")
            assertEquals(4, f.palettes.size, "split $split")
            assertEquals(0xffffffff.toInt(), f.palettes[0].defaultForeground)
            assertEquals(0xff000000.toInt(), f.palettes[1].defaultBackground)
            assertEquals(0xffaabbcc.toInt(), f.palettes[2].cursorBackground)
            assertEquals(theme, f.palettes[3])
            assertEquals(
                "\u001B]4;1;rgb:0101/0202/0303\u001B\\" +
                    "\u001B]10;rgb:1111/1212/1313\u001B\\" +
                    "\u001B]11;rgb:2121/2222/2323\u001B\\" +
                    "\u001B]12;rgb:3131/3232/3333\u001B\\",
                f.drainResponses(),
            )
        }
    }

    @Test
    fun `palette denial prevents resets while general response denial permits palette controls and queries`() {
        val f = Fixture()
        val initial = f.terminal.palette
        f.accept("\u001B]4;1;#123456\u0007\u001B]10;#010203;#040506;#070809\u0007")
        val overridden = f.terminal.palette
        f.palettes.clear()
        f.sink.setHostPolicy(HostPolicy(palettePolicy = HostControlPolicy.DENY))
        f.accept("\u001B]104\u0007\u001B]104;1\u0007\u001B]110\u0007\u001B]111\u0007\u001B]112\u0007")
        assertSame(overridden, f.terminal.palette)
        assertTrue(f.palettes.isEmpty())
        assertEquals("", f.drainResponses())

        f.sink.setHostPolicy(HostPolicy(terminalResponsePolicy = HostControlPolicy.DENY))
        f.accept(
            "\u001B]104\u0007\u001B]110\u0007\u001B]111\u0007\u001B]112\u0007\u001B]4;1;?\u0007\u001B]10;?;?;?\u0007\u001B[5n\u001B[?996n",
        )
        assertEquals(initial, f.terminal.palette)
        assertEquals(4, f.palettes.size)
        assertEquals(
            "\u001B]4;1;rgb:8080/0000/0000\u001B\\" +
                "\u001B]10;rgb:ffff/ffff/ffff\u001B\\" +
                "\u001B]11;rgb:0000/0000/0000\u001B\\" +
                "\u001B]12;rgb:ffff/ffff/ffff\u001B\\",
            f.drainResponses(),
        )
    }

    @Test
    fun `malformed aborted oversized and unknown resets preserve palette and recover`() {
        val f = Fixture()
        f.accept("\u001B]4;0;#123456\u0007\u001B]10;#010203\u0007")
        val overridden = f.terminal.palette
        f.palettes.clear()
        for (payload in listOf(
            "104;-1;256;99999999999999999999;invalid;;",
            "110;?",
            "111;#abcdef",
            "112; ",
            "113",
            "104;0;".padEnd(4097, ';'),
        )) {
            f.accept("\u001B]$payload\u0007")
        }
        f.accept("\u001B]104;0\u0018\u001B]110\u001A")
        f.parser.accept("\u001B]104;0;".encodeToByteArray() + byteArrayOf(0xc3.toByte()) + "\u0007".encodeToByteArray())
        assertSame(overridden, f.terminal.palette)
        assertTrue(f.palettes.isEmpty())
        assertEquals("", f.drainResponses())

        f.accept("\u001B]104;0\u001B\\\u001B]110\u0007\u001B]2;recovered\u0007")
        assertEquals(2, f.palettes.size)
        assertEquals("recovered", f.terminal.windowTitle)
    }

    private class Fixture {
        val terminal = TerminalBuffers.create(4, 3)
        val palettes = mutableListOf<TerminalColorPalette>()
        val sink =
            HostCommandAdapter(
                terminal,
                object : HostEventSink by HostEventSink.NONE {
                    override fun paletteChanged(palette: TerminalColorPalette) {
                        assertSame(terminal.palette, palette)
                        palettes += palette
                    }
                },
            )
        val parser = TerminalParsers.create(sink)

        fun accept(text: String) {
            parser.accept(text.encodeToByteArray())
        }

        fun drainResponses(): String {
            val destination = ByteArray(256)
            val count = terminal.readResponseBytes(destination)
            return destination.decodeToString(0, count)
        }
    }
}

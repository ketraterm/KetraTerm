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

class HostKittyColorTest {
    @Test
    fun `ordered mutations and queries produce one reply across every split and terminator`() {
        for (terminator in listOf("\u0007", "\u001b\\")) {
            val bytes =
                (
                    "\u001b]21;foreground=#123456;foreground=?;foreground;foreground=?;" +
                        "0001=#abcdef;0001=?;unknown=red;unknown;unknown=?$terminator"
                ).encodeToByteArray()
            for (split in 0..bytes.size) {
                val f = Fixture()
                f.parser.accept(bytes, 0, split)
                f.parser.accept(bytes, split, bytes.size - split)
                assertEquals(
                    "\u001b]21;foreground=rgb:1212/3434/5656;foreground=rgb:ffff/ffff/ffff;" +
                        "0001=rgb:abab/cdcd/efef;unknown=dW5rbm93bg;unknown=dW5rbm93bg;unknown=dW5rbm93bg\u001b\\",
                    f.drain(),
                    "split=$split",
                )
                assertEquals(0xffabcdef.toInt(), f.terminal.palette.indexedColor(1))
                assertEquals(0xffffffff.toInt(), f.terminal.palette.defaultForeground)
                assertEquals(1, f.palettes.size)
            }
        }
    }

    @Test
    fun `decimal spelling and rounding boundaries survive the byte stream and color replies`() {
        for (terminator in listOf("\u0007", "\u001b\\")) {
            val bytes =
                (
                    "\u001b]21;1=rgbi:.5/.1/.9;1=?;" +
                        "1=rgbi:.50000000000/.100000/.900000;1=?;" +
                        "1=rgbi:50000000000e-11/100000e-6/900000e-6;1=?;" +
                        "1=rgbi:.499999999999999999999999/.5/.500000000000000000000001;1=?$terminator"
                ).encodeToByteArray()
            for (split in 0..bytes.size) {
                val f = Fixture()
                f.parser.accept(bytes, 0, split)
                f.parser.accept(bytes, split, bytes.size - split)
                assertEquals(
                    "\u001b]21;1=rgb:8080/1a1a/e6e6;1=rgb:8080/1a1a/e6e6;" +
                        "1=rgb:8080/1a1a/e6e6;1=rgb:7f7f/8080/8080\u001b\\",
                    f.drain(),
                    "split=$split",
                )
                assertEquals(0xff7f8080.toInt(), f.terminal.palette.indexedColor(1))
            }
        }
    }

    @Test
    fun `query permission is independent of palette mutation and has no window permission dependency`() {
        for (palette in HostControlPolicy.entries) {
            for (response in HostControlPolicy.entries) {
                val f = Fixture()
                f.sink.setHostPolicy(
                    HostPolicy(
                        palettePolicy = palette,
                        terminalResponsePolicy = response,
                        windowManipulationPolicy = HostControlPolicy.DENY,
                    ),
                )
                val initial = f.terminal.palette
                f.accept("foreground=#010203;foreground=?;unrecognized=?")
                assertEquals(
                    if (palette == HostControlPolicy.ALLOW) 0xff010203.toInt() else initial.defaultForeground,
                    f.terminal.palette.defaultForeground,
                )
                assertEquals(
                    if (palette == HostControlPolicy.ALLOW && response == HostControlPolicy.ALLOW) {
                        "\u001b]21;foreground=rgb:0101/0202/0303;unknown=dW5yZWNvZ25pemVk\u001b\\"
                    } else {
                        ""
                    },
                    f.drain(),
                )
            }
        }
    }

    @Test
    fun `dynamic requests differ from resets and later theme replacement becomes reset source`() {
        val f = Fixture()
        f.accept(
            "cursor=;cursor_text=;selection_foreground=;selection_background=;" +
                "cursor=?;cursor_text=?;selection_foreground=?;selection_background=?",
        )
        assertEquals("\u001b]21;cursor=;cursor_text=;selection_foreground=;selection_background=\u001b\\", f.drain())
        val theme =
            TerminalColorPalette(
                cursorBackground = 0xff010203.toInt(),
                cursorForeground = 0xff040506.toInt(),
                selectionForeground = 0xff070809.toInt(),
                selectionBackground = 0xff0a0b0c.toInt(),
            )
        f.sink.setThemePalette(theme)
        f.accept("cursor=red;cursor_text=blue;selection_foreground=white;selection_background=black")
        f.accept(
            "cursor;cursor_text;selection_foreground;selection_background;" +
                "cursor=?;cursor_text=?;selection_foreground=?;selection_background=?",
        )
        assertEquals(theme, f.terminal.palette)
        assertEquals(
            "\u001b]21;cursor=rgb:0101/0202/0303;cursor_text=rgb:0404/0505/0606;" +
                "selection_foreground=;selection_background=\u001b\\",
            f.drain(),
        )
    }

    @Test
    fun `empty fixed-only targets are ignored and color syntax interoperates with legacy queries`() {
        val f = Fixture()
        f.accept(
            "foreground=#3a7;foreground=;background=rgbi:1/0.5/-1@0.2;background=;" +
                "1=rgb:1/123/ffff;1=;cursor=LightGoldenrodYellow;cursor_text=not-a-color",
        )
        assertEquals(0xff30a070.toInt(), f.terminal.palette.defaultForeground)
        assertEquals(0xffff8000.toInt(), f.terminal.palette.defaultBackground)
        assertEquals(0xff1112ff.toInt(), f.terminal.palette.indexedColor(1))
        assertEquals(0xfffafad2.toInt(), f.terminal.palette.cursorBackground)
        assertEquals(0xff000000.toInt(), f.terminal.palette.cursorForeground)
        f.parser.accept("\u001b]10;?;?;?\u0007".encodeToByteArray())
        assertEquals(
            "\u001b]10;rgb:3030/a0a0/7070\u001b\\\u001b]11;rgb:ffff/8080/0000\u001b\\" +
                "\u001b]12;rgb:fafa/fafa/d2d2\u001b\\",
            f.drain(),
        )
    }

    @Test
    fun `unknown keys use safe base64 with exact case whitespace and UTF8 identity`() {
        val f = Fixture()
        f.accept("Foreground=?; foreground=?;é=?;256=?;visual_bell=?;=red;=?;;")
        assertEquals(
            "\u001b]21;unknown=Rm9yZWdyb3VuZA;unknown=IGZvcmVncm91bmQ;unknown=w6k;" +
                "unknown=MjU2;unknown=dmlzdWFsX2JlbGw\u001b\\",
            f.drain(),
        )
    }

    @Test
    fun `maximum payload reply expands safely and oversized commands are discarded atomically`() {
        val f = Fixture()
        val payload = "21;" + "x;".repeat(2046) + "x"
        assertEquals(4096, payload.length)
        f.parser.accept("\u001b]$payload\u001b\\".encodeToByteArray())
        assertEquals("\u001b]21;" + List(2047) { "unknown=eA" }.joinToString(";") + "\u001b\\", f.drain())
        val before = f.terminal.palette
        f.parser.accept(("\u001b]" + "21;foreground=red;".padEnd(4097, ';') + "\u0007").encodeToByteArray())
        assertSame(before, f.terminal.palette)
        assertEquals("", f.drain())
        f.accept("foreground=?")
        assertEquals("\u001b]21;foreground=rgb:ffff/ffff/ffff\u001b\\", f.drain())
    }

    @Test
    fun `repeated unchanged colors preserve palette identity and never send scheme notifications`() {
        val f = Fixture()
        f.parser.accept("\u001b[?2031h".encodeToByteArray())
        f.accept("foreground=#123456;cursor=;selection_background=#abcdef")
        val palette = f.terminal.palette
        f.palettes.clear()
        f.accept("foreground=#123456;cursor=;selection_background=#abcdef")
        assertSame(palette, f.terminal.palette)
        assertTrue(f.palettes.isEmpty())
        assertEquals("", f.drain())
    }

    private class Fixture {
        val terminal = TerminalBuffers.create(4, 3)
        val palettes = mutableListOf<TerminalColorPalette>()
        val sink =
            HostCommandAdapter(
                terminal,
                object : HostEventSink by HostEventSink.NONE {
                    override fun paletteChanged(palette: TerminalColorPalette) {
                        palettes += palette
                    }
                },
            )
        val parser = TerminalParsers.create(sink)

        fun accept(fields: String) {
            parser.accept("\u001b]21;$fields\u0007".encodeToByteArray())
        }

        fun drain(): String {
            val bytes = ByteArray(terminal.pendingResponseBytes)
            val size = terminal.readResponseBytes(bytes)
            return bytes.decodeToString(0, size)
        }
    }
}

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

class HostColorStackTest {
    @Test
    fun `saved OSC21 and legacy colors restore in order across every split and terminator`() {
        for (terminator in listOf("\u0007", "\u001b\\")) {
            val bytes =
                (
                    "\u001b]21;foreground=#123456;1=#abcdef;cursor=;selection_background=$terminator" +
                        "\u001b]30001$terminator" +
                        "\u001b]10;#ffffff$terminator\u001b]4;1;#000000$terminator" +
                        "\u001b]21;cursor=red;selection_background=blue$terminator" +
                        "\u001b]30101;$terminator" +
                        "\u001b]21;foreground=?;1=?;cursor=?;selection_background=?$terminator"
                ).encodeToByteArray()
            for (split in 0..bytes.size) {
                val f = Fixture()
                f.parser.accept(bytes, 0, split)
                f.parser.accept(bytes, split, bytes.size - split)
                assertEquals(
                    "\u001b]21;foreground=rgb:1212/3434/5656;1=rgb:abab/cdcd/efef;cursor=;selection_background=\u001b\\",
                    f.drain(),
                    "split=$split",
                )
                assertEquals(5, f.palettes.size, "Push and queries must not publish palette changes")
                assertEquals(f.palettes.first(), f.palettes.last())
            }
        }
    }

    @Test
    fun `palette permission gates both stack operations independently of reply and window permissions`() {
        val f = Fixture()
        val original = f.terminal.palette
        val denied = HostPolicy(palettePolicy = HostControlPolicy.DENY)
        val allowed =
            HostPolicy(
                terminalResponsePolicy = HostControlPolicy.DENY,
                windowManipulationPolicy = HostControlPolicy.DENY,
            )

        f.sink.setHostPolicy(denied)
        f.accept("\u001b]30001\u0007")
        f.sink.setHostPolicy(allowed)
        f.accept("\u001b]10;#123456\u0007\u001b]30101\u0007")
        val changed = f.terminal.palette
        assertEquals(0xff123456.toInt(), changed.defaultForeground, "Denied push must not retain a snapshot")

        f.accept("\u001b]30001\u0007\u001b]110\u0007")
        assertEquals(original, f.terminal.palette)
        f.sink.setHostPolicy(denied)
        f.accept("\u001b]30101\u0007")
        assertEquals(original, f.terminal.palette, "Denied pop must not restore or consume an entry")
        f.sink.setHostPolicy(allowed)
        f.accept("\u001b]30101\u0007")
        assertSame(changed, f.terminal.palette)
        assertEquals("", f.drain())
    }

    @Test
    fun `both terminal reset sequences clear saved colors`() {
        for (reset in listOf("\u001bc", "\u001b[!p")) {
            val f = Fixture()
            f.accept("\u001b]30001\u0007\u001b]10;#123456\u0007$reset")
            val afterReset = f.terminal.palette
            f.palettes.clear()
            f.accept("\u001b]30101\u0007")
            assertSame(afterReset, f.terminal.palette)
            assertTrue(f.palettes.isEmpty())
        }
    }

    @Test
    fun `push and unchanged pop publish nothing and effective restore never reports a theme change`() {
        val f = Fixture()
        val original = f.terminal.palette
        f.accept("\u001b[?2031h\u001b]30101\u0007\u001b]30001\u0007\u001b]30101\u0007")
        assertSame(original, f.terminal.palette)
        assertTrue(f.palettes.isEmpty())

        f.accept("\u001b]30001\u0007\u001b]10;#123456\u0007")
        f.palettes.clear()
        f.accept("\u001b]30101\u0007")
        assertEquals(listOf(original), f.palettes)
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

        fun accept(bytes: String) = parser.accept(bytes.encodeToByteArray())

        fun drain(): String {
            val bytes = ByteArray(terminal.pendingResponseBytes)
            return bytes.decodeToString(0, terminal.readResponseBytes(bytes))
        }
    }
}

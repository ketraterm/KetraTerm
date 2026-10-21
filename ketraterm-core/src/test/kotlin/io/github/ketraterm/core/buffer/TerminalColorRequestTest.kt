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
package io.github.ketraterm.core.buffer

import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.core.api.TerminalResponseChannel
import io.github.ketraterm.protocol.TerminalColorRequest
import io.github.ketraterm.protocol.TerminalColorRequest.Companion.BACKGROUND
import io.github.ketraterm.protocol.TerminalColorRequest.Companion.CURSOR
import io.github.ketraterm.protocol.TerminalColorRequest.Companion.CURSOR_TEXT
import io.github.ketraterm.protocol.TerminalColorRequest.Companion.DYNAMIC
import io.github.ketraterm.protocol.TerminalColorRequest.Companion.FOREGROUND
import io.github.ketraterm.protocol.TerminalColorRequest.Companion.QUERY
import io.github.ketraterm.protocol.TerminalColorRequest.Companion.RESET
import io.github.ketraterm.protocol.TerminalColorRequest.Companion.SELECTION_BACKGROUND
import io.github.ketraterm.protocol.TerminalColorRequest.Companion.SELECTION_FOREGROUND
import io.github.ketraterm.protocol.TerminalColorRequest.Companion.SET
import io.github.ketraterm.protocol.TerminalColorRequest.Companion.UNKNOWN
import io.github.ketraterm.render.api.TerminalColorBehavior
import io.github.ketraterm.render.api.TerminalColorPalette
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TerminalColorRequestTest {
    @Test
    fun `queries capture preceding changes and preserve duplicate and unknown ordering`() {
        val terminal = TerminalBuffers.create(4, 3)
        terminal.applyColorRequest(
            request(
                Operation("foreground", FOREGROUND, SET, 0x00123456),
                Operation("foreground", FOREGROUND, QUERY),
                Operation("x", UNKNOWN, SET),
                Operation("foreground", FOREGROUND, SET, 0x00abcdef),
                Operation("foreground", FOREGROUND, QUERY),
                Operation("xy", UNKNOWN, RESET),
                Operation("xyz", UNKNOWN, QUERY),
                Operation("000", 0, SET, 0x00445566),
                Operation("000", 0, QUERY),
            ),
            true,
        )

        assertEquals(0xffabcdef.toInt(), terminal.palette.defaultForeground)
        assertEquals(0xff445566.toInt(), terminal.palette.indexedColor(0))
        assertEquals(
            "\u001b]21;foreground=rgb:1212/3434/5656;unknown=eA;foreground=rgb:abab/cdcd/efef;" +
                "unknown=eHk;unknown=eHl6;000=rgb:4444/5555/6666\u001b\\",
            drain(terminal),
        )
    }

    @Test
    fun `all named roles mutate distinct colors and reset independently to the latest theme`() {
        val terminal = TerminalBuffers.create(4, 3)
        val latest = TerminalColorPalette(defaultBackground = 0xff876543.toInt())
        terminal.setThemePalette(TerminalColorPalette())
        terminal.setThemePalette(latest)
        terminal.setColorSchemeReportingEnabled(true)
        terminal.applyColorRequest(
            request(
                Operation("foreground", FOREGROUND, SET, 0x00111111),
                Operation("background", BACKGROUND, SET, 0x00222222),
                Operation("selection_foreground", SELECTION_FOREGROUND, SET, 0x00333333),
                Operation("selection_background", SELECTION_BACKGROUND, SET, 0x00444444),
                Operation("cursor", CURSOR, SET, 0x00555555),
                Operation("cursor_text", CURSOR_TEXT, SET, 0x00666666),
                Operation("255", 255, SET, 0x00777777),
            ),
            true,
        )
        val changed = terminal.palette
        assertEquals(0xff111111.toInt(), changed.defaultForeground)
        assertEquals(0xff222222.toInt(), changed.defaultBackground)
        assertEquals(0xff333333.toInt(), changed.selectionForeground)
        assertEquals(0xff444444.toInt(), changed.selectionBackground)
        assertEquals(0xff555555.toInt(), changed.cursorBackground)
        assertEquals(0xff666666.toInt(), changed.cursorForeground)
        assertEquals(0xff777777.toInt(), changed.indexedColor(255))
        assertEquals(
            TerminalColorBehavior.OVERRIDE_SELECTION_FOREGROUND or TerminalColorBehavior.OVERRIDE_SELECTION_BACKGROUND,
            changed.colorBehavior,
        )

        terminal.applyColorRequest(request(Operation("background", BACKGROUND, RESET)), true)
        assertEquals(latest.defaultBackground, terminal.palette.defaultBackground)
        assertEquals(changed.copy(defaultBackground = latest.defaultBackground), terminal.palette)
        terminal.applyColorRequest(
            request(
                Operation("foreground", FOREGROUND, RESET),
                Operation("selection_foreground", SELECTION_FOREGROUND, RESET),
                Operation("selection_background", SELECTION_BACKGROUND, RESET),
                Operation("cursor", CURSOR, RESET),
                Operation("cursor_text", CURSOR_TEXT, RESET),
                Operation("255", 255, RESET),
            ),
            true,
        )
        assertEquals(latest, terminal.palette)
        assertEquals("", drain(terminal), "Application color changes must not emit mode 2031 notifications")
    }

    @Test
    fun `dynamic roles query empty while foreground background and indexed dynamic requests are ignored`() {
        val terminal = TerminalBuffers.create(4, 3)
        val theme = terminal.palette
        terminal.applyColorRequest(
            request(
                Operation("foreground", FOREGROUND, DYNAMIC),
                Operation("background", BACKGROUND, DYNAMIC),
                Operation("1", 1, DYNAMIC),
                Operation("selection_foreground", SELECTION_FOREGROUND, DYNAMIC),
                Operation("selection_background", SELECTION_BACKGROUND, DYNAMIC),
                Operation("cursor", CURSOR, DYNAMIC),
                Operation("cursor_text", CURSOR_TEXT, DYNAMIC),
                Operation("selection_foreground", SELECTION_FOREGROUND, QUERY),
                Operation("selection_background", SELECTION_BACKGROUND, QUERY),
                Operation("cursor", CURSOR, QUERY),
                Operation("cursor_text", CURSOR_TEXT, QUERY),
            ),
            true,
        )
        assertEquals(theme.defaultForeground, terminal.palette.defaultForeground)
        assertEquals(theme.defaultBackground, terminal.palette.defaultBackground)
        assertEquals(theme.indexedColor(1), terminal.palette.indexedColor(1))
        assertEquals(
            "\u001b]21;selection_foreground=;selection_background=;cursor=;cursor_text=\u001b\\",
            drain(terminal),
        )
        terminal.applyColorRequest(request(Operation("cursor", CURSOR, SET, theme.cursorBackground)), true)
        assertEquals(0, terminal.palette.colorBehavior and TerminalColorBehavior.DYNAMIC_CURSOR_BACKGROUND)
        assertNotEquals(0, terminal.palette.colorBehavior and TerminalColorBehavior.DYNAMIC_CURSOR_FOREGROUND)
    }

    @Test
    fun `selection queries distinguish host presentation from independent fixed overrides and resets`() {
        val terminal = TerminalBuffers.create(4, 3)
        val theme =
            TerminalColorPalette(
                selectionForeground = 0xff112233.toInt(),
                selectionBackground = 0xff445566.toInt(),
            )
        terminal.setThemePalette(theme)
        terminal.applyColorRequest(
            request(
                Operation("selection_foreground", SELECTION_FOREGROUND, QUERY),
                Operation("selection_background", SELECTION_BACKGROUND, QUERY),
                Operation("selection_foreground", SELECTION_FOREGROUND, SET, 0xff123456.toInt()),
                Operation("selection_foreground", SELECTION_FOREGROUND, QUERY),
                Operation("selection_background", SELECTION_BACKGROUND, QUERY),
                Operation("selection_background", SELECTION_BACKGROUND, SET, 0xffabcdef.toInt()),
                Operation("selection_foreground", SELECTION_FOREGROUND, RESET),
                Operation("selection_foreground", SELECTION_FOREGROUND, QUERY),
                Operation("selection_background", SELECTION_BACKGROUND, QUERY),
                Operation("selection_background", SELECTION_BACKGROUND, RESET),
                Operation("selection_foreground", SELECTION_FOREGROUND, QUERY),
                Operation("selection_background", SELECTION_BACKGROUND, QUERY),
            ),
            true,
        )

        assertEquals(
            "\u001b]21;selection_foreground=;selection_background=;" +
                "selection_foreground=rgb:1212/3434/5656;selection_background=;" +
                "selection_foreground=;selection_background=rgb:abab/cdcd/efef;" +
                "selection_foreground=;selection_background=\u001b\\",
            drain(terminal),
        )
        assertEquals(theme, terminal.palette)
    }

    @Test
    fun `explicit fixed selection theme queries remain defined when restored from dynamic overrides`() {
        val terminal = TerminalBuffers.create(4, 3)
        val theme =
            TerminalColorPalette(selectionForeground = 0xff112233.toInt(), selectionBackground = 0xff445566.toInt())
                .copyWithColorBehavior(
                    TerminalColorBehavior.OVERRIDE_SELECTION_FOREGROUND or TerminalColorBehavior.OVERRIDE_SELECTION_BACKGROUND,
                )
        terminal.setThemePalette(theme)
        terminal.applyColorRequest(
            request(
                Operation("selection_foreground", SELECTION_FOREGROUND, QUERY),
                Operation("selection_background", SELECTION_BACKGROUND, QUERY),
                Operation("selection_foreground", SELECTION_FOREGROUND, DYNAMIC),
                Operation("selection_background", SELECTION_BACKGROUND, DYNAMIC),
                Operation("selection_foreground", SELECTION_FOREGROUND, QUERY),
                Operation("selection_background", SELECTION_BACKGROUND, QUERY),
                Operation("selection_foreground", SELECTION_FOREGROUND, RESET),
                Operation("selection_foreground", SELECTION_FOREGROUND, QUERY),
                Operation("selection_background", SELECTION_BACKGROUND, QUERY),
                Operation("selection_background", SELECTION_BACKGROUND, RESET),
                Operation("selection_foreground", SELECTION_FOREGROUND, QUERY),
                Operation("selection_background", SELECTION_BACKGROUND, QUERY),
            ),
            true,
        )

        assertEquals(
            "\u001b]21;selection_foreground=rgb:1111/2222/3333;selection_background=rgb:4444/5555/6666;" +
                "selection_foreground=;selection_background=;" +
                "selection_foreground=rgb:1111/2222/3333;selection_background=;" +
                "selection_foreground=rgb:1111/2222/3333;selection_background=rgb:4444/5555/6666\u001b\\",
            drain(terminal),
        )
        assertEquals(theme, terminal.palette)
    }

    @Test
    fun `legacy cursor write clears dynamic state even with unchanged stored color and reset restores theme behavior`() {
        val terminal = TerminalBuffers.create(4, 3)
        val theme =
            TerminalColorPalette(defaultForeground = 0xff123456.toInt(), cursorBackground = 0xff654321.toInt())
                .copyWithColorBehavior(TerminalColorBehavior.DYNAMIC_CURSOR_BACKGROUND)
        terminal.setThemePalette(theme)
        terminal.queryDynamicColor(12)
        assertEquals("\u001b]12;rgb:1212/3434/5656\u001b\\", drain(terminal))

        terminal.setDynamicColor(12, theme.cursorBackground)
        assertEquals(0, terminal.palette.colorBehavior)
        terminal.resetDynamicColor(12)
        assertEquals(theme, terminal.palette)
        terminal.applyColorRequest(request(Operation("cursor", CURSOR, QUERY)), true)
        assertEquals("\u001b]21;cursor=\u001b\\", drain(terminal))
    }

    @Test
    fun `reset restores only the selected role behavior from the theme`() {
        val terminal = TerminalBuffers.create(4, 3)
        val themeBehavior =
            TerminalColorBehavior.DYNAMIC_SELECTION_FOREGROUND or TerminalColorBehavior.OVERRIDE_SELECTION_FOREGROUND or
                TerminalColorBehavior.DYNAMIC_CURSOR_FOREGROUND
        val theme = TerminalColorPalette().copyWithColorBehavior(themeBehavior)
        terminal.setThemePalette(theme)
        terminal.applyColorRequest(
            request(
                Operation("selection_foreground", SELECTION_FOREGROUND, SET, 0x00123456),
                Operation("cursor", CURSOR, DYNAMIC),
                Operation("cursor_text", CURSOR_TEXT, SET, 0x00654321),
                Operation("selection_foreground", SELECTION_FOREGROUND, RESET),
            ),
            true,
        )
        assertEquals(theme.selectionForeground, terminal.palette.selectionForeground)
        assertEquals(0xff654321.toInt(), terminal.palette.cursorForeground)
        assertEquals(
            TerminalColorBehavior.DYNAMIC_SELECTION_FOREGROUND or TerminalColorBehavior.OVERRIDE_SELECTION_FOREGROUND or
                TerminalColorBehavior.DYNAMIC_CURSOR_BACKGROUND,
            terminal.palette.colorBehavior,
        )
    }

    @Test
    fun `denying replies still applies mutations and suppresses unknown failures`() {
        val terminal = TerminalBuffers.create(4, 3)
        terminal.applyColorRequest(
            request(
                Operation("foreground", FOREGROUND, SET, 0x00123456),
                Operation("foreground", FOREGROUND, QUERY),
                Operation("unknown", UNKNOWN, QUERY),
                Operation("unknown", UNKNOWN, RESET),
            ),
            false,
        )
        assertEquals(0xff123456.toInt(), terminal.palette.defaultForeground)
        assertEquals(0, terminal.pendingResponseBytes)
    }

    @Test
    fun `unchanged operations and queries preserve palette identity and render generations`() {
        val terminal = TerminalBuffers.create(4, 3)
        val initial = terminal.palette
        var generation = 0L
        terminal.readRenderFrame { generation = it.lineGeneration(0) }
        terminal.applyColorRequest(
            request(
                Operation("foreground", FOREGROUND, SET, initial.defaultForeground),
                Operation("foreground", FOREGROUND, RESET),
                Operation("foreground", FOREGROUND, DYNAMIC),
                Operation("foreground", FOREGROUND, QUERY),
                Operation("0", 0, SET, initial.indexedColor(0)),
                Operation("0", 0, RESET),
                Operation("0", 0, DYNAMIC),
                Operation("selection_foreground", SELECTION_FOREGROUND, RESET),
                Operation("cursor", CURSOR, RESET),
                Operation("unsupported", UNKNOWN, SET),
            ),
            true,
        )
        assertSame(initial, terminal.palette)
        terminal.readRenderFrame { assertEquals(generation, it.lineGeneration(0)) }

        val dynamic = request(Operation("cursor", CURSOR, DYNAMIC))
        terminal.applyColorRequest(dynamic, true)
        val current = terminal.palette
        terminal.readRenderFrame { generation = it.lineGeneration(0) }
        terminal.applyColorRequest(dynamic, true)
        assertSame(current, terminal.palette)
        terminal.readRenderFrame { assertEquals(generation, it.lineGeneration(0)) }
    }

    @Test
    fun `caller supplied key bytes cannot inject controls through known or unknown targets`() {
        val terminal = TerminalBuffers.create(4, 3)
        terminal.applyColorRequest(
            request(
                Operation("\n", UNKNOWN, QUERY),
                Operation("\r\n", UNKNOWN, SET),
                Operation("\u001b\\\n", UNKNOWN, RESET),
                Operation("é", UNKNOWN, QUERY),
                Operation("\n", FOREGROUND, QUERY),
                Operation("\r", 0, QUERY),
                Operation("0001", 0, QUERY),
                Operation("99999999999999999999", 0, QUERY),
            ),
            true,
        )
        assertEquals(
            "\u001b]21;unknown=Cg;unknown=DQo;unknown=G1wK;unknown=w6k;foreground=rgb:ffff/ffff/ffff;" +
                "0=rgb:0000/0000/0000;0=rgb:0000/0000/0000;0=rgb:0000/0000/0000\u001b\\",
            drain(terminal),
        )
    }

    @Test
    fun `large repeated unknown replies grow beyond the input limit without losing entries`() {
        val terminal = TerminalBuffers.create(4, 3)
        val request = TerminalColorRequest(2046)
        request.clear("x".encodeToByteArray())
        repeat(2046) { request.add(UNKNOWN, RESET, 0, 0, 1) }
        terminal.applyColorRequest(request, true)
        assertEquals("\u001b]21" + ";unknown=eA".repeat(2046) + "\u001b\\", drain(terminal))
    }

    private data class Operation(
        val key: String,
        val target: Int,
        val operation: Int,
        val color: Int = 0,
    )

    private fun request(vararg operations: Operation): TerminalColorRequest {
        val payload = operations.joinToString("") { it.key }.encodeToByteArray()
        return TerminalColorRequest(operations.size).apply {
            clear(payload)
            var offset = 0
            for (operation in operations) {
                val end = offset + operation.key.encodeToByteArray().size
                add(operation.target, operation.operation, operation.color, offset, end)
                offset = end
            }
        }
    }

    private fun drain(channel: TerminalResponseChannel): String {
        val bytes = ByteArray(channel.pendingResponseBytes)
        assertEquals(bytes.size, channel.readResponseBytes(bytes))
        return bytes.decodeToString()
    }
}

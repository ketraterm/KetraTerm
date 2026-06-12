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
package com.gagik.terminal.workspace

import com.gagik.core.TerminalBuffers
import com.gagik.parser.api.TerminalOutputParser
import com.gagik.terminal.input.api.TerminalInputEncoder
import com.gagik.terminal.input.event.TerminalFocusEvent
import com.gagik.terminal.input.event.TerminalKeyEvent
import com.gagik.terminal.input.event.TerminalMouseEvent
import com.gagik.terminal.input.event.TerminalPasteEvent
import com.gagik.terminal.render.api.TerminalRenderFrameReader
import com.gagik.terminal.render.cache.TerminalRenderPublisher
import com.gagik.terminal.session.TerminalSession
import com.gagik.terminal.transport.TerminalConnector
import com.gagik.terminal.transport.TerminalConnectorListener
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TerminalWorkspaceTest {
    @Test
    fun testTabTitleRenamingAndPrecedence() {
        val terminal = TerminalBuffers.create(width = 80, height = 24, maxHistory = 100)
        val session =
            TerminalSession(
                terminal = terminal,
                publisher = TerminalRenderPublisher(80, 24),
                renderReader = terminal as TerminalRenderFrameReader,
                responseReader = terminal,
                connector = NoOpConnector,
                parser = NoOpParser,
                inputEncoder = NoOpInputEncoder,
            )

        var titleChangedCount = 0
        var lastTitle: String? = null

        val tab =
            TerminalWorkspaceTab(
                id = "t1",
                profile = TerminalProfile("p1", "Profile 1", listOf("mock-shell")),
                title = "Initial Dynamic",
                session = session,
                onColorChanged = { _, _ -> },
                onTitleChanged = { _, t ->
                    titleChangedCount++
                    lastTitle = t
                },
            )

        // 1. Initial state
        assertEquals("Initial Dynamic", tab.title)
        assertNull(tab.customTitle)

        // 2. Setting custom title takes precedence and fires callback
        tab.customTitle = "My Custom Name"
        assertEquals("My Custom Name", tab.title)
        assertEquals("My Custom Name", lastTitle)
        assertEquals(1, titleChangedCount)

        // 3. Dynamic title changes while custom title is set: should NOT fire callback or change visible title
        tab.updateDynamicTitle("PTY Title 1")
        assertEquals("My Custom Name", tab.title)
        assertEquals("My Custom Name", lastTitle)
        assertEquals(1, titleChangedCount)

        // 4. Clearing custom title reverts back to the dynamic title and fires callback
        tab.customTitle = null
        assertEquals("PTY Title 1", tab.title)
        assertEquals("PTY Title 1", lastTitle)
        assertEquals(2, titleChangedCount)

        // 5. Dynamic title changes while custom title is null: should update title and fire callback
        tab.updateDynamicTitle("PTY Title 2")
        assertEquals("PTY Title 2", tab.title)
        assertEquals("PTY Title 2", lastTitle)
        assertEquals(3, titleChangedCount)
    }

    private object NoOpConnector : TerminalConnector {
        override fun start(listener: TerminalConnectorListener) = Unit

        override fun write(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) = Unit

        override fun resize(
            columns: Int,
            rows: Int,
        ) = Unit

        override fun close() = Unit
    }

    private object NoOpParser : TerminalOutputParser {
        override fun accept(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) = Unit

        override fun acceptByte(byteValue: Int) = Unit

        override fun endOfInput() = Unit

        override fun reset() = Unit
    }

    private object NoOpInputEncoder : TerminalInputEncoder {
        override fun encodeKey(event: TerminalKeyEvent) = Unit

        override fun encodePaste(event: TerminalPasteEvent) = Unit

        override fun encodeFocus(event: TerminalFocusEvent) = Unit

        override fun encodeMouse(event: TerminalMouseEvent) = Unit
    }
}

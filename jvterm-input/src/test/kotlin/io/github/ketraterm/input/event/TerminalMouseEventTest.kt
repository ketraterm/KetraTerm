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
package io.github.ketraterm.input.event

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class TerminalMouseEventTest {
    @Test
    fun `rejects negative column`() {
        assertThrows(IllegalArgumentException::class.java) {
            TerminalMouseEvent(
                column = -1,
                row = 0,
                button = TerminalMouseButton.LEFT,
                type = TerminalMouseEventType.PRESS,
            )
        }
    }

    @Test
    fun `rejects negative row`() {
        assertThrows(IllegalArgumentException::class.java) {
            TerminalMouseEvent(
                column = 0,
                row = -1,
                button = TerminalMouseButton.LEFT,
                type = TerminalMouseEventType.PRESS,
            )
        }
    }

    @Test
    fun `rejects invalid modifier bits`() {
        assertThrows(IllegalArgumentException::class.java) {
            TerminalMouseEvent(
                column = 0,
                row = 0,
                button = TerminalMouseButton.LEFT,
                type = TerminalMouseEventType.PRESS,
                modifiers = 1 shl 12,
            )
        }
    }

    @Test
    fun `rejects press without concrete button`() {
        assertThrows(IllegalArgumentException::class.java) {
            TerminalMouseEvent(
                column = 0,
                row = 0,
                button = TerminalMouseButton.NONE,
                type = TerminalMouseEventType.PRESS,
            )
        }
    }

    @Test
    fun `rejects press with wheel button`() {
        assertThrows(IllegalArgumentException::class.java) {
            TerminalMouseEvent(
                column = 0,
                row = 0,
                button = TerminalMouseButton.WHEEL_UP,
                type = TerminalMouseEventType.PRESS,
            )
        }
    }

    @Test
    fun `rejects release with wheel button`() {
        assertThrows(IllegalArgumentException::class.java) {
            TerminalMouseEvent(
                column = 0,
                row = 0,
                button = TerminalMouseButton.WHEEL_DOWN,
                type = TerminalMouseEventType.RELEASE,
            )
        }
    }

    @Test
    fun `rejects wheel event with non-wheel button`() {
        assertThrows(IllegalArgumentException::class.java) {
            TerminalMouseEvent(
                column = 0,
                row = 0,
                button = TerminalMouseButton.LEFT,
                type = TerminalMouseEventType.WHEEL,
            )
        }
    }

    @Test
    fun `rejects motion with wheel button`() {
        assertThrows(IllegalArgumentException::class.java) {
            TerminalMouseEvent(
                column = 0,
                row = 0,
                button = TerminalMouseButton.WHEEL_LEFT,
                type = TerminalMouseEventType.MOTION,
            )
        }
    }

    @Test
    fun `accepts no-button motion`() {
        val event =
            TerminalMouseEvent(
                column = 2,
                row = 3,
                button = TerminalMouseButton.NONE,
                type = TerminalMouseEventType.MOTION,
            )

        assertEquals(TerminalMouseButton.NONE, event.button)
    }

    @Test
    fun `accepts release without button identity`() {
        val event =
            TerminalMouseEvent(
                column = 2,
                row = 3,
                button = TerminalMouseButton.NONE,
                type = TerminalMouseEventType.RELEASE,
            )

        assertEquals(TerminalMouseEventType.RELEASE, event.type)
    }
}

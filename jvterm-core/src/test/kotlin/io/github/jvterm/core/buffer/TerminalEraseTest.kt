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
package io.github.jvterm.core.buffer

import io.github.jvterm.core.TerminalBuffers
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TerminalEraseTest {
    @Test
    fun `ed3_clearsScrollbackButPreservesViewport_ifTargetingXtermSemantics`() {
        val buffer = TerminalBuffers.create(width = 3, height = 2, maxHistory = 4)
        buffer.writeText("abc")
        buffer.newLine()
        buffer.writeText("def")
        buffer.newLine()
        buffer.writeText("ghi")

        val screenBefore = buffer.getScreenAsString()

        buffer.eraseScreenAndHistory()

        assertAll(
            { assertEquals(screenBefore, buffer.getScreenAsString(), "ED 3 should preserve the visible viewport") },
            { assertEquals(0, buffer.historySize, "ED 3 should clear scrollback history") },
        )
    }
}

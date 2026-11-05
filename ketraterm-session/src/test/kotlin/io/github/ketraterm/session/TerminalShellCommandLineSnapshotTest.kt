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

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class TerminalShellCommandLineSnapshotTest {
    @Test
    fun `Kotlin copy calls preserve defaults and allow explicit confirmation changes`() {
        val original = TerminalShellCommandLineSnapshot("source", 2, 3, 4, requiresShellConfirmation = true)
        assertEquals(original, original.copy())
        assertEquals(TerminalShellCommandLineSnapshot("source", 2, 3, 9, true), original.copy(cursorRow = 9))
        assertEquals(
            TerminalShellCommandLineSnapshot("source", 2, 3, 4, false),
            original.copy(requiresShellConfirmation = false),
        )
    }

    @Test
    fun `original four-field copy descriptor preserves confirmation requirements`() {
        val copy =
            TerminalShellCommandLineSnapshot::class.java.getMethod(
                "copy",
                String::class.java,
                Integer.TYPE,
                Integer.TYPE,
                Integer.TYPE,
            )
        for (requiresConfirmation in listOf(false, true)) {
            val original = TerminalShellCommandLineSnapshot("source", 2, 3, 4, requiresConfirmation)
            assertEquals(
                TerminalShellCommandLineSnapshot("updated", 1, 5, 6, requiresConfirmation),
                copy.invoke(original, "updated", 1, 5, 6),
            )
        }
    }

    @Test
    fun `original copy default descriptor keeps every field mask and confirmation requirement`() {
        val copyDefault =
            TerminalShellCommandLineSnapshot::class.java.getMethod(
                "copy\$default",
                TerminalShellCommandLineSnapshot::class.java,
                String::class.java,
                Integer.TYPE,
                Integer.TYPE,
                Integer.TYPE,
                Integer.TYPE,
                Any::class.java,
            )
        for (requiresConfirmation in listOf(false, true)) {
            val original = TerminalShellCommandLineSnapshot("source", 2, 3, 4, requiresConfirmation)
            for (mask in 0..15) {
                val expected =
                    TerminalShellCommandLineSnapshot(
                        commandText = if (mask and 1 != 0) original.commandText else "updated",
                        cursorOffset = if (mask and 2 != 0) original.cursorOffset else 1,
                        cursorColumn = if (mask and 4 != 0) original.cursorColumn else 5,
                        cursorRow = if (mask and 8 != 0) original.cursorRow else 6,
                        requiresShellConfirmation = requiresConfirmation,
                    )
                assertEquals(
                    expected,
                    copyDefault.invoke(null, original, "updated", 1, 5, 6, mask, null),
                    "Legacy copy mask $mask, requires confirmation $requiresConfirmation",
                )
            }
        }
    }
}

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
package io.github.ketraterm.intellij.services

import io.github.ketraterm.completion.api.TerminalShellCapabilities
import io.github.ketraterm.workspace.TerminalProfileKind
import org.junit.Assert.assertEquals
import org.junit.Test

class IntellijCompletionShellCapabilitiesTest {
    @Test
    fun `Git Bash uses POSIX quoting with Windows path separators`() {
        assertEquals(
            TerminalShellCapabilities.POSIX.copy(backslashPathSeparator = true),
            TerminalProfileKind.GIT_BASH.intellijCompletionShellCapabilities(),
        )
        assertEquals(TerminalShellCapabilities.POWERSHELL, TerminalProfileKind.POWERSHELL.intellijCompletionShellCapabilities())
    }

    @Test
    fun `command prompt uses Windows path separators with conservative parsing`() {
        assertEquals(
            TerminalShellCapabilities.PLAIN.copy(backslashPathSeparator = true),
            TerminalProfileKind.COMMAND_PROMPT.intellijCompletionShellCapabilities(),
        )
    }

    @Test
    fun `native and WSL POSIX profiles preserve literal backslashes`() {
        for (kind in listOf(
            TerminalProfileKind.BASH,
            TerminalProfileKind.ZSH,
            TerminalProfileKind.WSL,
            TerminalProfileKind.UBUNTU,
            TerminalProfileKind.UNIX_SHELL,
        )) {
            assertEquals(kind.name, TerminalShellCapabilities.POSIX, kind.intellijCompletionShellCapabilities())
        }
    }
}

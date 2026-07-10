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
package io.github.ketraterm.app.completion

import io.github.ketraterm.completion.api.TerminalShellCapabilities
import io.github.ketraterm.workspace.TerminalProfileKind
import kotlin.test.Test
import kotlin.test.assertEquals

class StandaloneCompletionShellCapabilitiesTest {
    @Test
    fun `maps PowerShell profile to PowerShell completion capabilities`() {
        assertEquals(TerminalShellCapabilities.POWERSHELL, TerminalProfileKind.POWERSHELL.completionShellCapabilities())
    }

    @Test
    fun `maps tested POSIX profiles to POSIX completion capabilities`() {
        val profiles =
            listOf(
                TerminalProfileKind.GIT_BASH,
                TerminalProfileKind.UBUNTU,
                TerminalProfileKind.BASH,
                TerminalProfileKind.ZSH,
                TerminalProfileKind.WSL,
                TerminalProfileKind.UNIX_SHELL,
            )

        assertEquals(List(profiles.size) { TerminalShellCapabilities.POSIX }, profiles.map { it.completionShellCapabilities() })
    }

    @Test
    fun `maps unsupported and unknown shell profiles to conservative capabilities`() {
        val profiles =
            listOf(
                TerminalProfileKind.COMMAND_PROMPT,
                TerminalProfileKind.FISH,
                TerminalProfileKind.NUSHELL,
                TerminalProfileKind.DEFAULT,
            )

        assertEquals(List(profiles.size) { TerminalShellCapabilities.PLAIN }, profiles.map { it.completionShellCapabilities() })
    }
}

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
package io.github.jvterm.workspace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TerminalShellIntegrationBootstrapTest {
    @Test
    fun `PowerShell profile receives interactive OSC 133 bootstrap command`() {
        val profile =
            TerminalProfile(
                id = "powershell",
                displayName = "PowerShell",
                command = listOf("pwsh.exe", "-NoLogo"),
            )

        val integrated = TerminalShellIntegrationBootstrap.apply(profile, enabled = true)

        assertEquals("pwsh.exe", integrated.command[0])
        assertTrue("-NoLogo" in integrated.command)
        assertTrue("-NoExit" in integrated.command)
        assertEquals("-Command", integrated.command[integrated.command.lastIndex - 1])

        val script = integrated.command.last()
        assertTrue(script.contains("function global:prompt"))
        assertTrue(script.contains("function global:PSConsoleHostReadLine"))
        assertTrue(script.contains("]133;"))
        assertTrue(script.contains("D;$"))
        assertFalse(script.contains('"'))
    }

    @Test
    fun `PowerShell profile with existing NoExit does not duplicate it`() {
        val profile =
            TerminalProfile(
                id = "powershell",
                displayName = "PowerShell",
                command = listOf("powershell.exe", "-NoLogo", "-NoExit"),
            )

        val integrated = TerminalShellIntegrationBootstrap.apply(profile, enabled = true)

        assertEquals(1, integrated.command.count { it.equals("-NoExit", ignoreCase = true) })
    }

    @Test
    fun `explicit PowerShell entrypoint is not rewritten`() {
        val profile =
            TerminalProfile(
                id = "powershell",
                displayName = "PowerShell",
                command = listOf("pwsh.exe", "-NoLogo", "-Command", "Write-Host already-custom"),
            )

        val integrated = TerminalShellIntegrationBootstrap.apply(profile, enabled = true)

        assertSame(profile, integrated)
    }

    @Test
    fun `disabled shell integration leaves PowerShell profile unchanged`() {
        val profile =
            TerminalProfile(
                id = "powershell",
                displayName = "PowerShell",
                command = listOf("pwsh.exe", "-NoLogo"),
            )

        val integrated = TerminalShellIntegrationBootstrap.apply(profile, enabled = false)

        assertSame(profile, integrated)
    }

    @Test
    fun `non PowerShell profiles are not rewritten`() {
        val profile =
            TerminalProfile(
                id = "bash",
                displayName = "Bash",
                command = listOf("/bin/bash", "-l"),
            )

        val integrated = TerminalShellIntegrationBootstrap.apply(profile, enabled = true)

        assertSame(profile, integrated)
        assertFalse(integrated.command.any { it.contains("133") })
    }
}

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
package io.github.ketraterm.workspace

import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.session.TerminalSessionState
import io.github.ketraterm.shell.launch.TerminalShellLaunch
import io.github.ketraterm.testkit.MockConnector
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerminalShellLaunchTest {
    @Test
    fun `preparation detaches caller collections and preserves explicit programs`(
        @TempDir scripts: Path,
    ) {
        val command = mutableListOf("pwsh", "-Command", "Write-Output test")
        val environment = mutableMapOf("HOST_VALUE" to "original")
        TerminalShellLaunch.prepare(TerminalProfile("test", "test", command, environment), scriptDirectory = scripts).use { launch ->
            command[2] = "changed"
            environment["HOST_VALUE"] = "changed"
            assertEquals(listOf("pwsh", "-Command", "Write-Output test"), launch.profile.command)
            assertEquals("original", launch.profile.environment["HOST_VALUE"])
            assertFalse(launch.promptMarkersExpected)
            assertFalse(launch.profile.environment.containsKey("KetraTerm_COMMAND_INVENTORY_DIRECTORY"))
        }
    }

    @Test
    fun `closing an unstarted launch reclaims its private mailbox`(
        @TempDir scripts: Path,
    ) {
        val launch = TerminalShellLaunch.prepare(TerminalProfile("bash", "Bash", listOf("bash")), scriptDirectory = scripts)
        val mailbox = Path.of(launch.profile.environment.getValue("KetraTerm_COMMAND_INVENTORY_DIRECTORY"))
        assertTrue(Files.isDirectory(mailbox))
        launch.close()
        launch.close()
        assertFalse(Files.exists(mailbox))
        assertTrue(Files.isDirectory(scripts))
    }

    @Test
    fun `attachment leaves process startup to the host and rejects reuse`(
        @TempDir scripts: Path,
    ) {
        TerminalShellLaunch.prepare(TerminalProfile("test", "test", listOf("pwsh")), scriptDirectory = scripts).use { launch ->
            TerminalSession
                .create(
                    TerminalBuffers.create(80, 4),
                    MockConnector(),
                    shellIntegration = launch.shellIntegration,
                ).use { session ->
                    launch.attach(session)
                    assertEquals(TerminalSessionState.Created, session.state.value)
                    assertFailsWith<IllegalStateException> { launch.attach(session) }
                }
        }
    }

    @Test
    fun `closed handles reject attachment`(
        @TempDir scripts: Path,
    ) {
        val launch = TerminalShellLaunch.prepare(TerminalProfile("test", "test", listOf("pwsh")), scriptDirectory = scripts)
        launch.close()
        TerminalSession.create(TerminalBuffers.create(80, 4), MockConnector()).use { session ->
            assertFailsWith<IllegalStateException> { launch.attach(session) }
        }
    }

    @Test
    fun `native metadata reaches a host-created session without a custom parser`(
        @TempDir scripts: Path,
    ) {
        TerminalShellLaunch.prepare(TerminalProfile("test", "test", listOf("pwsh")), scriptDirectory = scripts).use { launch ->
            val connector = MockConnector()
            TerminalSession.create(TerminalBuffers.create(80, 4), connector, shellIntegration = launch.shellIntegration).use { session ->
                launch.attach(session)
                session.start()
                connector.feedFromHost("\u001b]7;file://localhost/tmp\u0007\u001b]133;A\u0007PS> \u001b]133;B\u0007ca".toByteArray())
                assertEquals("file://localhost/tmp", session.shellIntegrationState.currentWorkingDirectoryUri())
                assertEquals("ca", session.activeShellCommandLine()?.commandText)
            }
        }
    }
}

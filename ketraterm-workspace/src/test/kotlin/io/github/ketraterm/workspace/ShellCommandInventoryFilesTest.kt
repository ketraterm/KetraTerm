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
import io.github.ketraterm.shell.integration.OscShellIntegration
import io.github.ketraterm.testkit.MockConnector
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ShellCommandInventoryFilesTest {
    @Test
    fun `only complete files publish and early files are retried after Ready`(
        @TempDir root: Path,
    ) = runTest {
        val mailbox = ShellCommandInventoryFiles(Files.createDirectory(root.resolve("inventory")))
        val connector = MockConnector()
        val dispatcher = StandardTestDispatcher(testScheduler)
        TerminalSession
            .create(
                TerminalBuffers.create(80, 4),
                connector,
                shellIntegration = OscShellIntegration.configured(true, mailbox.input),
                workerDispatcher = dispatcher,
                ioDispatcher = dispatcher,
            ).use { session ->
                session.start()
                val pending = mailbox.directory.resolve("path.pending")
                val ready = mailbox.directory.resolve("path.ready")
                Files.write(pending, snapshot(1, "git"))
                mailbox.poll()
                assertNull(session.shellCommandInventory.value)
                Files.move(pending, ready, ATOMIC_MOVE)
                mailbox.poll()
                assertNull(session.shellCommandInventory.value)
                connector.feedFromHost(frame("Ready;$EPOCH"))
                mailbox.poll()
                assertEquals(
                    listOf("git"),
                    session.shellCommandInventory.value
                        ?.commands
                        ?.map { it.displayText },
                )
                Files.write(pending, snapshot(2, "tar"))
                Files.move(pending, ready, ATOMIC_MOVE, REPLACE_EXISTING)
                mailbox.poll()
                assertEquals(
                    listOf("tar"),
                    session.shellCommandInventory.value
                        ?.commands
                        ?.map { it.displayText },
                )
                mailbox.close()
                mailbox.close()
                assertFalse(Files.exists(mailbox.directory))
            }
    }

    @Test
    fun `session closure reclaims a mailbox even if the session was never started`(
        @TempDir root: Path,
    ) = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val mailbox = ShellCommandInventoryFiles(Files.createDirectory(root.resolve("inventory")), dispatcher = dispatcher)
        val session =
            TerminalSession.create(
                TerminalBuffers.create(80, 4),
                MockConnector(),
                shellIntegration = OscShellIntegration.configured(true, mailbox.input),
                workerDispatcher = dispatcher,
                ioDispatcher = dispatcher,
            )
        mailbox.observe(session)
        session.close()
        runCurrent()
        assertFalse(Files.exists(mailbox.directory))
    }

    @Test
    fun `WSL inventory directory is exported with guest path translation`(
        @TempDir root: Path,
    ) {
        val launch =
            TerminalShellIntegrationBootstrap.apply(
                TerminalProfile("wsl", "WSL", listOf("wsl.exe", "-d", "Ubuntu", "--", "bash", "-i")),
                enabled = true,
                scriptDirectory = root,
                commandInventoryDirectory = root.resolve("mailbox"),
            )
        assertTrue(launch.promptMarkersExpected)
        assertTrue(
            launch.profile.environment
                .getValue("WSLENV")
                .split(':')
                .contains("KetraTerm_COMMAND_INVENTORY_DIRECTORY/up"),
        )
        assertEquals(
            root.resolve("mailbox").toString().replace('\\', '/'),
            launch.profile.environment["KetraTerm_COMMAND_INVENTORY_DIRECTORY"],
        )
    }

    companion object {
        private val EPOCH = "a".repeat(32)

        private fun frame(body: String) = "\u001b]633;KetraTermCommands;3;$body\u0007".toByteArray()

        private fun snapshot(
            scan: Int,
            name: String,
        ): ByteArray {
            val body = "$name\u0000\u0000C\u0000\u0000".toByteArray()
            return frame("Begin;$EPOCH;$scan") + frame("Chunk;$EPOCH;$scan;0;${Base64.getEncoder().encodeToString(body)}") +
                frame("End;$EPOCH;$scan;1;1;${body.size}")
        }
    }
}

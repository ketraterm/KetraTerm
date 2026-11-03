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
package io.github.ketraterm.shell.integration

import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.input.event.TerminalPasteEvent
import io.github.ketraterm.session.TerminalInputAdmission
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.session.TerminalShellCommandInventoryStatus
import io.github.ketraterm.testkit.MockConnector
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ShellCommandInventoryInputTest {
    @Test
    fun `inventory arrival between prompt bytes preserves the parser and editable line`() =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler)).use { f ->
                f.ready(EPOCH)
                f.connector.feedFromHost("\u001b]133;A\u0007$ \u001b]133;".toByteArray())
                assertTrue(f.input.accept(snapshot(EPOCH, 1, "git")))
                f.connector.feedFromHost("B\u0007git".toByteArray())
                assertEquals("git", f.session.activeShellCommandLine()?.commandText)
                assertEquals(listOf("git"), f.names())
                val expected = assertNotNull(f.session.captureCommandEdit())
                assertTrue(f.input.accept(snapshot(EPOCH, 2, "grep")))
                assertEquals(TerminalInputAdmission.ACCEPTED, f.session.submitInput(expected, listOf(TerminalPasteEvent(" status"))))
            }
        }

    @Test
    fun `file arrival waits for live epoch and rejects superseded environment`() =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler)).use { f ->
                val bytes = snapshot(EPOCH, 1, "git")
                assertFalse(f.input.accept(bytes))
                assertNull(f.session.shellCommandInventory.value)
                f.ready(EPOCH)
                assertTrue(f.input.accept(bytes))
                assertEquals(listOf("git"), f.names())
                f.ready(OTHER_EPOCH)
                assertFalse(f.input.accept(bytes))
                assertEquals(
                    TerminalShellCommandInventoryStatus.PENDING,
                    f.session.shellCommandInventory.value
                        ?.status,
                )
                assertTrue(f.input.accept(snapshot(OTHER_EPOCH, 1, "cargo")))
                assertEquals(listOf("cargo"), f.names())
            }
        }

    @Test
    fun `whole envelope is checked before any frame can alter authority or terminal state`() =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler)).use { f ->
                f.ready(EPOCH)
                val valid = snapshot(EPOCH, 1, "git")
                val rejected =
                    listOf(
                        valid + "oops".toByteArray(),
                        valid + "\u001b]133;B\u0007".toByteArray(),
                        valid + frame("Ready;$OTHER_EPOCH"),
                        valid.copyOf(valid.size - 1),
                        frame("Begin;$EPOCH;1"),
                        ByteArray(ShellCommandInventoryInput.MAX_SNAPSHOT_BYTES + 1),
                    )
                for (bytes in rejected) {
                    assertTrue(f.input.accept(bytes))
                    assertEquals(
                        TerminalShellCommandInventoryStatus.PENDING,
                        f.session.shellCommandInventory.value
                            ?.status,
                    )
                    assertNull(f.session.activeShellCommandLine())
                }
                assertTrue(f.input.accept(valid))
                assertEquals(listOf("git"), f.names())
            }
        }

    @Test
    fun `close drops late files and a failed refresh retains a complete inventory`() =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler)).use { f ->
                f.ready(EPOCH)
                assertTrue(f.input.accept(snapshot(EPOCH, 1, "git")))
                assertTrue(f.input.accept(frame("Begin;$EPOCH;2") + frame("Fail;$EPOCH;2;Overflow")))
                assertEquals(listOf("git"), f.names())
                f.session.close()
                assertTrue(f.input.accept(snapshot(EPOCH, 3, "cargo")))
                assertNull(f.session.shellCommandInventory.value)
            }
        }

    private class Fixture(
        dispatcher: kotlinx.coroutines.CoroutineDispatcher,
    ) : AutoCloseable {
        val input = ShellCommandInventoryInput()
        val connector = MockConnector()
        val session =
            TerminalSession
                .create(
                    TerminalBuffers.create(80, 4),
                    connector,
                    shellIntegration = OscShellIntegration.configured(true, input),
                    workerDispatcher = dispatcher,
                    ioDispatcher = dispatcher,
                ).also { it.start() }

        fun ready(epoch: String) = connector.feedFromHost(frame("Ready;$epoch"))

        fun names() = assertNotNull(session.shellCommandInventory.value).commands.map { it.displayText }

        override fun close() = session.close()
    }

    companion object {
        private val EPOCH = "a".repeat(32)
        private val OTHER_EPOCH = "b".repeat(32)

        private fun frame(body: String) = "\u001b]633;KetraTermCommands;3;$body\u0007".toByteArray()

        private fun snapshot(
            epoch: String,
            scan: Int,
            name: String,
        ): ByteArray {
            val body = "$name\u0000\u0000C\u0000\u0000".toByteArray()
            return frame("Begin;$epoch;$scan") +
                frame("Chunk;$epoch;$scan;0;${Base64.getEncoder().encodeToString(body)}") +
                frame("End;$epoch;$scan;1;1;${body.size}")
        }
    }
}

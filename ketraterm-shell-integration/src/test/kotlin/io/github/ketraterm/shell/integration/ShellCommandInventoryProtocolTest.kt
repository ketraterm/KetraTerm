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
import io.github.ketraterm.session.*
import io.github.ketraterm.testkit.MockConnector
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import java.util.*
import kotlin.test.*

class ShellCommandInventoryProtocolTest {
    @Test
    fun `combined source budgets reject overflow and recover after local removal`() =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler)).use { f ->
                f.feed("Ready;$EPOCH")
                f.feed("Begin;$EPOCH;1")
                val bodies =
                    (0 until TerminalShellCommandInventory.MAX_COMMANDS)
                        .chunked(1000)
                        .map { names -> names.joinToString("") { record("c-$it") } }
                bodies.forEachIndexed { i, body -> f.feed("Chunk;$EPOCH;1;$i;${encode(body)}") }
                f.feed("End;$EPOCH;1;${bodies.size};${TerminalShellCommandInventory.MAX_COMMANDS};${bodies.sumOf { it.length }}")
                f.feed("Ready;$EPOCH", local = true)
                f.snapshot(1, record("extra"), local = true)
                assertEquals(TerminalShellCommandInventoryStatus.UNAVAILABLE, f.inventory.status)
                f.feed("Begin;$EPOCH;2", local = true)
                f.feed("End;$EPOCH;2;0;0;0", local = true)
                assertEquals(TerminalShellCommandInventory.MAX_COMMANDS, f.inventory.commands.size)
            }
        }

    @Test
    fun `live commands arrive before PATH and alias changes reuse discovery`() =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler)).use { f ->
                f.feed("Ready;$EPOCH")
                f.feed("Begin;$EPOCH;1")
                f.feed("Ready;$EPOCH", local = true)
                f.snapshot(1, "git\u0000\u0000A\u0000git status\u0000", local = true)
                assertEquals("git status", f.inventory.aliases["git"])
                f.feed("Ready;$OTHER_EPOCH", local = true)
                f.snapshot(2, record("function"), local = true, epoch = OTHER_EPOCH)
                assertEquals(listOf("function"), f.inventory.commands.map { it.displayText })
                // PATH discovery remains valid across a shell-local change.
                f.feed("Chunk;$EPOCH;1;0;${encode(record("git"))}")
                f.feed("End;$EPOCH;1;1;1;${record("git").length}")
                assertEquals(listOf("function", "git"), f.inventory.commands.map { it.displayText })
                f.snapshot(3, "git\u0000\u0000A\u0000git switch\u0000", local = true, epoch = OTHER_EPOCH)
                assertEquals(listOf("git"), f.inventory.commands.map { it.displayText })
                assertEquals("git switch", f.inventory.aliases["git"])
                f.feed("Ready;$OTHER_EPOCH")
                assertEquals("git switch", f.inventory.aliases["git"])
                f.snapshot(4, record("obsolete"))
                assertEquals(listOf("git"), f.inventory.commands.map { it.displayText })
            }
        }

    @Test
    fun `Unicode chunks preserve quoted insertion and literal alias definitions`() =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler)).use { f ->
                f.feed("Ready;$EPOCH")
                f.feed("Begin;$EPOCH;1")
                val body = "λ tool\u0000'λ tool'\u0000C\u0000\u0000gλ\u0000\u0000A\u0000git -C 'répo' switch\u0000"
                f.feed("Chunk;$EPOCH;1;0;${encode(body)}", bytewise = true)
                f.feed("End;$EPOCH;1;1;2;${body.toByteArray().size}")
                assertEquals(mapOf("gλ" to "git -C 'répo' switch"), f.inventory.aliases)
                assertEquals(
                    "'λ tool'",
                    f.inventory
                        .matchingCommands("λ", 1)
                        .single()
                        .replacementText,
                )
            }
        }

    @Test
    fun `wire and chunk budgets also bound low text and repeated insertions`() =
        runTest {
            for (wireBudget in listOf(false, true)) {
                Fixture(StandardTestDispatcher(testScheduler)).use { f ->
                    f.feed("Ready;$EPOCH")
                    f.feed("Begin;$EPOCH;1")
                    var sequence = 0
                    if (wireBudget) {
                        (0 until 3200).chunked(30).forEach { indices ->
                            val body =
                                indices.joinToString("") {
                                    val name = "名".repeat(240) + it
                                    "$name\u0000$name\u0000C\u0000\u0000"
                                }
                            f.feed("Chunk;$EPOCH;1;${sequence++};${encode(body)}")
                        }
                    } else {
                        repeat(ShellCommandInventoryProtocol.MAX_CHUNKS + 1) {
                            f.feed("Chunk;$EPOCH;1;${sequence++};${encode(record("c-$it"))}")
                        }
                    }
                    assertEquals(TerminalShellCommandInventoryStatus.UNAVAILABLE, f.inventory.status)
                }
            }
        }

    @Test
    fun `large snapshot is published only after its verified end across byte boundaries`() =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler)).use { fixture ->
                fixture.feed("Ready;$EPOCH")
                fixture.feed("Begin;$EPOCH;1")
                val bodies = (0 until 12_000).map { record("command-$it") }.chunked(1000).map { it.joinToString("") }
                bodies.forEachIndexed { index, body ->
                    fixture.feed("Chunk;$EPOCH;1;$index;${encode(body)}", bytewise = index == 0)
                    assertEquals(TerminalShellCommandInventoryStatus.PENDING, fixture.inventory.status)
                    assertTrue(fixture.inventory.commands.isEmpty())
                }
                fixture.feed("End;$EPOCH;1;12;12000;${bodies.sumOf { it.toByteArray().size }}")
                assertEquals(TerminalShellCommandInventoryStatus.READY, fixture.inventory.status)
                assertEquals(12_000, fixture.inventory.commands.size)
                assertEquals(
                    "command-9999",
                    fixture.inventory
                        .matchingCommands("command-9999", 10)
                        .single()
                        .displayText,
                )
                assertFalse(fixture.session.shellCompletionAvailable)
                assertTrue(fixture.connector.writtenBytes.isEmpty())
            }
        }

    @Test
    fun `same environment refresh retains complete authority and changed environment rejects late results`() =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler)).use { f ->
                f.feed("Ready;$EPOCH")
                f.snapshot(1, record("git"))
                val first = f.inventory
                f.feed("Ready;$EPOCH")
                f.feed("Begin;$EPOCH;2")
                f.feed("Chunk;$EPOCH;2;0;${encode(record("new"))}")
                assertSame(first, f.inventory)
                f.feed("Fail;$EPOCH;2;Overflow")
                assertSame(first, f.inventory)
                f.feed("Ready;$OTHER_EPOCH")
                assertEquals(TerminalShellCommandInventoryStatus.PENDING, f.inventory.status)
                f.snapshot(3, record("obsolete"))
                assertTrue(f.inventory.commands.isEmpty())
                f.feed("Begin;$OTHER_EPOCH;1")
                f.feed("End;$OTHER_EPOCH;1;0;0;0")
                assertEquals(TerminalShellCommandInventoryStatus.READY, f.inventory.status)
                assertTrue(f.inventory.commands.isEmpty())
                assertEquals("git", first.commands.single().displayText)
            }
        }

    @Test
    fun `newer scan retires previous assembly and replay cannot replace it`() =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler)).use { f ->
                f.feed("Ready;$EPOCH")
                f.feed("Begin;$EPOCH;1")
                f.feed("Chunk;$EPOCH;1;0;${encode(record("old"))}")
                f.snapshot(2, record("new"))
                val current = f.inventory
                f.snapshot(1, record("old"))
                f.feed("End;$EPOCH;1;1;1;${record("old").length}")
                assertSame(current, f.inventory)
                assertEquals("new", current.commands.single().displayText)
            }
        }

    @Test
    fun `malformed chunks and incorrect totals fail closed and a later scan recovers`() =
        runTest {
            val body = record("git")
            val invalid =
                listOf(
                    "Chunk;$EPOCH;1;1;${encode(body)}", // missing sequence zero
                    "Chunk;$EPOCH;1;0;!",
                    "Chunk;$EPOCH;1;0;${Base64.getEncoder().encodeToString(byteArrayOf(0xc3.toByte(), 0x28))}",
                    "Chunk;$EPOCH;1;0;${encode("git\u0000")}",
                    "Chunk;$EPOCH;1;0;${encode(record("bad\nname"))}",
                    "Chunk;$EPOCH;1;0;${encode(record(""))}",
                    "Chunk;$EPOCH;1;0;${encode(record("x".repeat(513)))}",
                    "Chunk;$EPOCH;1;0;${encode("git\u0000${"x".repeat(4097)}\u0000C\u0000\u0000")}",
                    "Chunk;$EPOCH;1;0;${encode("git\u0000\u0000X\u0000\u0000")}",
                    "Chunk;$EPOCH;1;0;${encode("git\u0000\u0000C\u0000definition\u0000")}",
                    "Chunk;$EPOCH;1;0;${encode("git\u0000\u0000A\u0000${"x".repeat(4097)}\u0000")}",
                    "Chunk;$EPOCH;1;0;${encode("x".repeat(49_153))}",
                    "End;$EPOCH;1;1;0;0",
                    "End;$EPOCH;1;0;1;0",
                    "End;$EPOCH;1;0;0;1",
                    "Fail;$EPOCH;1;Overflow",
                )
            for (message in invalid) {
                Fixture(StandardTestDispatcher(testScheduler)).use { f ->
                    f.feed("Ready;$EPOCH")
                    f.feed("Begin;$EPOCH;1")
                    f.feed(message)
                    assertEquals(TerminalShellCommandInventoryStatus.UNAVAILABLE, f.inventory.status, message.take(100))
                    assertTrue(f.inventory.commands.isEmpty())
                    f.snapshot(2, body)
                    assertEquals(
                        "git",
                        f.inventory.commands
                            .single()
                            .displayText,
                    )
                }
            }
        }

    @Test
    fun `duplicate chunks or names never publish a partial snapshot`() =
        runTest {
            for (duplicateChunk in listOf(true, false)) {
                Fixture(StandardTestDispatcher(testScheduler)).use { f ->
                    f.feed("Ready;$EPOCH")
                    f.feed("Begin;$EPOCH;1")
                    val body = record("git")
                    f.feed("Chunk;$EPOCH;1;0;${encode(body)}")
                    f.feed("Chunk;$EPOCH;1;${if (duplicateChunk) 0 else 1};${encode(body)}")
                    f.feed("End;$EPOCH;1;2;2;${body.length * 2}")
                    assertEquals(TerminalShellCommandInventoryStatus.UNAVAILABLE, f.inventory.status)
                }
            }
        }

    @Test
    fun `entry and retained text budgets reject a whole snapshot`() =
        runTest {
            for (longAliases in listOf(false, true)) {
                Fixture(StandardTestDispatcher(testScheduler)).use { f ->
                    f.feed("Ready;$EPOCH")
                    f.feed("Begin;$EPOCH;1")
                    val count = if (longAliases) 513 else TerminalShellCommandInventory.MAX_COMMANDS + 1
                    val records =
                        (0 until count).map {
                            if (longAliases) "alias-$it\u0000\u0000A\u0000${"x".repeat(4096)}\u0000" else record("c-$it")
                        }
                    records.chunked(if (longAliases) 10 else 1000).forEachIndexed { index, chunk ->
                        f.feed("Chunk;$EPOCH;1;$index;${encode(chunk.joinToString(""))}")
                    }
                    assertEquals(TerminalShellCommandInventoryStatus.UNAVAILABLE, f.inventory.status)
                }
            }
        }

    @Test
    fun `deadline and session closure release incomplete authority and reject late publication`() =
        runTest {
            var now = 0L
            Fixture(StandardTestDispatcher(testScheduler), { now }).use { f ->
                f.protocol.observe("KetraTermCommands;3;Ready;$EPOCH".split(';'))
                f.protocol.observe("KetraTermCommands;3;Begin;$EPOCH;1".split(';'))
                now = ShellCommandInventoryProtocol.MAX_AGE_NANOS - 1
                f.protocol.expire()
                assertEquals(TerminalShellCommandInventoryStatus.PENDING, f.inventory.status)
                now++
                f.protocol.expire()
                assertEquals(TerminalShellCommandInventoryStatus.UNAVAILABLE, f.inventory.status)
                f.session.close()
                f.channel.publishCommands(TerminalShellCommandInventory(emptyList()))
                assertNull(f.session.shellCommandInventory.value)
            }
        }

    private class Fixture(
        dispatcher: kotlinx.coroutines.CoroutineDispatcher,
        clock: () -> Long = System::nanoTime,
    ) : AutoCloseable {
        val connector = MockConnector()
        lateinit var channel: TerminalShellCompletionChannel
        lateinit var protocol: ShellCommandInventoryProtocol
        val session =
            TerminalSession
                .create(
                    TerminalBuffers.create(80, 4),
                    connector,
                    shellIntegration =
                        TerminalShellIntegrationFactory { context ->
                            channel = context.shellCompletion
                            protocol = ShellCommandInventoryProtocol(channel, clock)
                            OscShellIntegration.create(context)
                        },
                    workerDispatcher = dispatcher,
                    ioDispatcher = dispatcher,
                ).also { it.start() }
        val inventory get() = assertNotNull(session.shellCommandInventory.value)

        fun feed(
            message: String,
            bytewise: Boolean = false,
            local: Boolean = false,
        ) {
            val source = if (local) "KetraTermShellCommands" else "KetraTermCommands"
            val bytes = "\u001b]633;$source;3;$message\u0007".toByteArray()
            if (bytewise) bytes.forEach { connector.feedFromHost(byteArrayOf(it)) } else connector.feedFromHost(bytes)
        }

        fun snapshot(
            scan: Int,
            body: String,
            local: Boolean = false,
            epoch: String = EPOCH,
        ) {
            feed("Begin;$epoch;$scan", local = local)
            feed("Chunk;$epoch;$scan;0;${encode(body)}", local = local)
            feed("End;$epoch;$scan;1;1;${body.toByteArray().size}", local = local)
        }

        override fun close() = session.close()
    }

    companion object {
        private val EPOCH = "a".repeat(32)
        private val OTHER_EPOCH = "b".repeat(32)

        private fun record(name: String) = "$name\u0000\u0000C\u0000\u0000"

        private fun encode(body: String) = Base64.getEncoder().encodeToString(body.toByteArray())
    }
}

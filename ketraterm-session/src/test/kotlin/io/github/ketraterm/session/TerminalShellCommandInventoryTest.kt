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

import kotlin.test.*

class TerminalShellCommandInventoryTest {
    @Test
    fun `snapshot owns sorted storage and cached immutable alias lookup`() {
        val name = "git".toCharArray().concatToString()
        val replacement = name.toCharArray().concatToString()
        val borrowed = mutableListOf(command("zsh"), command(name, replacement), command("gco", alias = "git checkout"))
        val snapshot = TerminalShellCommandInventory(borrowed)
        borrowed.clear()
        assertEquals(listOf("gco", "git", "zsh"), snapshot.commands.map { it.displayText })
        assertSame(snapshot.commands[1].displayText, snapshot.commands[1].replacementText)
        assertEquals(mapOf("gco" to "git checkout"), snapshot.aliases)
        assertSame(snapshot.aliases, snapshot.aliases)
        assertFailsWith<UnsupportedOperationException> { (snapshot.commands as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (snapshot.aliases as MutableMap).clear() }
    }

    @Test
    fun `prefix lookup preserves exact case unicode and limits at both ends`() {
        val inventory = TerminalShellCommandInventory(listOf("z", "gitk", "Git", "git", "gita", "λtool").map { command(it) })

        fun names(
            prefix: String,
            limit: Int = 10,
        ) = inventory.matchingCommands(prefix, limit).map { it.displayText }
        assertEquals(listOf("git", "gita"), names("gi", 2))
        assertEquals(listOf("Git"), names("G"))
        assertEquals(listOf("λtool"), names("λ"))
        assertEquals(listOf("Git"), names("", 1))
        assertEquals(listOf("z"), names("z", Int.MAX_VALUE))
        assertTrue(names("zz").isEmpty())
        assertTrue(names("!missing").isEmpty())
        assertFailsWith<IllegalArgumentException> { inventory.matchingCommands("", 0) }
        assertFailsWith<UnsupportedOperationException> { (inventory.matchingCommands("gi", 2) as MutableList).clear() }
    }

    @Test
    fun `invalid and over-budget snapshots are rejected before publication`() {
        val invalid =
            listOf(
                listOf(command("same"), command("same")),
                listOf(command("")),
                listOf(command("bad\nname")),
                listOf(command("name", "")),
                listOf(command("x".repeat(513))),
                listOf(command("name", "x".repeat(4097))),
                listOf(command("name", alias = "x".repeat(4097))),
                List(TerminalShellCommandInventory.MAX_COMMANDS + 1) { command("c$it") },
                List(513) { command("a$it", alias = "x".repeat(4096)) },
            )
        for (commands in invalid) assertFailsWith<IllegalArgumentException> { TerminalShellCommandInventory(commands) }
        for (status in listOf(TerminalShellCommandInventoryStatus.PENDING, TerminalShellCommandInventoryStatus.UNAVAILABLE)) {
            assertFailsWith<IllegalArgumentException> { TerminalShellCommandInventory(listOf(command("git")), status) }
            assertTrue(TerminalShellCommandInventory(emptyList(), status).matchingCommands("", 1).isEmpty())
        }
    }

    private fun command(
        name: String,
        replacement: String = name,
        alias: String? = null,
    ) = TerminalShellCompletionCandidate(replacement, name, "", TerminalShellCompletionKind.COMMAND, alias)
}

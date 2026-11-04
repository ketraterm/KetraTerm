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

import io.github.ketraterm.input.event.*
import io.github.ketraterm.render.api.TerminalRenderAttrs
import io.github.ketraterm.session.TerminalInputAdmission
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.session.TerminalShellCompletionKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@Tag("native")
class TerminalShellIntegrationBootstrapNativeTest {
    @Test
    fun `Bash collector preserves PATH semantics and isolates directory and glob state`(
        @TempDir tempDir: Path,
    ) {
        val bash = installedExecutable("bash", "bash.exe")
        assumeTrue(bash != null, "Bash is not installed")
        assumeBashCommandInventory(bash!!)
        assertPathCollector(listOf(bash, "--noprofile", "--norc"), tempDir)
    }

    @Test
    fun `zsh collector preserves PATH semantics and isolates directory and glob state`(
        @TempDir tempDir: Path,
    ) {
        val zsh = installedExecutable("zsh", "zsh.exe")
        assumeTrue(zsh != null, "zsh is not installed")
        assertPathCollector(listOf(zsh!!, "-f"), tempDir)
    }

    @Test
    fun `Bash incomplete or overflowing collector never publishes successful PATH inventory`() {
        val bash = installedExecutable("bash", "bash.exe")
        assumeTrue(bash != null, "Bash is not installed")
        assumeBashCommandInventory(bash!!)
        val hook = checkNotNull(javaClass.getResourceAsStream("bash-completion.bash")).use { it.readBytes().toString(Charsets.UTF_8) }
        for ((output, reason) in listOf("git\\0" to "Unavailable", "__ketraterm_inventory_overflow" to "Overflow")) {
            val script = hook + "\n__ketraterm_collect_path_commands() { printf '$output'; }\n__ketraterm_publish_commands test 1 Path\n"
            val result = runProcess(listOf(bash, "--noprofile", "--norc"), standardInput = script)
            assertTrue(result.stdout.contains("KetraTermCommands;3;Fail;test;1;$reason"))
            assertTrue(!result.stdout.contains("KetraTermCommands;3;End;"))
        }
    }

    private suspend fun TerminalSession.awaitLine(line: String) {
        try {
            withTimeout(15.seconds) {
                activeShellCommandLineRevision.first { activeShellCommandLine()?.commandText == line }
            }
        } catch (failure: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("Expected editable line '$line', observed ${activeShellCommandLine()}, session ${state.value}", failure)
        }
    }

    private fun assertPathCollector(
        command: List<String>,
        directory: Path,
    ) {
        val collector = checkNotNull(javaClass.getResourceAsStream("path-commands.sh")).use { it.readBytes().toString(Charsets.UTF_8) }
        val script =
            collector + "\n" +
                """
                mkdir 'first dir' second 'first dir/not-a-command'
                for file in 'first dir/ketra tool' 'second/ketra tool' second/ketra-other .ketra-hidden ketra-current; do
                    printf '#!/bin/sh\n' > "${'$'}file"
                    chmod +x "${'$'}file"
                done
                printf 'not executable' > second/ketra-not-executable
                chmod -x second/ketra-not-executable
                origin=${'$'}PWD
                chpwd() { printf 'unexpected chpwd hook'; }
                ketra_directory_hook() { printf 'unexpected directory hook'; }
                chpwd_functions=(ketra_directory_hook)
                PATH='first dir:missing:second:first dir::'
                set -f
                __ketraterm_collect_path_commands
                [[ ${'$'}PWD == "${'$'}origin" ]] || exit 11
                [[ ${'$'}- == *f* ]] || exit 12
                """.trimIndent()
        val result = runProcess(command, standardInput = script, workingDirectory = directory)
        assertEquals(0, result.exitCode, visibleEscapes(result.stdout))
        val records = result.stdout.split('\u0000')
        assertEquals("__ketraterm_inventory_end", records.last())
        assertEquals(setOf("ketra tool", "ketra-other", ".ketra-hidden", "ketra-current"), records.dropLast(1).toSet())
        assertEquals(5, records.size)
    }

    @Test
    fun `Bash PATH discovery identity changes with cwd only for relative entries`() {
        val bash = installedExecutable("bash", "bash.exe")
        assumeTrue(bash != null, "Bash is not installed")
        assumeBashCommandInventory(bash!!)
        assertPathDiscoveryIdentity(listOf(bash, "--noprofile", "--norc", "-i"), "__ketraterm_completion")
    }

    @Test
    fun `zsh PATH discovery identity changes with cwd only for relative entries`() {
        val zsh = installedExecutable("zsh", "zsh.exe")
        assumeTrue(zsh != null, "zsh is not installed")
        assertPathDiscoveryIdentity(listOf(zsh!!, "-f", "-i"), "__ketraterm_zsh_completion")
    }

    private fun assertPathDiscoveryIdentity(
        command: List<String>,
        prefix: String,
    ): Unit =
        runBlocking {
            TerminalWorkspace().use { workspace ->
                val session =
                    workspace
                        .openTab(
                            TerminalProfile("discovery", "Discovery", command),
                            TerminalWorkspaceOpenOptions(),
                        ).session
                withTimeout(15.seconds) {
                    session.activeShellCommandLineRevision.first { session.activeShellCommandLine()?.commandText == "" }
                }
                for ((path, directory, operator, marker) in listOf(
                    listOf("/usr/bin:/bin", "/", "==", "ketra_absolute_ok"),
                    listOf(".:/usr/bin:/bin", "/tmp", "!=", "ketra_relative_ok"),
                    listOf(":/usr/bin:/bin", "/", "!=", "ketra_empty_entry_ok"),
                )) {
                    val script =
                        "PATH=$path; ${prefix}_ready; before=\"${'$'}${prefix}_path_epoch\"; " +
                            "cd $directory; ${prefix}_ready; " +
                            "[[ ${'$'}before $operator ${'$'}${prefix}_path_epoch ]] && $marker() { :; }"
                    session.submitInput(listOf(TerminalPasteEvent(script), TerminalKeyEvent.key(TerminalKey.ENTER)))
                    withTimeout(15.seconds) {
                        session.shellCommandInventory.first { it?.commands?.any { c -> c.displayText == marker } == true }
                        session.activeShellCommandLineRevision.first { session.activeShellCommandLine()?.commandText == "" }
                    }
                }
            }
        }

    @Test
    fun `Bash inventories cross old limits and report overflow without a successful partial end`() {
        val bash = installedExecutable("bash", "bash.exe")
        assumeTrue(bash != null, "Bash is not installed")
        assumeBashCommandInventory(bash!!)
        assertLargeInventory(listOf(bash, "--noprofile", "--norc"), "bash-completion.bash", "__ketraterm_publish_commands")
    }

    @Test
    fun `zsh inventories cross old limits and report overflow without a successful partial end`() {
        val zsh = installedExecutable("zsh", "zsh.exe")
        assumeTrue(zsh != null, "zsh is not installed")
        assertLargeInventory(listOf(zsh!!, "-f"), "zsh-completion.zsh", "__ketraterm_zsh_publish_commands")
    }

    private fun assertLargeInventory(
        command: List<String>,
        resource: String,
        publisher: String,
    ) {
        val hook =
            checkNotNull(javaClass.getResourceAsStream("path-commands.sh")).use { it.readBytes().toString(Charsets.UTF_8) } + "\n" +
                checkNotNull(javaClass.getResourceAsStream(resource)).use { it.readBytes().toString(Charsets.UTF_8) }
        val script =
            hook + "\n" +
                """
                PATH=/usr/bin:/bin
                for ((i=0; i<10000; i++)); do eval "ketra_large_command_${'$'}i() { :; }"; done
                $publisher test 1 Local
                printf -v long_definition '%4096s' x
                for ((i=0; i<513; i++)); do alias "ketra_overflow_${'$'}i=${'$'}long_definition"; done
                $publisher test 2 Local
                """.trimIndent()
        val result = runProcess(command, standardInput = script)
        assertEquals(0, result.exitCode)
        val secondStart = result.stdout.indexOf("\u001b]633;KetraTermShellCommands;3;Begin;test;2\u0007")
        assertTrue(secondStart > 0)
        val complete = commandInventories(result.stdout.substring(0, secondStart)).single()
        assertTrue(complete.size > 10000)
        assertTrue(complete.any { it[0] == "ketra_large_command_9999" })
        val overflow = result.stdout.substring(secondStart)
        assertTrue(overflow.contains("KetraTermShellCommands;3;Fail;test;2;Overflow"))
        assertTrue(!overflow.contains("KetraTermShellCommands;3;End;"))
    }

    @Test
    fun `zsh inventory reflects enabled commands and literal aliases without executing user code`(
        @TempDir tempDir: Path,
    ) {
        val zsh = installedExecutable("zsh", "zsh.exe")
        assumeTrue(zsh != null, "zsh is not installed")
        val hook =
            checkNotNull(javaClass.getResourceAsStream("path-commands.sh")).use { it.readBytes().toString(Charsets.UTF_8) } + "\n" +
                checkNotNull(javaClass.getResourceAsStream("zsh-completion.zsh")).use { it.readBytes().toString(Charsets.UTF_8) }
        val script =
            hook + "\n" +
                """
                PATH="${'$'}PWD:/usr/bin:/bin"
                ketra_function() { touch function-executed; }
                ketra_disabled() { touch function-executed; }
                disable -f ketra_disabled
                disable pushd
                autoload -Uz ketra_autoload
                alias ketra_alias='git switch'
                alias ketra_literal='git ${'$'}(touch alias-executed)'
                printf -v long_definition '%4097s' x
                alias ketra_long="${'$'}long_definition"
                alias -g ketra_global='| cat'
                alias -s ketra_suffix=cat
                functions[${'$'}'ketra\ninvalid']=':'
                functions[${'$'}'ketra\u0085invalid']=':'
                printf '#!/bin/sh\n' > 'ketra executable'
                printf '#!/bin/sh\n' > "ketra'quoted"
                printf '#!/bin/sh\n' > 'ketra-λ'
                chmod +x 'ketra executable' "ketra'quoted" 'ketra-λ'
                hash ketra_cached="${'$'}PWD/ketra executable"
                __ketraterm_zsh_publish_commands test 1 Local
                __ketraterm_zsh_publish_commands test 1 Path
                alias ketra_alias='git checkout'
                unfunction ketra_function
                rm 'ketra executable'
                mkdir next
                printf '#!/bin/sh\n' > next/ketra_next
                chmod +x next/ketra_next
                PATH="${'$'}PWD/next:/usr/bin:/bin"
                __ketraterm_zsh_publish_commands test 1 Local
                __ketraterm_zsh_publish_commands test 1 Path
                setopt KSH_ARRAYS SH_WORD_SPLIT NO_UNSET
                unsetopt ALIASES
                __ketraterm_zsh_publish_commands test 1 Local
                __ketraterm_zsh_publish_commands test 1 Path
                [[ -o KSH_ARRAYS && -o SH_WORD_SPLIT && -o NO_UNSET && ! -o ALIASES && -o HASH_LIST_ALL ]] || exit 12
                """.trimIndent()
        val result = runProcess(listOf(zsh!!, "-f"), standardInput = script, workingDirectory = tempDir)
        assertEquals(0, result.exitCode, visibleEscapes(result.stdout))
        val inventories =
            commandInventories(result.stdout).chunked(2).map { (local, path) ->
                (path + local).associateBy { it[0] }.values.toList()
            }
        assertEquals(3, inventories.size)
        val sourceSnapshots = commandInventories(result.stdout)
        assertTrue(sourceSnapshots[0].none { it[0] == "ketra executable" })
        assertTrue(sourceSnapshots[1].none { it[0] == "ketra_function" || it[2] == "Alias" })
        val first = inventories[0].associateBy { it[0] }
        assertEquals(listOf("ketra_alias", "ketra_alias", "Alias", "git switch"), first["ketra_alias"])
        assertEquals("git ${'$'}(touch alias-executed)", first["ketra_literal"]?.get(3))
        assertEquals(listOf("ketra_long", "ketra_long", "Alias", ""), first["ketra_long"])
        assertEquals(listOf("ketra_function", "ketra_function", "Command", ""), first["ketra_function"])
        assertEquals("Command", first["ketra_autoload"]?.get(2))
        assertEquals("Command", first["printf"]?.get(2))
        assertEquals("'ketra executable'", first["ketra executable"]?.get(1))
        assertEquals("'ketra'\\''quoted'", first["ketra'quoted"]?.get(1))
        assertEquals("'ketra-λ'", first["ketra-λ"]?.get(1))
        assertEquals("Command", first["ketra_cached"]?.get(2))
        val second = inventories[1].associateBy { it[0] }
        assertEquals("git checkout", second["ketra_alias"]?.get(3))
        assertEquals("Command", second["ketra_next"]?.get(2))
        assertTrue(listOf("ketra_function", "ketra executable", "ketra_cached", "ketra'quoted").none { it in second })
        assertTrue(inventories[2].none { it[2] == "Alias" })
        val excluded = setOf("ketra_disabled", "ketra_global", "ketra_suffix", "pushd", "if", "!")
        assertTrue(inventories.all { entries -> entries.none { it[0] in excluded || it[0].startsWith("__ketraterm_") } })
        assertTrue(inventories.all { entries -> entries.none { it[0].any(Char::isISOControl) } })
        assertTrue(Files.notExists(tempDir.resolve("alias-executed")))
        assertTrue(Files.notExists(tempDir.resolve("function-executed")))
    }

    @Test
    fun `zsh inventory refreshes at prompts and permits guarded acceptance through ZLE`(
        @TempDir tempDir: Path,
    ): Unit =
        runBlocking {
            val zsh = installedExecutable("zsh", "zsh.exe")
            assumeTrue(zsh != null, "zsh is not installed")
            TerminalWorkspace().use { workspace ->
                val tab =
                    workspace.openTab(
                        TerminalProfile(
                            "zsh",
                            "Zsh",
                            listOf(zsh!!, "-d", "-i"),
                            environment = mapOf("ZDOTDIR" to tempDir.toString(), "PROMPT" to "ketra> ", "PATH" to "/usr/bin:/bin"),
                        ),
                        TerminalWorkspaceOpenOptions(),
                    )
                val session = tab.session

                session.awaitLine("")
                withTimeout(15.seconds) {
                    session.shellCommandInventory.first { commands -> commands?.commands?.any { it.displayText == "printf" } == true }
                }
                for (definition in listOf("git switch", "git checkout")) {
                    session.submitInput(
                        listOf(TerminalPasteEvent("alias ketra_live='$definition'"), TerminalKeyEvent.key(TerminalKey.ENTER)),
                    )
                    withTimeout(15.seconds) {
                        session.shellCommandInventory.first { commands ->
                            commands?.commands?.any { it.displayText == "ketra_live" && it.aliasExpansion == definition } == true
                        }
                    }
                    session.awaitLine("")
                }
                session.submitInput(TerminalPasteEvent("ketra_l"))
                session.awaitLine("ketra_l")
                val expected = assertNotNull(session.captureCommandEdit())
                val candidate = assertNotNull(session.shellCommandInventory.value).commands.single { it.displayText == "ketra_live" }
                assertEquals(
                    TerminalInputAdmission.ACCEPTED,
                    session.submitInput(expected, listOf(TerminalTextReplacementEvent(0, 7, candidate.replacementText))),
                )
                session.awaitLine("ketra_live")
                session.submitInput(
                    listOf(TerminalTextReplacementEvent(0, 10, "unalias ketra_live"), TerminalKeyEvent.key(TerminalKey.ENTER)),
                )
                withTimeout(15.seconds) {
                    session.shellCommandInventory.first { commands ->
                        commands != null &&
                            commands.commands.any { it.displayText == "printf" } &&
                            commands.commands.none { it.displayText == "ketra_live" }
                    }
                }
                session.awaitLine("")
            }
        }

    @Test
    fun `Bash command inventory refreshes functions aliases and executable availability`(
        @TempDir tempDir: Path,
    ) {
        val bash = installedExecutable("bash", "bash.exe")
        assumeTrue(bash != null, "Bash is not installed")
        assumeBashCommandInventory(bash!!)
        val hook =
            checkNotNull(javaClass.getResourceAsStream("path-commands.sh")).use { it.readBytes().toString(Charsets.UTF_8) } + "\n" +
                checkNotNull(javaClass.getResourceAsStream("bash-completion.bash")).use { it.readBytes().toString(Charsets.UTF_8) }
        val script =
            hook + "\n" +
                """
                shopt -s expand_aliases
                ketra_native_function() { :; }
                alias ketra_native_alias='printf alias'
                alias ketra_same='git switch'
                alias ketra_literal='git ${'$'}(touch alias-executed)'
                printf '#!/bin/sh\n' > 'ketra native executable'
                chmod +x 'ketra native executable'
                export PATH="${'$'}PWD:/usr/bin:/bin"
                cp "${'$'}(type -P true)" ketra_native_exe.exe
                printf '#!/bin/sh\n' > KETRA_CASE
                chmod +x KETRA_CASE
                cp "${'$'}(type -P true)" ketra_case.exe
                cp "${'$'}(type -P true)" ketra_hashed.exe
                hash -p "${'$'}(type -P false)" ketra_hashed.exe
                __ketraterm_publish_commands test 1 Local
                __ketraterm_publish_commands test 1 Path
                alias ketra_native_exe='printf shadowed'
                alias ketra_same='git checkout'
                unalias ketra_native_alias
                unset -f ketra_native_function
                rm 'ketra native executable'
                __ketraterm_publish_commands test 1 Local
                __ketraterm_publish_commands test 1 Path
                unalias ketra_native_exe
                unalias ketra_same
                unalias ketra_literal
                hash -d ketra_hashed.exe
                rm ketra_native_exe.exe ketra_case.exe KETRA_CASE ketra_hashed.exe
                __ketraterm_publish_commands test 1 Local
                __ketraterm_publish_commands test 1 Path
                """.trimIndent()
        val result = runProcess(listOf(bash, "--noprofile", "--norc"), standardInput = script, workingDirectory = tempDir)
        assertEquals(0, result.exitCode, visibleEscapes(result.stdout))
        val inventories =
            commandInventories(result.stdout).chunked(2).map { (local, path) ->
                (path + local).associateBy { it[0] }.values.toList()
            }
        assertEquals(3, inventories.size, visibleEscapes(result.stdout))
        assertTrue(inventories[0].contains(listOf("ketra_native_function", "ketra_native_function", "Command", "")))
        assertTrue(inventories[0].contains(listOf("ketra_native_alias", "ketra_native_alias", "Alias", "printf alias")))
        assertTrue(inventories[0].contains(listOf("ketra_same", "ketra_same", "Alias", "git switch")))
        assertTrue(inventories[0].contains(listOf("ketra_literal", "ketra_literal", "Alias", "git ${'$'}(touch alias-executed)")))
        assertTrue(Files.notExists(tempDir.resolve("alias-executed")), "Discovery must not execute alias definitions")
        assertTrue(inventories[1].contains(listOf("ketra_same", "ketra_same", "Alias", "git checkout")))
        assertTrue(inventories[0].contains(listOf("ketra native executable", "'ketra native executable'", "Command", "")))
        assertTrue(inventories[0].contains(listOf("printf", "printf", "Command", "")))
        assertTrue(inventories.all { inventory -> inventory.none { it[0] == "!" || it[0] == "if" } })
        if (isWindows()) {
            assertEquals("ketra_case.exe", inventories[0].single { it[0] == "ketra_case.exe" }[1])
            assertEquals("ketra_hashed.exe", inventories[0].single { it[0] == "ketra_hashed.exe" }[1])
        }
        val executableReplacement = if (isWindows()) "ketra_native_exe" else "ketra_native_exe.exe"
        assertTrue(
            inventories[0].contains(listOf("ketra_native_exe.exe", executableReplacement, "Command", "")),
            inventories[0].filter { it[0].startsWith("ketra") }.toString(),
        )
        assertTrue(inventories[1].contains(listOf("ketra_native_exe.exe", "ketra_native_exe.exe", "Command", "")))
        assertTrue(inventories[1].contains(listOf("ketra_native_exe", "ketra_native_exe", "Alias", "printf shadowed")))
        assertTrue(inventories[2].none { it[0].startsWith("ketra") })
        assertTrue(inventories.all { it.size <= 32768 && it.map { entry -> entry[0] }.distinct().size == it.size })
    }

    @Test
    fun `Bash inventory survives deletion and guarded acceptance through real Readline`(
        @TempDir tempDir: Path,
    ): Unit =
        runBlocking {
            val bash = installedExecutable("bash", "bash.exe")
            assumeTrue(bash != null, "Bash is not installed")
            assumeBashCommandInventory(bash!!)
            val inputrc = tempDir.resolve("inputrc")
            Files.writeString(inputrc, "set enable-active-region on\nset enable-bracketed-paste on\nset editing-mode emacs\n")
            TerminalWorkspace().use { workspace ->
                val tab =
                    workspace.openTab(
                        TerminalProfile(
                            "bash",
                            "Bash",
                            listOf(bash, "--noprofile", "--norc", "-i", "-u"),
                            environment = mapOf("PS1" to "ketra> ", "PATH" to "/usr/bin:/bin", "INPUTRC" to inputrc.toString()),
                        ),
                        TerminalWorkspaceOpenOptions(),
                    )
                val session = tab.session

                suspend fun awaitRenderedLine(
                    line: String,
                    highlighted: Boolean,
                ) {
                    withTimeout(15.seconds) {
                        session.renderGeneration.first {
                            val command = session.activeShellCommandLine()
                            if (command?.commandText != line) return@first false
                            var matches = false
                            session.readRenderFrame { frame ->
                                val attrs = LongArray(frame.columns)
                                frame.copyLine(
                                    command.cursorRow,
                                    IntArray(frame.columns),
                                    attrWords = attrs,
                                    flags = IntArray(frame.columns),
                                )
                                val start = command.cursorColumn - line.length
                                matches = start >= 0 &&
                                    (start until command.cursorColumn).all { column ->
                                        TerminalRenderAttrs.isInverse(attrs[column]) == highlighted
                                    }
                            }
                            matches
                        }
                    }
                }
                session.awaitLine("")
                withTimeout(15.seconds) {
                    session.shellCommandInventory.first { commands -> commands?.commands?.any { it.displayText == "printf" } == true }
                }
                session.submitInput(TerminalPasteEvent("prin"))
                awaitRenderedLine("prin", highlighted = true)
                session.submitInput(TerminalKeyEvent.key(TerminalKey.BACKSPACE))
                session.awaitLine("pri")
                val expected = assertNotNull(session.captureCommandEdit())
                val candidate = assertNotNull(session.shellCommandInventory.value).commands.single { it.displayText == "printf" }
                session.submitInput(TerminalKeyEvent.key(TerminalKey.BACKSPACE, type = TerminalKeyEventType.RELEASE))
                assertEquals(
                    TerminalInputAdmission.ACCEPTED,
                    session.submitInput(expected, listOf(TerminalTextReplacementEvent(0, 3, candidate.replacementText))),
                )
                awaitRenderedLine("printf", highlighted = false)
                session.submitInput(TerminalTextReplacementEvent(0, 0, "123"))
                awaitRenderedLine("printf123", highlighted = false)
                // An empty closing paste must not create another undo step.
                session.submitInput(TerminalKeyEvent.codepoint('_'.code, TerminalModifiers.CTRL))
                session.awaitLine("printf")
                for (definition in listOf("git switch", "git checkout")) {
                    session.submitInput(
                        listOf(
                            TerminalTextReplacementEvent(0, 6, "alias ketra_same='$definition'"),
                            TerminalKeyEvent.key(TerminalKey.ENTER),
                        ),
                    )
                    session.awaitLine("")
                    withTimeout(15.seconds) {
                        session.shellCommandInventory.first { commands ->
                            commands?.commands?.any { it.displayText == "ketra_same" && it.aliasExpansion == definition } == true
                        }
                    }
                    session.submitInput(TerminalPasteEvent("printf"))
                    session.awaitLine("printf")
                }
            }
        }

    @Test
    fun `PowerShell native completion round trips through real PSReadLine and PTY`(): Unit =
        runBlocking {
            val executable = installedExecutable("pwsh", "powershell.exe")
            assumeTrue(executable != null, "PowerShell is not installed")
            TerminalWorkspace().use { workspace ->
                val tab =
                    workspace.openTab(
                        TerminalProfile("powershell", "PowerShell", listOf(executable!!, "-NoLogo", "-NoProfile")),
                        TerminalWorkspaceOpenOptions(),
                    )
                val session = tab.session

                session.awaitLine("")
                val emptyContext = assertNotNull(session.captureCommandEdit())
                val emptyResult = assertNotNull(session.requestShellCompletions("", 0))
                assertTrue(emptyResult.candidates.isNotEmpty())
                assertTrue(emptyResult.candidates.size <= 128)
                assertEquals(TerminalInputAdmission.ACCEPTED, session.submitInput(emptyContext, listOf(TerminalPasteEvent("Get-Chi"))))
                session.awaitLine("Get-Chi")
                val expected = assertNotNull(session.captureCommandEdit())
                val result = assertNotNull(session.requestShellCompletions("Get-Chi", 7))
                assertTrue(result.candidates.any { it.replacementText == "Get-ChildItem" })
                val replacement = TerminalTextReplacementEvent(result.replacementStartOffset, result.replacementEndOffset, "Get-ChildItem")
                assertEquals(TerminalInputAdmission.ACCEPTED, session.submitInput(expected, listOf(replacement)))
                session.awaitLine("Get-ChildItem")
                repeat(3) {
                    assertEquals(
                        TerminalInputAdmission.ACCEPTED,
                        session.submitInput(
                            listOf(
                                TerminalTextReplacementEvent(
                                    0,
                                    "Get-ChildItem".length - "Get-Chi".length,
                                    "",
                                ),
                            ),
                        ),
                    )
                    session.awaitLine("Get-Chi")
                    val afterDeletion = assertNotNull(session.captureCommandEdit())
                    val refreshed = assertNotNull(session.requestShellCompletions("Get-Chi", 7))
                    assertTrue(refreshed.candidates.any { it.replacementText == "Get-ChildItem" })
                    assertEquals(
                        TerminalInputAdmission.ACCEPTED,
                        session.submitInput(TerminalKeyEvent.key(TerminalKey.BACKSPACE, type = TerminalKeyEventType.RELEASE)),
                    )
                    assertEquals(
                        TerminalInputAdmission.ACCEPTED,
                        session.submitInput(afterDeletion, listOf(TerminalTextReplacementEvent(0, 7, "Get-ChildItem"))),
                    )
                    session.awaitLine("Get-ChildItem")
                }
                val command = "Write-Output 'ketra-accepted'"
                assertEquals(
                    TerminalInputAdmission.ACCEPTED,
                    session.submitInput(
                        assertNotNull(session.captureCommandEdit()),
                        listOf(TerminalTextReplacementEvent(0, "Get-ChildItem".length, command), TerminalKeyEvent.key(TerminalKey.ENTER)),
                    ),
                )
                withTimeout(15.seconds) {
                    session.shellIntegrationState.revision.first {
                        val state = session.shellIntegrationState
                        state.commandText(state.latestCommandRecordId()) == command
                    }
                }
                session.awaitLine("")
                val encodingLine = "cat .\\utf8-extended.txt -Encoding u"
                assertEquals(
                    TerminalInputAdmission.ACCEPTED,
                    session.submitInput(assertNotNull(session.captureCommandEdit()), listOf(TerminalPasteEvent(encodingLine))),
                )
                session.awaitLine(encodingLine)
                val encodingEdit = assertNotNull(session.captureCommandEdit())
                val encodings = assertNotNull(session.requestShellCompletions(encodingLine, encodingLine.length))
                assertTrue(encodings.candidates.any { it.replacementText.equals("Unicode", ignoreCase = true) })
                val utf8 = encodings.candidates.first { it.replacementText.equals("UTF8", ignoreCase = true) }
                assertTrue(encodings.candidates.all { it.kind == TerminalShellCompletionKind.ARGUMENT })
                assertEquals(encodingLine.length - 1, encodings.replacementStartOffset)
                assertEquals(encodingLine.length, encodings.replacementEndOffset)
                assertEquals(
                    TerminalInputAdmission.ACCEPTED,
                    session.submitInput(
                        encodingEdit,
                        listOf(
                            TerminalTextReplacementEvent(
                                deleteAfterCursorCount = encodings.replacementEndOffset - encodings.cursorOffset,
                                deleteBeforeCursorCount = encodings.cursorOffset - encodings.replacementStartOffset,
                                replacementText = utf8.replacementText,
                            ),
                        ),
                    ),
                )
                session.awaitLine(encodingLine.dropLast(1) + utf8.replacementText)
            }
        }

    @Test
    fun `PowerShell native hook uses functions aliases and registered completers from its own session`() {
        val executable = installedExecutable("pwsh", "powershell.exe")
        assumeTrue(executable != null, "PowerShell is not installed")

        fun resource(name: String) = checkNotNull(javaClass.getResourceAsStream(name)).use { it.readBytes().toString(Charsets.UTF_8) }
        val script = resource("powershell-completion-test.ps1").replace("__SCRIPT__", resource("powershell-completion.ps1"))
        val result =
            runProcess(
                listOf(
                    executable!!,
                    "-NoProfile",
                    "-NonInteractive",
                    "-EncodedCommand",
                    Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE)),
                ),
                emptyMap(),
            )
        assertEquals(0, result.exitCode, visibleEscapes(result.stdout))
        val messages =
            result.stdout
                .lineSequence()
                .filter { it.contains("KetraTermCompletion;1;Result;") }
                .toList()
        val application =
            result.stdout
                .lineSequence()
                .firstOrNull { it.startsWith("APPLICATION=") }
                ?.substringAfter('=')
                ?.split(';')
        assertEquals(if (application == null) 5 else 7, messages.size, visibleEscapes(result.stdout))

        fun encoded(value: String) = Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
        assertTrue(messages[0].contains(encoded("Get-KetraNativeWidget")), visibleEscapes(messages[0]))
        assertTrue(messages[1].contains(encoded("alpha")), visibleEscapes(messages[1]))
        assertTrue(messages[2].contains(encoded("beta")), visibleEscapes(messages[2]))
        assertTrue(messages[3].contains(encoded("'native value'")), visibleEscapes(messages[3]))
        assertTrue(messages[3].contains(encoded("native detail")), visibleEscapes(messages[3]))
        assertTrue(messages[4].contains(";5;Ok;0;0;0;;"), visibleEscapes(messages[4]))
        assertTrue(messages[4].contains(";Command;"), visibleEscapes(messages[4]))
        assertTrue(messages[4].contains(encoded("& '0000'")), visibleEscapes(messages[4]))
        assertTrue(messages[4].contains(encoded("& '00 space'")), visibleEscapes(messages[4]))
        if (application != null) {
            assertTrue(messages[5].contains(";Command;${encoded(application[1])};${encoded(application[0])};"), visibleEscapes(messages[5]))
            assertTrue(messages[6].contains(";Command;${encoded(application[0])};${encoded(application[0])};"), visibleEscapes(messages[6]))
        }
        assertTrue(result.stdout.contains("EXIT=77"), visibleEscapes(result.stdout))
    }

    @Test
    fun `Bash startup applies project toolchain after bashrc replaces environment and prompt hook`(
        @TempDir tempDir: Path,
    ) {
        val bash = installedExecutable("bash", "bash.exe")
        assumeTrue(bash != null, "bash is not installed")
        val home = Files.createDirectory(tempDir.resolve("user home"))
        val jdk = Files.createDirectory(tempDir.resolve("project JDK ${'$'}literal 'quote"))
        val bin = Files.createDirectory(jdk.resolve("bin"))
        Files.writeString(
            home.resolve(".bashrc"),
            """
            export JAVA_HOME=rc-jdk
            export PATH=/usr/bin:/bin
            PROMPT_COMMAND=': user-prompt-hook'
            export USER_RC_RAN=yes
            """.trimIndent(),
        )
        Files.writeString(bin.resolve("java"), "#!/bin/sh\nprintf 'PROJECT_JAVA\\n'\n")
        assertTrue(bin.resolve("java").toFile().setExecutable(true))
        val profile =
            TerminalProfile(
                id = "bash",
                displayName = "Bash",
                command = listOf(bash!!, "-i"),
                environment = mapOf("HOME" to home.toString(), "JAVA_HOME" to "explicit-jdk", "TERM" to "xterm-256color"),
                shellEnvironment = TerminalShellEnvironment(mapOf("JAVA_HOME" to jdk.toString()), bin.toString()),
            )
        val launch =
            TerminalShellIntegrationBootstrap
                .apply(
                    profile,
                    enabled = true,
                    scriptDirectory = tempDir.resolve("bootstrap"),
                ).profile
        val result =
            runProcess(
                launch.command,
                environment = launch.environment,
                standardInput =
                    """
                    printf 'HOME_RESULT=%s\nRC_RESULT=%s\n' "${'$'}JAVA_HOME" "${'$'}USER_RC_RAN"
                    [[ "${'$'}{PATH%%:*}/java" -ef "${'$'}JAVA_HOME/bin/java" ]] && printf 'PROJECT_BIN_FIRST=yes\n'
                    java
                    export -p | command grep _KetraTerm_FORCE_ || :
                    exit
                    """.trimIndent() + "\n",
            )

        assertEquals(0, result.exitCode, visibleEscapes(result.stdout))
        assertTrue(result.stdout.contains("HOME_RESULT=$jdk"), visibleEscapes(result.stdout))
        assertTrue(result.stdout.contains("RC_RESULT=yes"), visibleEscapes(result.stdout))
        assertTrue(result.stdout.lineSequence().any { it.trimEnd('\r') == "PROJECT_BIN_FIRST=yes" }, visibleEscapes(result.stdout))
        assertTrue(result.stdout.contains("PROJECT_JAVA"), visibleEscapes(result.stdout))
        assertTrue(!result.stdout.contains("declare -x _KetraTerm_FORCE_"), visibleEscapes(result.stdout))
        assertTrue(result.stdout.contains("\u001B]133;A\u0007"), visibleEscapes(result.stdout))
    }

    @Test
    fun `Bash login startup sources first login file and reapplies environment once`(
        @TempDir tempDir: Path,
    ) {
        val bash = installedExecutable("bash", "bash.exe")
        assumeTrue(bash != null, "bash is not installed")
        val home = Files.createDirectory(tempDir.resolve("login home"))
        Files.writeString(
            home.resolve(".bash_profile"),
            """
            export JAVA_HOME=login-jdk
            export PATH=/usr/bin:/bin
            export LOGIN_FILE=profile
            """.trimIndent(),
        )
        Files.writeString(home.resolve(".bash_login"), "export LOGIN_FILE=wrong\n")
        val profile =
            TerminalProfile(
                id = "bash",
                displayName = "Bash",
                command = listOf(bash!!, "--login", "-i"),
                environment = mapOf("HOME" to home.toString(), "TERM" to "xterm-256color"),
                shellEnvironment = TerminalShellEnvironment(mapOf("JAVA_HOME" to "project-jdk"), "/project/bin"),
            )
        val launch =
            TerminalShellIntegrationBootstrap
                .apply(
                    profile,
                    enabled = true,
                    scriptDirectory = tempDir.resolve("bootstrap"),
                ).profile
        val result =
            runProcess(
                launch.command,
                environment = launch.environment,
                standardInput =
                    """
                    printf 'LOGIN_RESULT=%s:%s:%s\n' "${'$'}JAVA_HOME" "${'$'}LOGIN_FILE" "${'$'}PATH"
                    export JAVA_HOME=user-change
                    printf 'USER_RESULT=%s\n' "${'$'}JAVA_HOME"
                    exit
                    """.trimIndent() + "\n",
            )

        assertEquals(0, result.exitCode, visibleEscapes(result.stdout))
        assertTrue(result.stdout.contains("LOGIN_RESULT=project-jdk:profile:/project/bin:/usr/bin:/bin"), visibleEscapes(result.stdout))
        assertTrue(result.stdout.contains("USER_RESULT=user-change"), visibleEscapes(result.stdout))
    }

    @Test
    fun `PowerShell startup reapplies toolchain after profile code and clears launch markers`(
        @TempDir tempDir: Path,
    ) {
        val powerShell = installedExecutable("pwsh", "pwsh.exe", "powershell.exe")
        assumeTrue(powerShell != null, "PowerShell is not installed")
        val javaHome = "project JDK ${'$'}literal 'quoted'"
        val bin = tempDir.resolve("project JDK ${'$'}literal 'quoted'/bin").toString()
        val profile =
            TerminalProfile(
                id = "powershell",
                displayName = "PowerShell",
                command = listOf(powerShell!!),
                shellEnvironment = TerminalShellEnvironment(mapOf("JAVA_HOME" to javaHome), bin),
            )
        val launch =
            TerminalShellIntegrationBootstrap
                .apply(
                    profile,
                    enabled = true,
                    scriptDirectory = tempDir.resolve("bootstrap"),
                ).profile
        val bootstrap = String(Base64.getDecoder().decode(launch.command.last()), Charsets.UTF_16LE)
        val startupFile = tempDir.resolve("profile.ps1")
        Files.writeString(startupFile, "${'$'}env:JAVA_HOME = 'profile-jdk'\n${'$'}env:PATH = 'profile-bin'\n")
        val startup =
            """
            . '${startupFile.toString().replace("'", "''")}'
            $bootstrap
            [Console]::WriteLine('HOME_RESULT=' + ${'$'}env:JAVA_HOME)
            [Console]::WriteLine('PATH_RESULT=' + ${'$'}env:PATH)
            [Console]::WriteLine('MARKERS=' + @(Get-ChildItem Env: | Where-Object Name -Like '_KetraTerm_FORCE_*').Count)
            ${'$'}env:JAVA_HOME = 'user-change'
            prompt | Out-Null
            [Console]::WriteLine('USER_RESULT=' + ${'$'}env:JAVA_HOME)
            """.trimIndent()
        val encoded = Base64.getEncoder().encodeToString(startup.toByteArray(Charsets.UTF_16LE))
        val result = runProcess(listOf(powerShell, "-NoProfile", "-EncodedCommand", encoded), environment = launch.environment)

        assertEquals(0, result.exitCode, visibleEscapes(result.stdout))
        assertTrue(result.stdout.contains("HOME_RESULT=$javaHome"), visibleEscapes(result.stdout))
        assertTrue(result.stdout.contains("PATH_RESULT=$bin${java.io.File.pathSeparator}profile-bin"), visibleEscapes(result.stdout))
        assertTrue(result.stdout.contains("MARKERS=0"), visibleEscapes(result.stdout))
        assertTrue(result.stdout.contains("USER_RESULT=user-change"), visibleEscapes(result.stdout))
    }

    @Test
    fun `zsh startup reapplies toolchain after login file and follows user ZDOTDIR changes`(
        @TempDir tempDir: Path,
    ) {
        val zsh = installedExecutable("zsh", "zsh.exe")
        assumeTrue(zsh != null, "zsh is not installed")
        val original = Files.createDirectory(tempDir.resolve("original"))
        val relocated = Files.createDirectory(tempDir.resolve("relocated"))
        Files.writeString(original.resolve(".zshenv"), "export ZDOTDIR=${shellSingleQuote(relocated.toString())}\n")
        Files.writeString(relocated.resolve(".zshrc"), "export JAVA_HOME=rc-jdk\n")
        Files.writeString(
            relocated.resolve(".zlogin"),
            """
            export JAVA_HOME=login-jdk
            export PATH=/usr/bin:/bin
            export LOGIN_FILE=relocated
            """.trimIndent(),
        )
        val profile =
            TerminalProfile(
                id = "zsh",
                displayName = "Zsh",
                command = listOf(zsh!!, "-l", "-i"),
                environment = mapOf("ZDOTDIR" to original.toString(), "TERM" to "xterm-256color"),
                shellEnvironment = TerminalShellEnvironment(mapOf("JAVA_HOME" to "project-jdk"), "/project/bin"),
            )
        val launch =
            TerminalShellIntegrationBootstrap
                .apply(
                    profile,
                    enabled = true,
                    scriptDirectory = tempDir.resolve("bootstrap"),
                ).profile
        val assertions =
            """
            printf 'LOGIN_RESULT=%s:%s:%s\n' "${'$'}JAVA_HOME" "${'$'}LOGIN_FILE" "${'$'}PATH"
            print -l -- ${'$'}{parameters[(I)_KetraTerm_FORCE_*]}
            exit 0
            """.trimIndent()
        val result =
            runProcess(
                launch.command + listOf("-c", assertions),
                environment = launch.environment,
            )
        assertEquals(0, result.exitCode, visibleEscapes(result.stdout))
        assertTrue(result.stdout.contains("LOGIN_RESULT=project-jdk:relocated:/project/bin:/usr/bin:/bin"), visibleEscapes(result.stdout))
        assertTrue(!result.stdout.lineSequence().any { it.startsWith("_KetraTerm_FORCE_SET_") }, visibleEscapes(result.stdout))
    }

    @Test
    fun `fish startup reapplies toolchain after user config and consumes markers`(
        @TempDir tempDir: Path,
    ) {
        val fish = installedExecutable("fish", "fish.exe")
        assumeTrue(fish != null, "fish is not installed")
        val config = Files.createDirectories(tempDir.resolve("config/fish"))
        Files.writeString(
            config.resolve("config.fish"),
            """
            set -gx JAVA_HOME config-jdk
            set -gx PATH /usr/bin /bin
            set -gx USER_CONFIG_RAN yes
            """.trimIndent(),
        )
        val profile =
            TerminalProfile(
                id = "fish",
                displayName = "Fish",
                command = listOf(fish!!, "-i"),
                environment = mapOf("XDG_CONFIG_HOME" to config.parent.toString(), "TERM" to "xterm-256color"),
                shellEnvironment = TerminalShellEnvironment(mapOf("JAVA_HOME" to "project-jdk"), "/project/bin"),
            )
        val launch =
            TerminalShellIntegrationBootstrap
                .apply(
                    profile,
                    enabled = true,
                    scriptDirectory = tempDir.resolve("bootstrap"),
                ).profile
        val assertions =
            """
            printf 'RESULT=%s:%s:%s\n' "${'$'}JAVA_HOME" "${'$'}USER_CONFIG_RAN" "${'$'}PATH[1]"
            set --names | string match '_KetraTerm_FORCE_*'
            exit 0
            """.trimIndent()
        val result =
            runProcess(
                launch.command + listOf("-c", assertions),
                environment = launch.environment,
            )
        assertEquals(0, result.exitCode, visibleEscapes(result.stdout))
        assertTrue(result.stdout.contains("RESULT=project-jdk:yes:/project/bin"), visibleEscapes(result.stdout))
        assertTrue(!result.stdout.lineSequence().any { it.startsWith("_KetraTerm_FORCE_SET_") }, visibleEscapes(result.stdout))
    }

    @Test
    fun `generated PowerShell bootstrap emits encoded current directory before prompt when PowerShell is installed`(
        @TempDir tempDir: Path,
    ) {
        val powerShell = installedExecutable("pwsh", "pwsh.exe", "powershell.exe")
        assumeTrue(powerShell != null, "PowerShell is not installed")

        val profile =
            TerminalProfile(
                id = "powershell",
                displayName = "PowerShell",
                command = listOf(powerShell!!),
            )
        val integrated = TerminalShellIntegrationBootstrap.apply(profile, enabled = true).profile
        val bootstrap = String(Base64.getDecoder().decode(integrated.command.last()), Charsets.UTF_16LE)
        val invocation = Base64.getEncoder().encodeToString("$bootstrap\nprompt | Out-Host".toByteArray(Charsets.UTF_16LE))
        val workingDirectory = Files.createDirectory(tempDir.resolve("space % directory"))
        val result =
            runProcess(
                listOf(powerShell, "-NoProfile", "-EncodedCommand", invocation),
                workingDirectory = workingDirectory,
            )

        assertEquals(0, result.exitCode)
        assertMarkerOrder(result.stdout, "A")
        assertCurrentDirectoryBeforePrompts(result.stdout, expectedPromptCount = 1)
        assertTrue(result.stdout.contains("space%20%25%20directory"), visibleEscapes(result.stdout))
    }

    @Test
    fun `process runner drains PowerShell output larger than an operating system pipe buffer`() {
        val powerShell = installedExecutable("pwsh", "pwsh.exe", "powershell.exe")
        assumeTrue(powerShell != null, "PowerShell is not installed")
        val script = "[Console]::Out.Write('x' * $OUTPUT_STRESS_LENGTH)"
        val invocation = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))

        val result = runProcess(listOf(powerShell!!, "-NoProfile", "-EncodedCommand", invocation))

        assertEquals(0, result.exitCode)
        assertEquals(OUTPUT_STRESS_LENGTH, result.stdout.count { it == 'x' })
    }

    @Test
    fun `generated Bash bootstrap emits encoded current directory and lifecycle markers when Bash is installed`(
        @TempDir tempDir: Path,
    ) {
        val bash = installedExecutable("bash", "bash.exe")
        assumeTrue(bash != null, "bash is not installed")

        val profile =
            TerminalProfile(
                id = "bash",
                displayName = "Bash",
                command = listOf(bash!!),
            )
        val bootstrap =
            TerminalShellIntegrationBootstrap
                .apply(profile, enabled = true)
                .profile.environment
                .getValue("PROMPT_COMMAND")
        val workingDirectory = Files.createDirectory(tempDir.resolve("space % directory"))
        val result =
            runProcess(
                listOf(
                    bash,
                    "--noprofile",
                    "--norc",
                ),
                standardInput = "$bootstrap; __ketraterm_preexec; false; __ketraterm_prompt_command\n",
                workingDirectory = workingDirectory,
            )

        assertEquals(1, result.exitCode)
        assertMarkerOrder(result.stdout, "A", "C", "D;1", "A")
        assertCurrentDirectoryBeforePrompts(result.stdout, expectedPromptCount = 2)
        assertTrue(result.stdout.contains("space%20%25%20directory"), visibleEscapes(result.stdout))
        val directoryUri = assertNotNull(Regex("\u001b]7;([^\u0007]*)\u0007").find(result.stdout)).groupValues[1]
        assertEquals(
            workingDirectory.toAbsolutePath().normalize(),
            Path.of(URI("file", null, URI(directoryUri).path, null)).toAbsolutePath().normalize(),
            "The shell directory must resolve to the native host directory",
        )
    }

    @Test
    fun `generated Bash bootstrap continues markers after clear when Bash is installed`() {
        val bash = installedExecutable("bash", "bash.exe")
        assumeTrue(bash != null, "bash is not installed")

        val profile =
            TerminalProfile(
                id = "bash",
                displayName = "Bash",
                command = listOf(bash!!),
                environment = mapOf("TERM" to "xterm-256color"),
            )
        val integrated = TerminalShellIntegrationBootstrap.apply(profile, enabled = true).profile
        val bootstrap = integrated.environment.getValue("PROMPT_COMMAND")
        val result =
            runProcess(
                listOf(
                    bash,
                    "--noprofile",
                    "--norc",
                ),
                environment = integrated.environment,
                standardInput =
                    "$bootstrap; __ketraterm_preexec; { clear >/dev/null 2>&1 || printf '\\033[H\\033[2J\\033[3J'; }; true; __ketraterm_prompt_command; __ketraterm_preexec; false; __ketraterm_prompt_command\n",
            )

        assertEquals(1, result.exitCode)
        assertMarkerOrder(result.stdout, "A", "C", "D;0", "A", "C", "D;1", "A")
        assertCurrentDirectoryBeforePrompts(result.stdout, expectedPromptCount = 3)
    }

    @Test
    fun `generated zsh bootstrap emits prompt command and lifecycle markers when zsh is installed`(
        @TempDir tempDir: Path,
    ) {
        val zsh = installedExecutable("zsh", "zsh.exe")
        assumeTrue(zsh != null, "zsh is not installed")

        val originalZdotdir = tempDir.resolve("original")
        val profile =
            TerminalProfile(
                id = "zsh",
                displayName = "Zsh",
                command = listOf(zsh!!),
                environment = mapOf("KetraTerm_ORIGINAL_ZDOTDIR" to originalZdotdir.toString()),
            )
        val integrated = TerminalShellIntegrationBootstrap.apply(profile, enabled = true, scriptDirectory = tempDir).profile
        val zshrc = Path.of(integrated.environment.getValue("ZDOTDIR")).resolve(".zshrc")
        val result =
            runProcess(
                listOf(
                    zsh,
                    "-f",
                    "-c",
                    "source ${shellSingleQuote(
                        zshrc.toString(),
                    )}; __ketraterm_zsh_precmd; __ketraterm_zsh_preexec false; false; __ketraterm_zsh_precmd",
                ),
                environment = integrated.environment,
            )

        assertEquals(1, result.exitCode)
        assertMarkerOrder(result.stdout, "A", "C", "D;1", "A")
        assertCurrentDirectoryBeforePrompts(result.stdout, expectedPromptCount = 2)
    }

    @Test
    fun `generated fish bootstrap emits prompt command and lifecycle markers when fish is installed`() {
        val fish = installedExecutable("fish", "fish.exe")
        assumeTrue(fish != null, "fish is not installed")

        val profile =
            TerminalProfile(
                id = "fish",
                displayName = "Fish",
                command = listOf(fish!!),
            )
        val integrated = TerminalShellIntegrationBootstrap.apply(profile, enabled = true).profile
        val result =
            runProcess(
                integrated.command +
                    listOf(
                        "--no-config",
                        "-c",
                        "emit fish_prompt; emit fish_preexec false; false; emit fish_postexec false; emit fish_prompt",
                    ),
            )

        assertEquals(0, result.exitCode)
        assertMarkerOrder(result.stdout, "A", "C", "D;1", "A")
        assertCurrentDirectoryBeforePrompts(result.stdout, expectedPromptCount = 2)
    }

    private fun commandInventories(output: String): List<List<List<String>>> {
        val snapshots = mutableListOf<List<List<String>>>()
        var entries = mutableListOf<List<String>>()
        var chunks = 0
        var bytes = 0
        for (message in Regex("\u001b]633;KetraTerm(?:Shell)?Commands;3;([^\u0007]*)\u0007").findAll(output)) {
            val fields = message.groupValues[1].split(';')
            when (fields[0]) {
                "Begin" -> {
                    entries = mutableListOf()
                    chunks = 0
                    bytes = 0
                }
                "Chunk" -> {
                    assertEquals(chunks++, fields[3].toInt())
                    val decoded = Base64.getDecoder().decode(fields[4])
                    assertTrue(decoded.size <= 49_152)
                    bytes += decoded.size
                    val body = decoded.toString(Charsets.UTF_8)
                    assertTrue(body.endsWith('\u0000'))
                    entries +=
                        body.dropLast(1).split('\u0000').chunked(4).map { record ->
                            assertEquals(4, record.size)
                            listOf(record[0], record[1].ifEmpty { record[0] }, if (record[2] == "A") "Alias" else "Command", record[3])
                        }
                }
                "End" -> {
                    assertEquals(listOf(chunks, entries.size, bytes), fields.drop(3).map(String::toInt))
                    snapshots += entries.toList()
                }
                "Fail" -> error("Inventory failed: $fields")
            }
        }
        return snapshots
    }

    private fun assertMarkerOrder(
        output: String,
        vararg markers: String,
    ) {
        var index = 0
        for (marker in markers) {
            val encoded = "\u001B]133;$marker\u0007"
            val nextIndex = output.indexOf(encoded, startIndex = index)
            assertTrue(nextIndex >= 0, "missing OSC 133 marker $marker in ${visibleEscapes(output)}")
            index = nextIndex + encoded.length
        }
    }

    private fun assertCurrentDirectoryBeforePrompts(
        output: String,
        expectedPromptCount: Int,
    ) {
        val osc7Prefix = "\u001B]7;"
        val promptStart = "\u001B]133;A\u0007"
        var searchFrom = 0
        repeat(expectedPromptCount) {
            val promptIndex = output.indexOf(promptStart, startIndex = searchFrom)
            assertTrue(promptIndex >= 0, "missing OSC 133 prompt marker in ${visibleEscapes(output)}")
            val osc7Index = output.lastIndexOf(osc7Prefix, startIndex = promptIndex)
            assertTrue(osc7Index >= searchFrom, "missing OSC 7 before prompt in ${visibleEscapes(output)}")
            val uriEnd = output.indexOf('\u0007', startIndex = osc7Index + osc7Prefix.length)
            assertTrue(uriEnd in (osc7Index + osc7Prefix.length)..<promptIndex)
            val uri = URI(output.substring(osc7Index + osc7Prefix.length, uriEnd))
            assertEquals("file", uri.scheme)
            assertTrue(!uri.rawPath.isNullOrEmpty(), "OSC 7 URI must contain a path: $uri")
            searchFrom = promptIndex + promptStart.length
        }
    }

    private fun runProcess(
        command: List<String>,
        environment: Map<String, String> = emptyMap(),
        standardInput: String? = null,
        workingDirectory: Path? = null,
    ): ProcessResult {
        val process =
            ProcessBuilder(command)
                .redirectErrorStream(true)
                .also { if (workingDirectory != null) it.directory(workingDirectory.toFile()) }
                .also { it.environment().putAll(environment) }
                .start()
        val outputTask = FutureTask { process.inputStream.use { it.readAllBytes() } }
        Thread.ofVirtual().name("shell-integration-test-output").start(outputTask)
        if (standardInput != null) {
            process.outputStream.use { input ->
                input.write(standardInput.toByteArray(StandardCharsets.UTF_8))
            }
        } else {
            process.outputStream.close()
        }
        val completed = process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!completed) {
            process.destroyForcibly()
            if (!process.waitFor(PROCESS_TERMINATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                outputTask.cancel(true)
                error("process did not terminate after forced destruction: ${command.joinToString(" ")}")
            }
            val stdout = completedOutput(outputTask, command)
            error(
                "process timed out after $PROCESS_TIMEOUT_SECONDS seconds: ${command.joinToString(" ")}\n" +
                    visibleEscapes(stdout),
            )
        }
        val stdout = completedOutput(outputTask, command)
        return ProcessResult(process.exitValue(), stdout)
    }

    private fun completedOutput(
        outputTask: FutureTask<ByteArray>,
        command: List<String>,
    ): String =
        try {
            String(outputTask.get(PROCESS_TERMINATION_TIMEOUT_SECONDS, TimeUnit.SECONDS), StandardCharsets.UTF_8)
        } catch (_: TimeoutException) {
            outputTask.cancel(true)
            error("process output stream did not close: ${command.joinToString(" ")}")
        }

    private fun assumeBashCommandInventory(bash: String) {
        val probe = runProcess(listOf(bash, "--noprofile", "--norc", "-c", "compgen -V commands -A builtin"))
        assumeTrue(probe.exitCode == 0, "Bash 5.3+ command inventory is not supported by this installed shell")
    }

    private fun installedExecutable(vararg names: String): String? {
        val locator = if (isWindows()) listOf("where.exe") else listOf("sh", "-c")
        for (name in names) {
            val command =
                if (isWindows()) {
                    locator + name
                } else {
                    locator + "command -v ${shellSingleQuote(name)}"
                }
            val output = locate(command) ?: continue
            val firstLine = output.lineSequence().firstOrNull { isUsableShellPath(it) } ?: continue
            return firstLine.trim()
        }
        if (isWindows() && names.any { it == "bash" || it == "bash.exe" }) {
            val gitBash = Path.of(System.getenv("ProgramFiles") ?: "C:\\Program Files", "Git", "bin", "bash.exe")
            if (Files.isRegularFile(gitBash)) return gitBash.toString()
        }
        return null
    }

    private fun locate(command: List<String>): String? =
        try {
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                null
            } else if (process.exitValue() == 0) {
                String(process.inputStream.readAllBytes(), StandardCharsets.UTF_8)
            } else {
                null
            }
        } catch (_: IOException) {
            null
        }

    private fun shellSingleQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun isUsableShellPath(path: String): Boolean {
        if (path.isBlank()) return false
        val normalized = path.trim().replace('\\', '/').lowercase()
        return normalized != "c:/windows/system32/bash.exe" &&
            !normalized.contains("/microsoft/windowsapps/")
    }

    private fun visibleEscapes(value: String): String =
        value
            .replace("\u001B", "<ESC>")
            .replace("\u0007", "<BEL>")

    private fun isWindows(): Boolean = System.getProperty("os.name").lowercase().contains("windows")

    private companion object {
        private const val OUTPUT_STRESS_LENGTH = 256 * 1024
        private const val PROCESS_TERMINATION_TIMEOUT_SECONDS = 5L
        private const val PROCESS_TIMEOUT_SECONDS = 30L
    }

    private data class ProcessResult(
        val exitCode: Int,
        val stdout: String,
    )
}

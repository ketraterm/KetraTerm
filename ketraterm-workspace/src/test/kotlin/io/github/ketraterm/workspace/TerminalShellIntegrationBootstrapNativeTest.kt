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
import io.github.ketraterm.session.TerminalCommandEditContext
import io.github.ketraterm.session.TerminalInputAdmission
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.session.TerminalShellCompletionKind
import io.github.ketraterm.session.TerminalStartupCommand
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.future.await
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.IOException
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.WatchService
import java.util.*
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.*
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
            val script =
                "$hook\n__ketraterm_collect_path_commands() { printf '$output'; }\n__ketraterm_publish_commands test 1 Path\n"
            val result = runProcess(listOf(bash, "--noprofile", "--norc"), standardInput = script)
            assertTrue(result.stdout.contains("KetraTermCommands;3;Fail;test;1;$reason"))
            assertTrue(!result.stdout.contains("KetraTermCommands;3;End;"))
        }
    }

    private suspend fun TerminalSession.awaitLine(
        line: String,
        stage: String = "input",
        requireShellCompletion: Boolean = false,
        cursorOffset: Int = line.length,
    ): TerminalCommandEditContext {
        try {
            var captured: TerminalCommandEditContext? = null
            withTimeout(15.seconds) {
                renderGeneration.first {
                    val edit = captureCommandEdit() ?: return@first false
                    if (requireShellCompletion && (!shellCompletionAvailable || edit.prefixResetKeys.isEmpty())) {
                        return@first false
                    }
                    val snapshot = edit.commandLine
                    if (snapshot.commandText == line && snapshot.cursorOffset == cursorOffset) {
                        captured = edit
                        true
                    } else {
                        false
                    }
                }
            }
            return checkNotNull(captured)
        } catch (failure: TimeoutCancellationException) {
            val screen =
                buildString {
                    readRenderFrame { frame ->
                        appendLine("Cursor: ${frame.cursor.column}, ${frame.cursor.row}")
                        val codeWords = IntArray(frame.columns)
                        val flags = IntArray(frame.columns)
                        for (row in 0 until frame.rows) {
                            frame.copyTextLine(row, codeWords, flags = flags)
                            val text = buildString { codeWords.forEach { append(if (it in 32..126) it.toChar() else ' ') } }.trimEnd()
                            if (text.isNotEmpty()) appendLine("$row: $text")
                        }
                    }
                }
            throw AssertionError(
                "Expected editable line '$line' (cursor=$cursorOffset) after $stage (completionAvailable=$shellCompletionAvailable), observed ${activeShellCommandLine()}, session ${state.value}\n$screen",
                failure,
            )
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
        assertPathDiscoveryIdentity(listOf(zsh!!, "-d", "-i"), "__ketraterm_zsh_completion")
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

    @ParameterizedTest(name = "Zsh inventory worker completes before close: {0}")
    @ValueSource(booleans = [false, true])
    fun `zsh background inventory progresses without terminal noise and stops with its session`(
        completeInventory: Boolean,
        @TempDir tempDir: Path,
    ): Unit =
        runBlocking(Dispatchers.IO) {
            val zsh = installedExecutable("zsh")
            assumeTrue(zsh != null && !isWindows(), "Native POSIX zsh is not installed")
            val mkfifo = assertNotNull(installedExecutable("mkfifo"))
            val bin = Files.createDirectory(tempDir.resolve("bin"))
            val gate = tempDir.resolve("finder.gate")
            assertEquals(0, runProcess(listOf(mkfifo, gate.toString())).exitCode)
            for (name in listOf("base64", "mv")) {
                Files.createSymbolicLink(bin.resolve(name), Path.of(assertNotNull(installedExecutable(name))))
            }
            Files.writeString(
                bin.resolve("find"),
                """
                #!/bin/sh
                [ "${'$'}5" != 0 ] || exit 0
                printf '%s\n' "${'$'}${'$'}" > "${'$'}KETRA_TEST_ROOT/finder.pending"
                "${'$'}KETRA_TEST_ROOT/bin/mv" "${'$'}KETRA_TEST_ROOT/finder.pending" "${'$'}KETRA_TEST_ROOT/finder.ready"
                read release < "${'$'}KETRA_TEST_ROOT/finder.gate"
                printf './ketra_path_marker\0'
                """.trimIndent() + "\n",
            )
            assertTrue(bin.resolve("find").toFile().setExecutable(true))
            Files.writeString(bin.resolve("ketra_path_marker"), "#!/bin/sh\n")
            assertTrue(bin.resolve("ketra_path_marker").toFile().setExecutable(true))
            Files.writeString(
                tempDir.resolve(".zshrc"),
                "setopt MONITOR\nprintf '%s' ${'$'}${'$'} > \"${'$'}KETRA_TEST_ROOT/shell.pid\"\nPROMPT='ketra> '\n",
            )
            tempDir.fileSystem.newWatchService().use { watcher ->
                tempDir.register(watcher, ENTRY_CREATE)
                TerminalWorkspace().use { workspace ->
                    val session =
                        workspace
                            .openTab(
                                TerminalProfile(
                                    "zsh",
                                    "Zsh",
                                    listOf(zsh!!, "-d", "-i"),
                                    workingDirectory = tempDir,
                                    environment =
                                        mapOf(
                                            "ZDOTDIR" to tempDir.toString(),
                                            "KETRA_TEST_ROOT" to tempDir.toString(),
                                            "PATH" to bin.toString(),
                                        ),
                                ),
                                TerminalWorkspaceOpenOptions(),
                            ).session
                    val workers = mutableListOf<ProcessHandle>()
                    try {
                        awaitCreatedFile(tempDir.resolve("finder.ready"), watcher)
                        val shellPid = Files.readString(tempDir.resolve("shell.pid")).toLong()
                        val finder = ProcessHandle.of(Files.readString(tempDir.resolve("finder.ready")).trim().toLong()).orElseThrow()
                        val lineage = mutableListOf<ProcessHandle>()
                        for (process in generateSequence(finder) { it.parent().orElse(null) }) {
                            lineage += process
                            if (process.pid() == shellPid) break
                        }
                        assertEquals(shellPid, lineage.last().pid(), "Only this session's descendants belong to the fixture")
                        workers += lineage.dropLast(1)
                        assertTrue(workers.size > 1, "The finder must run in the background inventory process tree")
                        session.awaitLine("")
                        if (completeInventory) {
                            session.submitInput(TerminalPasteEvent("echo inventory-readyX"))
                            session.awaitLine("echo inventory-readyX")
                            assertEquals(
                                0,
                                runProcess(
                                    listOf("/bin/sh", "-c", "printf 'release\\n' > ${shellSingleQuote(gate.toString())}"),
                                ).exitCode,
                            )
                            withTimeout(15.seconds) {
                                session.shellCommandInventory.first { inventory ->
                                    inventory?.commands?.any { it.displayText == "ketra_path_marker" } == true
                                }
                                workers.forEach { it.onExit().await() }
                            }
                            session.submitInput(TerminalKeyEvent.key(TerminalKey.BACKSPACE))
                            session.awaitLine("echo inventory-ready")
                            session.submitInput(TerminalKeyEvent.key(TerminalKey.ENTER))
                            withTimeout(15.seconds) {
                                session.shellIntegrationState.revision.first {
                                    val state = session.shellIntegrationState
                                    val command = state.commandMetadata(state.latestCommandRecordId())
                                    command?.commandText == "echo inventory-ready" && command.exitCode == 0
                                }
                            }
                            session.awaitLine("")
                        }
                        session.readRenderFrame { frame ->
                            val codeWords = IntArray(frame.columns)
                            val flags = IntArray(frame.columns)
                            val lines =
                                (0 until frame.rows).mapNotNull { row ->
                                    frame.copyTextLine(row, codeWords, flags = flags)
                                    buildString { codeWords.forEach { append(if (it in 32..126) it.toChar() else ' ') } }
                                        .trimEnd()
                                        .takeIf(String::isNotEmpty)
                                }
                            val expected =
                                if (completeInventory) {
                                    listOf(
                                        "ketra> echo inventory-ready",
                                        "inventory-ready",
                                        "ketra>",
                                    )
                                } else {
                                    listOf("ketra>")
                                }
                            assertEquals(expected, lines, "Inventory work must preserve editing and never print job-control messages")
                        }
                        session.close()
                        withTimeout(15.seconds) { workers.forEach { it.onExit().await() } }
                    } finally {
                        if (Files.exists(tempDir.resolve("shell.pid"))) {
                            ProcessHandle.of(Files.readString(tempDir.resolve("shell.pid")).toLong()).ifPresent { shell ->
                                workers += shell.descendants().toList()
                            }
                        }
                        workers.forEach { if (it.isAlive) it.destroyForcibly() }
                    }
                }
            }
        }

    private suspend fun awaitCreatedFile(
        path: Path,
        watcher: WatchService,
    ): Unit =
        withContext(Dispatchers.IO) {
            withTimeout(15.seconds) {
                while (!Files.exists(path)) {
                    runInterruptible(Dispatchers.IO) {
                        val key = watcher.take()
                        key.pollEvents()
                        check(key.reset()) { "Native fixture directory is no longer watchable" }
                    }
                }
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
    fun `Bash prompt inventory refreshes when builtins are disabled and reenabled`(): Unit =
        runBlocking {
            val bash = installedExecutable("bash", "bash.exe")
            assumeTrue(bash != null, "Bash is not installed")
            assumeBashCommandInventory(bash!!)
            TerminalWorkspace().use { workspace ->
                val session =
                    workspace
                        .openTab(
                            TerminalProfile("bash", "Bash", listOf(bash, "--noprofile", "--norc", "-i")),
                            TerminalWorkspaceOpenOptions(),
                        ).session
                session.awaitLine("")
                withTimeout(15.seconds) {
                    session.shellCommandInventory.first { it?.commands?.any { command -> command.displayText == "times" } == true }
                }
                for ((action, marker) in listOf(
                    "-n times" to "ketra_builtin_disabled",
                    "times" to "ketra_builtin_reenabled",
                )) {
                    val script =
                        "before=\"${'$'}__ketraterm_completion_epoch\"; builtin enable $action; " +
                            "__ketraterm_completion_ready; " +
                            "[[ ${'$'}before != ${'$'}__ketraterm_completion_epoch ]] && $marker() { :; }"
                    session.submitInput(listOf(TerminalPasteEvent(script), TerminalKeyEvent.key(TerminalKey.ENTER)))
                    withTimeout(15.seconds) {
                        session.shellCommandInventory.first { it?.commands?.any { command -> command.displayText == marker } == true }
                        session.activeShellCommandLineRevision.first { session.activeShellCommandLine()?.commandText == "" }
                    }
                }
            }
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
            withContext(Dispatchers.IO) {
                Files.writeString(
                    inputrc,
                    "set enable-active-region on\nset enable-bracketed-paste on\nset editing-mode emacs\n",
                )
            }
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
                            // Live cells can run ahead of this render notification. Await the
                            // published text before undoing it, or that state may be conflated away.
                            session.readPublishedFrame { frame ->
                                val start = frame.cursorColumn - line.length
                                frame.cursorRow == command.cursorRow &&
                                    frame.cursorColumn == command.cursorColumn &&
                                    start >= 0 &&
                                    line.indices.all { index ->
                                        val cell = frame.cursorRow * frame.columns + start + index
                                        frame.codeWords[cell] == line[index].code &&
                                            TerminalRenderAttrs.isInverse(frame.attrWords[cell]) == highlighted
                                    }
                            } == true
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
                session.awaitLine("printf", "undoing the inserted suffix")
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

    @ParameterizedTest(name = "PowerShell Unicode prefix reset [{0}]")
    @ValueSource(strings = ["powershell.exe", "pwsh"])
    fun `PowerShell prefix reset preserves the suffix across Unicode editor variants`(
        executableName: String,
        @TempDir directory: Path,
    ): Unit =
        runBlocking {
            val executable = installedExecutable(executableName)
            assumeTrue(executable != null, "$executableName is not installed")
            val helper = directory.resolve("prefix-reset-test.ps1")
            withContext(Dispatchers.IO) {
                Files.writeString(helper, powerShellEditorTestScript("powershell-prefix-reset-test.ps1"))
            }
            val snapshots = Channel<String>(Channel.UNLIMITED)
            TerminalWorkspace(powerShellEditorListener(snapshots)).use { workspace ->
                val session =
                    workspace
                        .openTab(
                            TerminalProfile(
                                "powershell",
                                "PowerShell",
                                listOf(executable!!, "-NoLogo", "-NoProfile"),
                                workingDirectory = directory,
                            ),
                            TerminalWorkspaceOpenOptions(),
                        ).session
                session.awaitLine("")
                assertEquals(
                    TerminalInputAdmission.ACCEPTED,
                    session.submitInput(
                        listOf(
                            TerminalPasteEvent(". ([ScriptBlock]::Create([IO.File]::ReadAllText('prefix-reset-test.ps1')))"),
                            TerminalKeyEvent.key(TerminalKey.ENTER),
                        ),
                    ),
                )
                assertEquals("ketra-ready", withTimeout(15.seconds) { snapshots.receive() })
                session.awaitLine("", "after prefix reset setup", requireShellCompletion = true)

                val suffix = " tail \uD83D\uDE80"
                for (prefix in listOf("echo e\u0301", "echo \uD83D\uDE80", "echo \uD83D\uDC69\u200D\uD83D\uDCBB", "")) {
                    assertEquals(
                        TerminalInputAdmission.ACCEPTED,
                        session.submitInput(listOf(TerminalKeyEvent.key(TerminalKey.F12), TerminalKeyEvent.codepoint('s'.code))),
                    )
                    snapshots.assertPowerShellBuffer(prefix + suffix, prefix.length, exitCode = 77)
                    assertEquals(
                        TerminalInputAdmission.ACCEPTED,
                        session.submitInput(
                            listOf(
                                TerminalKeyEvent.key(TerminalKey.F12),
                                TerminalKeyEvent.codepoint('r'.code),
                                TerminalKeyEvent.key(TerminalKey.F12),
                                TerminalKeyEvent.codepoint('t'.code),
                            ),
                        ),
                    )
                    snapshots.assertPowerShellBuffer(suffix, 0, exitCode = 77)
                    val replacement = "echo accepted"
                    assertEquals(
                        TerminalInputAdmission.ACCEPTED,
                        session.submitInput(
                            listOf(
                                TerminalTextReplacementEvent(0, 0, replacement),
                                TerminalKeyEvent.key(TerminalKey.F12),
                                TerminalKeyEvent.codepoint('t'.code),
                            ),
                        ),
                    )
                    snapshots.assertPowerShellBuffer(replacement + suffix, replacement.length, exitCode = 77)
                }
            }
            snapshots.close()
        }

    @ParameterizedTest(name = "PowerShell inline prediction [{0}]")
    @ValueSource(strings = ["powershell.exe", "pwsh"])
    fun `PowerShell completion distinguishes inline predictions from editable suffixes and spaces`(
        executableName: String,
        @TempDir directory: Path,
    ): Unit =
        runBlocking {
            val executable = installedExecutable(executableName)
            assumeTrue(executable != null, "$executableName is not installed")
            withContext(Dispatchers.IO) {
                Files.writeString(
                    directory.resolve("prediction-test.ps1"),
                    powerShellEditorTestScript("powershell-prediction-test.ps1"),
                )
            }
            val snapshots = Channel<String>(Channel.UNLIMITED)
            try {
                TerminalWorkspace(powerShellEditorListener(snapshots)).use { workspace ->
                    val session =
                        workspace
                            .openTab(
                                TerminalProfile(
                                    "powershell",
                                    "PowerShell",
                                    listOf(requireNotNull(executable), "-NoLogo", "-NoProfile"),
                                    workingDirectory = directory,
                                ),
                                TerminalWorkspaceOpenOptions().copy { it.columns = 240 },
                            ).session
                    session.awaitLine("")
                    assertEquals(
                        TerminalInputAdmission.ACCEPTED,
                        session.submitInput(
                            listOf(
                                TerminalPasteEvent(". ([ScriptBlock]::Create([IO.File]::ReadAllText('prediction-test.ps1')))"),
                                TerminalKeyEvent.key(TerminalKey.ENTER),
                            ),
                        ),
                    )
                    val readiness = withTimeout(15.seconds) { snapshots.receive() }
                    assumeTrue(readiness != "ketra-prediction-unavailable", "$executableName PSReadLine does not support predictions")
                    assertEquals("ketra-prediction-ready", readiness)
                    session.awaitLine("", "after prediction setup", requireShellCompletion = true)

                    val prefix = "Get-Chi"
                    assertEquals(
                        TerminalInputAdmission.ACCEPTED,
                        session.submitInput(prefix.map { TerminalKeyEvent.codepoint(it.code) }),
                    )
                    withTimeout(15.seconds) {
                        session.renderGeneration.first {
                            var predicted = false
                            session.readRenderFrame { frame ->
                                val codeWords = IntArray(frame.columns)
                                frame.copyTextLine(frame.cursor.row, codeWords, flags = IntArray(frame.columns))
                                val suffix =
                                    buildString {
                                        for (column in frame.cursor.column until frame.columns) append(codeWords[column].toChar())
                                    }
                                predicted = suffix.startsWith("ldItem -Name ketra-native-prediction")
                            }
                            predicted
                        }
                    }
                    session.awaitLine(prefix, "painted inline prediction")
                    assertTrue(assertNotNull(session.activeShellCommandLine()).requiresShellConfirmation)
                    val edit = assertNotNull(session.captureCommandEdit())
                    val queryStarted =
                        kotlin.time.TimeSource.Monotonic
                            .markNow()
                    val response = session.requestShellCompletions(prefix, prefix.length)
                    val result =
                        assertNotNull(
                            response,
                            "Native confirmation failed after ${queryStarted.elapsedNow()}; current ${session.activeShellCommandLine()}",
                        )
                    assertEquals(prefix, result.commandLine)
                    assertEquals(prefix.length, result.cursorOffset)
                    val candidate = result.candidates.single { it.replacementText == "Get-ChildItem" }
                    assertEquals(
                        TerminalInputAdmission.ACCEPTED,
                        session.submitInput(
                            edit,
                            listOf(
                                TerminalKeyEvent.key(TerminalKey.F12),
                                TerminalKeyEvent.codepoint('r'.code),
                                TerminalTextReplacementEvent(0, 0, candidate.replacementText),
                                TerminalKeyEvent.key(TerminalKey.F12),
                                TerminalKeyEvent.codepoint('t'.code),
                            ),
                        ),
                    )
                    snapshots.assertPowerShellBuffer("Get-ChildItem", "Get-ChildItem".length)

                    assertEquals(
                        TerminalInputAdmission.ACCEPTED,
                        session.submitInput(listOf(TerminalKeyEvent.key(TerminalKey.F12), TerminalKeyEvent.codepoint('s'.code))),
                    )
                    snapshots.assertPowerShellBuffer("Get-Chi tail", prefix.length)
                    session.awaitLine(prefix, "genuine editable suffix")
                    assertTrue(assertNotNull(session.activeShellCommandLine()).requiresShellConfirmation)
                    assertNull(
                        session.requestShellCompletions(prefix, prefix.length),
                        "A real suffix must not be mistaken for a prediction",
                    )

                    assertEquals(
                        TerminalInputAdmission.ACCEPTED,
                        session.submitInput(listOf(TerminalKeyEvent.key(TerminalKey.F12), TerminalKeyEvent.codepoint('w'.code))),
                    )
                    val spaced = "$prefix  "
                    snapshots.assertPowerShellBuffer(spaced, spaced.length)
                    session.awaitLine(spaced, "genuine trailing spaces")
                    val spacedResult = assertNotNull(session.requestShellCompletions(spaced, spaced.length))
                    assertEquals(spaced, spacedResult.commandLine)
                    assertEquals(spaced.length, spacedResult.cursorOffset)
                }
            } finally {
                snapshots.close()
            }
        }

    private fun powerShellEditorTestScript(scenario: String): String =
        listOf("powershell-editor-test.ps1", scenario).joinToString("\n") { name ->
            checkNotNull(javaClass.getResourceAsStream(name)).use { it.readBytes().toString(Charsets.UTF_8) }
        }

    private fun powerShellEditorListener(snapshots: Channel<String>): TerminalWorkspaceListener =
        object : TerminalWorkspaceListener {
            override fun titleChanged(
                tab: TerminalWorkspaceTab,
                title: String,
            ) {
                if (title.startsWith("ketra-")) snapshots.trySend(title).getOrThrow()
            }
        }

    private suspend fun Channel<String>.assertPowerShellBuffer(
        line: String,
        cursor: Int,
        exitCode: Int? = null,
    ) {
        val snapshot = withTimeout(15.seconds) { receive() }
        val fields = snapshot.split(';')
        assertEquals(5, fields.size, snapshot)
        assertEquals("ketra-buffer", fields[0])
        assertEquals(cursor.toString(), fields[2], snapshot)
        if (exitCode != null) assertEquals(exitCode.toString(), fields[3], "Prefix replacement must preserve LASTEXITCODE")
        assertEquals(line, String(Base64.getDecoder().decode(fields[4]), Charsets.UTF_8))
    }

    @ParameterizedTest(name = "PowerShell completion round trip [{0}]")
    @ValueSource(strings = ["powershell.exe", "pwsh"])
    fun `PowerShell native completion round trips through real PSReadLine and PTY`(executableName: String): Unit =
        runBlocking {
            val executable = installedExecutable(executableName)
            assumeTrue(executable != null, "$executableName is not installed")
            val historySetup =
                "Set-PSReadLineOption -HistorySaveStyle SaveNothing; [Microsoft.PowerShell.PSConsoleReadLine]::ClearHistory()"
            TerminalWorkspace().use { workspace ->
                val tab =
                    workspace.openTab(
                        TerminalProfile(
                            "powershell",
                            "PowerShell",
                            listOf(executable!!, "-NoLogo", "-NoProfile"),
                            startupCommand = TerminalStartupCommand(historySetup),
                        ),
                        TerminalWorkspaceOpenOptions(),
                    )
                val session = tab.session

                withTimeout(15.seconds) {
                    session.shellIntegrationState.revision.first {
                        val state = session.shellIntegrationState
                        val metadata = state.commandMetadata(state.latestCommandRecordId())
                        metadata?.commandText == historySetup && metadata.finishedAtEpochMillis != null
                    }
                }
                val historyRecord = session.shellIntegrationState.latestCommandRecordId()
                assertEquals(0, session.shellIntegrationState.commandMetadata(historyRecord)?.exitCode, "History setup failed")
                val emptyContext = session.awaitLine("", "test history setup", requireShellCompletion = true)
                val emptyResult = assertNotNull(session.requestShellCompletions("", 0))
                assertTrue(emptyResult.candidates.isNotEmpty())
                assertTrue(emptyResult.candidates.size <= 128)
                assertEquals(TerminalInputAdmission.ACCEPTED, session.submitInput(emptyContext, listOf(TerminalPasteEvent("Get-Chi"))))
                val expected = session.awaitLine("Get-Chi", "typing after empty-buffer completion")
                val result = assertNotNull(session.requestShellCompletions("Get-Chi", 7))
                assertTrue(result.candidates.any { it.replacementText == "Get-ChildItem" })
                val replacement = TerminalTextReplacementEvent(result.replacementStartOffset, result.replacementEndOffset, "Get-ChildItem")
                assertEquals(TerminalInputAdmission.ACCEPTED, session.submitInput(expected, listOf(replacement)))
                session.awaitLine("Get-ChildItem", "first acceptance")
                var lastAcceptedEdit: TerminalCommandEditContext? = null
                repeat(3) { iteration ->
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
                    val afterDeletion = session.awaitLine("Get-Chi", "deletion in acceptance cycle $iteration")
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
                    val accepted = session.awaitLine("Get-ChildItem", "acceptance cycle $iteration")
                    if (iteration == 2) {
                        lastAcceptedEdit = accepted
                    }
                }
                val command = "Write-Output 'ketra-accepted'"
                assertEquals(
                    TerminalInputAdmission.ACCEPTED,
                    session.submitInput(
                        checkNotNull(lastAcceptedEdit),
                        listOf(TerminalTextReplacementEvent(0, "Get-ChildItem".length, command), TerminalKeyEvent.key(TerminalKey.ENTER)),
                    ),
                )
                withTimeout(15.seconds) {
                    session.shellIntegrationState.revision.first {
                        val state = session.shellIntegrationState
                        state.commandText(state.latestCommandRecordId()) == command
                    }
                }
                val emptyPostExecution = session.awaitLine("", "executing accepted command", requireShellCompletion = true)
                val encodingLine = "Get-Content .\\utf8-extended.txt -Encoding u"
                assertEquals(
                    TerminalInputAdmission.ACCEPTED,
                    session.submitInput(emptyPostExecution, listOf(TerminalPasteEvent(encodingLine))),
                )
                val encodingEdit = session.awaitLine(encodingLine, "typing encoding argument")
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
                var currentLine = encodingLine.dropLast(1) + utf8.replacementText
                var currentEdit = session.awaitLine(currentLine, "accepting encoding argument")
                for ((line, expectedText) in listOf(
                    "Get-Content -Enc u" to "UTF8",
                    "Get-Content -Encoding:u" to "UTF8",
                    "Get-ChildItem -H" to "-Hidden",
                )) {
                    assertEquals(
                        TerminalInputAdmission.ACCEPTED,
                        session.submitInput(
                            currentEdit,
                            listOf(TerminalTextReplacementEvent(0, currentLine.length, line)),
                        ),
                    )
                    val edit = session.awaitLine(line, "typing parameter case '$line'")
                    val answer = assertNotNull(session.requestShellCompletions(line, line.length))
                    assertNull(answer.externalCommandName)
                    val candidate = answer.candidates.first { it.replacementText.equals(expectedText, ignoreCase = true) }
                    assertEquals(
                        TerminalInputAdmission.ACCEPTED,
                        session.submitInput(
                            edit,
                            listOf(TerminalTextReplacementEvent(0, line.length - answer.replacementStartOffset, candidate.replacementText)),
                        ),
                    )
                    currentLine = line.take(answer.replacementStartOffset) + candidate.replacementText
                    currentEdit = session.awaitLine(currentLine, "accepting parameter case '$line'")
                }
            }
        }

    @ParameterizedTest(name = "PowerShell completion progress [{0}]")
    @ValueSource(strings = ["powershell.exe", "pwsh"])
    fun `PowerShell completion progress does not invalidate its non editing reply`(executableName: String): Unit =
        runBlocking {
            val executable = installedExecutable(executableName)
            assumeTrue(executable != null, "$executableName is not installed")
            val setup =
                "function Get-KetraProgress { param([string] \$Value) }; " +
                    "Register-ArgumentCompleter -CommandName Get-KetraProgress -ParameterName Value -ScriptBlock { " +
                    "Write-Progress -Activity 'Completing' -Status 'Loading' -PercentComplete 50; " +
                    "Write-Progress -Activity 'Completing' -Completed; " +
                    "[Management.Automation.CompletionResult]::new('known', 'known', 'ParameterValue', 'known') }"
            TerminalWorkspace().use { workspace ->
                val session =
                    workspace
                        .openTab(
                            TerminalProfile(
                                "powershell",
                                "PowerShell",
                                listOf(executable!!, "-NoLogo", "-NoProfile"),
                                startupCommand = TerminalStartupCommand(setup),
                            ),
                            TerminalWorkspaceOpenOptions(),
                        ).session
                withTimeout(15.seconds) {
                    session.shellIntegrationState.revision.first {
                        val state = session.shellIntegrationState
                        val metadata = state.commandMetadata(state.latestCommandRecordId())
                        metadata?.commandText == setup && metadata.finishedAtEpochMillis != null
                    }
                }
                session.awaitLine("", "installing progress completer", requireShellCompletion = true)
                val line = "Get-KetraProgress -Value k"
                session.submitInput(TerminalPasteEvent(line))
                session.awaitLine(line, "typing progress completion")
                val result = assertNotNull(session.requestShellCompletions(line, line.length))
                assertTrue(result.candidates.any { it.replacementText == "known" })
                assertEquals(line, session.activeShellCommandLine()?.commandText)
            }
        }

    @ParameterizedTest
    @ValueSource(strings = ["powershell.exe", "pwsh"])
    fun `PowerShell completes a new session alias as its first native query`(executableName: String): Unit =
        runBlocking {
            val executable = installedExecutable(executableName)
            assumeTrue(executable != null, "$executableName is not installed")
            val setup = "Set-Alias ketra_smoke_echo Write-Output"
            TerminalWorkspace().use { workspace ->
                val session =
                    workspace
                        .openTab(
                            TerminalProfile(
                                "powershell",
                                "PowerShell",
                                listOf(executable!!, "-NoLogo", "-NoProfile"),
                                startupCommand = TerminalStartupCommand(setup),
                            ),
                            TerminalWorkspaceOpenOptions.create { it.columns = 100 },
                        ).session
                withTimeout(15.seconds) {
                    session.shellIntegrationState.revision.first {
                        val state = session.shellIntegrationState
                        val metadata = state.commandMetadata(state.latestCommandRecordId())
                        metadata?.commandText == setup && metadata.finishedAtEpochMillis != null
                    }
                }
                session.awaitLine("", "defining alias", requireShellCompletion = true)
                val line = "ketra_smoke_e"
                session.submitInput(TerminalPasteEvent(line))
                session.awaitLine(line, "typing alias")
                assertNotNull(
                    session.captureCommandEdit(),
                    "First alias query has no editable context; current ${session.activeShellCommandLine()}",
                )
                val queryStarted =
                    kotlin.time.TimeSource.Monotonic
                        .markNow()
                val response = session.requestShellCompletions(line, line.length)
                val result =
                    assertNotNull(
                        response,
                        "First alias completion failed after ${queryStarted.elapsedNow()}; " +
                            "available=${session.shellCompletionAvailable}, current=${session.activeShellCommandLine()}",
                    )
                assertTrue(result.candidates.any { it.replacementText == "ketra_smoke_echo" })
                assertEquals(line, session.activeShellCommandLine()?.commandText)
            }
        }

    @Test
    fun `PowerShell native hook uses functions aliases and registered completers from its own session`(
        @TempDir directory: Path,
    ) {
        val executable = installedExecutable("pwsh", "powershell.exe")
        assumeTrue(executable != null, "PowerShell is not installed")

        fun resource(name: String) = checkNotNull(javaClass.getResourceAsStream(name)).use { it.readBytes().toString(Charsets.UTF_8) }
        val script = resource("powershell-completion-test.ps1").replace("__SCRIPT__", resource("powershell-completion.ps1"))
        val scriptPath = Files.writeString(directory.resolve("completion-test.ps1"), script)
        val result =
            runProcess(
                listOf(
                    executable!!,
                    "-NoProfile",
                    "-NonInteractive",
                    "-ExecutionPolicy",
                    "Bypass",
                    "-File",
                    scriptPath.toString(),
                ),
                emptyMap(),
            )
        assertEquals(0, result.exitCode, visibleEscapes(result.stdout))
        val readiness = result.stdout.lineSequence().first { it.contains("KetraTermCompletion;2;Ready;") }
        assertTrue(readiness.trimEnd('\u0007', '\r').endsWith(";PrefixReset"), visibleEscapes(readiness))
        val messages =
            result.stdout
                .lineSequence()
                .filter { it.contains("KetraTermCompletion;2;Result;") }
                .toList()
        val application =
            result.stdout
                .lineSequence()
                .firstOrNull { it.startsWith("APPLICATION=") }
                ?.substringAfter('=')
                ?.split(';')
        assertEquals(if (application == null) 10 else 18, messages.size, visibleEscapes(result.stdout))

        fun encoded(value: String) = Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))

        fun fields(index: Int) = messages[index].substringAfter("KetraTermCompletion;").trimEnd('\u0007', '\r').split(';')
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
            assertEquals("", fields(5)[9], "Command-name completion needs no external application identity")
            assertEquals("", fields(6)[9], "A shadowing function is not an external application")
            for (index in 7..10) assertEquals(encoded(application[0]), fields(index)[9], visibleEscapes(messages[index]))
            assertEquals("", fields(11)[9], "The cursor is outside the nested command")
            assertEquals("", fields(12)[9], "Dynamic command text is not evaluated")
        }
        val offset = if (application == null) 5 else 13
        assertEquals("0", fields(offset)[10], "ls --a must not receive Unix options")
        assertTrue(messages[offset + 1].contains(encoded("-Hidden")))
        for (index in offset + 2..offset + 3) {
            val replacements =
                fields(index).drop(11).chunked(4).map { candidate ->
                    String(Base64.getDecoder().decode(candidate[1]), Charsets.UTF_8)
                }
            assertTrue(replacements.any { it.equals("UTF8", ignoreCase = true) }, visibleEscapes(messages[index]))
            assertEquals("", fields(index)[9])
        }
        assertEquals("9", fields(offset + 2)[6])
        assertEquals("14", fields(offset + 3)[6])
        assertTrue(messages[offset + 4].contains(";ProviderContainer;"))
        assertTrue(result.stdout.contains("EXIT=77"), visibleEscapes(result.stdout))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "PROMPT_COMMAND=user_prompt",
            "PROMPT_COMMAND=(user_prompt ': second callback')",
            "PROMPT_COMMAND='user_prompt; # user comment'",
        ],
    )
    fun `ordinary Bash startup keeps markers when bashrc replaces the prompt hook`(
        promptCommand: String,
        @TempDir directory: Path,
    ) {
        val bash = installedExecutable("bash", "bash.exe")
        assumeTrue(bash != null, "bash is not installed")
        val home = Files.createDirectory(directory.resolve("home"))
        Files.writeString(
            home.resolve(".bashrc"),
            "user_prompt() { local status=\"${'$'}?\"; PS1='test> '; printf 'USER_PROMPT=%s\\n' \"${'$'}status\"; }\n$promptCommand\n",
        )
        val profile =
            TerminalProfile(
                id = "bash",
                displayName = "Bash",
                command = listOf(bash!!, "-i"),
                environment = mapOf("HOME" to home.toString(), "TERM" to "xterm-256color"),
            )
        val launch = TerminalShellIntegrationBootstrap.apply(profile, enabled = true, scriptDirectory = directory.resolve("hooks")).profile
        val result = runProcess(launch.command, environment = launch.environment, standardInput = "false\nprintf 'BODY\\n'\nexit\n")

        assertEquals(0, result.exitCode, visibleEscapes(result.stdout))
        val markers = Regex("\u001b]133;([^\u0007]+)\u0007").findAll(result.stdout).map { it.groupValues[1] }.toList()
        assertEquals(listOf("A", "B", "C", "D;1", "A", "B", "C", "D;0", "A", "B", "C"), markers, visibleEscapes(result.stdout))
        assertTrue(result.stdout.contains("USER_PROMPT=1"), visibleEscapes(result.stdout))
        assertEquals(3, Regex("test> \u001b]133;B\u0007").findAll(result.stdout).count(), visibleEscapes(result.stdout))
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
        val visibleOutput = result.stdout.replace(Regex("\u001b][^\u0007]*\u0007"), "")
        assertTrue(visibleOutput.lineSequence().any { it.trimEnd('\r') == "PROJECT_BIN_FIRST=yes" }, visibleEscapes(result.stdout))
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

    @ParameterizedTest(name = "Fish initial command boundary with {0}-column prompt")
    @ValueSource(ints = [12, 19, 20, 21, 40])
    fun `fish initial prompt at the right margin leaves an empty command line`(promptWidth: Int): Unit =
        runBlocking {
            val fish = installedExecutable("fish", "fish.exe")
            assumeTrue(fish != null, "fish is not installed")
            val prompt = "p".repeat(promptWidth - 1) + " "
            val setup =
                "set -g fish_greeting; set -g fish_history ''; set -g fish_autosuggestion_enabled 0; " +
                    "function fish_prompt; set_color green; printf '%s' '$prompt'; set_color normal; end; function fish_right_prompt; end"
            TerminalWorkspace().use { workspace ->
                val session =
                    workspace
                        .openTab(
                            TerminalProfile("fish", "Fish", listOf(fish!!, "--no-config", "-C", setup, "-i")),
                            TerminalWorkspaceOpenOptions.create { it.columns = 20 },
                        ).session
                session.awaitLine("", "rendering the $promptWidth-column prompt")
                assertEquals(0, session.activeShellCommandLine()?.cursorOffset)
                session.submitInput(TerminalPasteEvent("echo marker"))
                session.awaitLine("echo marker", "typing after the $promptWidth-column prompt")
            }
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
                command = listOf(bash!!, "--norc"),
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
            workingDirectory.toRealPath(),
            Path.of(URI("file", null, URI(directoryUri).path, null)).toRealPath(),
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
                command = listOf(bash!!, "--norc"),
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
                    "$bootstrap; __ketraterm_preexec; { clear >/dev/null 2>&1 || printf '\\033[H\\033[2J\\033[3J'; }; true; __ketraterm_prompt_command; __ketraterm_finish_prompt; __ketraterm_preexec; false; __ketraterm_prompt_command\n",
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

    @ParameterizedTest(name = "Fish prompt boundary with native markers [{0}]")
    @ValueSource(booleans = [false, true])
    fun `generated fish hook terminates the actual prompt without duplicating native lifecycle markers`(nativeMarkers: Boolean) {
        val fish = installedExecutable("fish", "fish.exe")
        assumeTrue(fish != null, "fish is not installed")
        val features = "--features=${if (nativeMarkers) "mark-prompt" else "no-mark-prompt"}"
        if (nativeMarkers) {
            assumeTrue(
                runProcess(listOf(fish!!, "--no-config", features, "-c", "status test-feature mark-prompt")).exitCode == 0,
                "Fish has no native prompt markers",
            )
        }

        val profile =
            TerminalProfile(
                id = "fish",
                displayName = "Fish",
                command =
                    listOf(
                        fish!!,
                        features,
                        "--init-command",
                        "function fish_prompt; printf 'prompt> '; end",
                    ),
            )
        val integrated = TerminalShellIntegrationBootstrap.apply(profile, enabled = true).profile
        val result =
            runProcess(
                integrated.command +
                    listOf(
                        "--no-config",
                        "-c",
                        "function later_directory_event --on-event fish_prompt; printf '\\e]7;file://other-host/tmp\\a'; end; " +
                            "emit fish_prompt; fish_prompt; emit fish_preexec false; false; emit fish_postexec false; emit fish_prompt; fish_prompt",
                    ),
                environment = integrated.environment,
            )

        assertEquals(0, result.exitCode)
        if (nativeMarkers) {
            // In this noninteractive invocation only our hook runs. A/C/D belong to Fish's reader.
            assertMarkerOrder(result.stdout, "B", "B")
            assertFalse(Regex("\u001b]133;[ACD]").containsMatchIn(result.stdout), visibleEscapes(result.stdout))
        } else {
            assertMarkerOrder(result.stdout, "A", "B", "C", "D;1", "A", "B")
        }
        assertEquals(
            2,
            Regex("prompt> \u001b]7;file://localhost/[^\u0007]+\u0007\u001b]133;B\u0007").findAll(result.stdout).count(),
            visibleEscapes(result.stdout),
        )
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

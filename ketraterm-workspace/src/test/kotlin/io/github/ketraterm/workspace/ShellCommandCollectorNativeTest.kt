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

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Exercises the installed filesystem tools through direct shells and opt-in WSL. */
@Tag("native")
class ShellCommandCollectorNativeTest {
    @ParameterizedTest
    @ValueSource(strings = ["bash", "zsh", "wsl-bash", "wsl-zsh"])
    fun `native enumeration and fallbacks preserve names permissions and directory isolation`(shell: String) {
        val output =
            runCollector(
                shell,
                """
                command mkdir tools first second first/directory
                for file in 'first/with space' "first/with'quote" 'first/with${'$'}(injection)' first/duplicate second/duplicate second/last .hidden; do
                    printf '#!/bin/sh\n' > "${'$'}file"
                    command chmod +x "${'$'}file"
                done
                printf 'data' > first/not-executable
                command chmod -x first/not-executable
                command ln -s 'with space' first/link
                command ln -s directory first/directory-link
                # Git Bash may emulate links by copying, so dangling/cyclic
                # link cases require an actual symlink-capable filesystem.
                if [[ -L first/link ]]; then
                    command ln -s missing first/broken
                    command ln -s . first/loop
                fi
                native_find=${'$'}(command -v find)
                export KETRA_TEST_REAL_FIND="${'$'}native_find"
                command cat > tools/find <<'FINDER'
                #!/bin/sh
                case "${'$'}KETRA_TEST_MODE" in
                    unsupported) exit 2 ;;
                esac
                exec "${'$'}KETRA_TEST_REAL_FIND" "${'$'}@"
                FINDER
                command chmod +x tools/find
                find() { printf 'unexpected find function'; }
                chpwd() { printf 'unexpected directory hook'; }
                on_directory() { printf 'unexpected directory hook'; }
                chpwd_functions=(on_directory)
                origin=${'$'}PWD
                for mode in native unsupported missing relative; do
                    export KETRA_TEST_MODE=${'$'}mode
                    PATH="${'$'}origin/tools:first:second:first:missing:"
                    if [[ ${'$'}mode == missing ]]; then
                        PATH='first:second:first:missing:' 
                    elif [[ ${'$'}mode == relative ]]; then
                        PATH='tools:first:second:first:missing:'
                    fi
                    set -f
                    __ketraterm_collect_path_commands
                    printf '\0'
                    [[ ${'$'}PWD == "${'$'}origin" && ${'$'}- == *f* ]] || exit 20
                done
                """.trimIndent(),
            )
        val snapshots = output.split("__ketraterm_inventory_end\u0000").also { assertEquals(5, it.size) }
        val expected = setOf("with space", "with'quote", "with\$(injection)", "duplicate", "last", "link", ".hidden")
        snapshots.dropLast(1).forEachIndexed { index, snapshot ->
            val names = snapshot.split('\u0000').filter(String::isNotEmpty).filter { it != "find" }
            assertEquals(expected, names.toSet(), "mode $index")
            assertEquals(expected.size, names.size, "mode $index must deduplicate PATH entries")
        }
        assertEquals("", snapshots.last())
    }

    @ParameterizedTest
    @ValueSource(strings = ["bash", "zsh", "wsl-bash", "wsl-zsh"])
    fun `large directories retain complete results and overflow never reports success`(
        shell: String,
        @TempDir directory: Path,
    ) {
        shellCommand(shell)
        val setup =
            if (shell.startsWith("wsl-")) {
                """
                command mkdir bin
                chmod_command=${'$'}(command -v chmod)
                for ((start=0; start<32769; start+=1000)); do
                    batch=()
                    for ((i=start; i<start+1000 && i<32769; i++)); do
                        file="${'$'}PWD/bin/command_${'$'}i"
                        printf '#!/bin/sh\n' > "${'$'}file"
                        batch+=("${'$'}file")
                    done
                    command "${'$'}chmod_command" +x "${'$'}{batch[@]}"
                done
                """.trimIndent()
            } else {
                val bin = Files.createDirectory(directory.resolve("bin"))
                // Share executables below NTFS's link limit; .exe avoids MSYS probing every shebang.
                for (start in 0 until 32769 step 512) {
                    val executable = Files.writeString(directory.resolve("executable_$start"), "#!/bin/sh\n")
                    assertTrue(executable.toFile().setExecutable(true, false))
                    repeat(minOf(512, 32769 - start)) { Files.createLink(bin.resolve("command_${start + it}.exe"), executable) }
                }
                "builtin cd -- '${directory.toString().replace('\\', '/').replace("'", "'\\''")}' || exit 13"
            }
        val output =
            runCollector(
                shell,
                setup + "\n" +
                    """
                    native_find=${'$'}(command -v find)
                    command mkdir tools
                    command ln -s "${'$'}native_find" tools/find
                    PATH="${'$'}PWD/tools:${'$'}PWD/bin"
                    __ketraterm_collect_path_commands
                    """.trimIndent(),
            )
        val records = output.split('\u0000')
        assertEquals("__ketraterm_inventory_overflow", records.last())
        assertEquals(32768, records.size - 1)
        assertEquals(32768, records.dropLast(1).toSet().size)
        assertTrue(records.dropLast(1).all { it == "find" || it.startsWith("command_") })
    }

    @ParameterizedTest
    @ValueSource(strings = ["bash", "zsh", "wsl-bash", "wsl-zsh"])
    fun `raw entry overflow and failed directory streams never publish successful partial inventory`(shell: String) {
        val zsh = shell.endsWith("zsh")
        val resource = if (zsh) "zsh-completion.zsh" else "bash-completion.bash"
        val publisher = if (zsh) "__ketraterm_zsh_publish_commands" else "__ketraterm_publish_commands"
        val hook = checkNotNull(javaClass.getResourceAsStream(resource)).use { it.readBytes().toString(UTF_8) }
        for ((mode, reason) in listOf("partial" to "Unavailable", "overflow" to "Overflow")) {
            val output =
                runCollector(
                    shell,
                    hook + "\n" +
                        """
                        command mkdir directory
                        printf '#!/bin/sh\n' > command
                        command chmod +x command
                        printf 'data' > not-executable
                        command chmod -x not-executable
                        cat_command=${'$'}(command -v cat)
                        export KETRA_TEST_CAT="${'$'}cat_command" KETRA_TEST_STREAM="${'$'}PWD/raw-stream" KETRA_TEST_MODE=$mode
                        if [[ ${'$'}KETRA_TEST_MODE == overflow ]]; then
                            # Exactly 131,073 records. Fixed-size batches keep setup
                            # bounded and let cat emulate find's buffered output.
                            for ((start=0; start<43691; start+=256)); do
                                batch=()
                                for ((i=start; i<start+256 && i<43691; i++)); do
                                    batch+=(./not-executable ./directory ./command)
                                done
                                printf '%s\0' "${'$'}{batch[@]}"
                            done > raw-stream
                        fi
                        command cat > find <<'FINDER'
                        #!/bin/sh
                        [ "${'$'}5" != 0 ] || exit 0
                        if [ "${'$'}KETRA_TEST_MODE" = partial ]; then
                            printf './command\0'
                            exit 1
                        fi
                        exec "${'$'}KETRA_TEST_CAT" "${'$'}KETRA_TEST_STREAM"
                        FINDER
                        command chmod +x find
                        PATH=${'$'}PWD
                        __ketraterm_collect_path_commands > inventory
                        command "${'$'}cat_command" inventory
                        __ketraterm_collect_path_commands() { command "${'$'}cat_command" inventory; }
                        $publisher test 1 Path
                        """.trimIndent(),
                )
            val marker = if (mode == "overflow") "__ketraterm_inventory_overflow" else "__ketraterm_inventory_unavailable"
            assertEquals(
                "command\u0000$marker" +
                    "\u001b]633;KetraTermCommands;3;Begin;test;1\u0007" +
                    "\u001b]633;KetraTermCommands;3;Fail;test;1;$reason\u0007",
                output,
                mode,
            )
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["bash", "zsh", "wsl-bash", "wsl-zsh"])
    fun `raw filename bytes are bounded independently of executable and entry counts`(shell: String) {
        val output =
            runCollector(
                shell,
                """
                printf -v KETRA_TEST_NAME '%0170d' 0
                export KETRA_TEST_NAME
                printf '#!/bin/sh\n' > "${'$'}KETRA_TEST_NAME"
                command chmod +x "${'$'}KETRA_TEST_NAME"
                export KETRA_TEST_CAT=${'$'}(command -v cat) KETRA_TEST_STREAM="${'$'}PWD/raw-stream"
                # 100,096 entries remain below the entry limit but exceed 16 MiB.
                # Repeated executable names must count before deduplication.
                batch=()
                for ((i=0; i<256; i++)); do batch+=("./${'$'}KETRA_TEST_NAME"); done
                for ((start=0; start<100000; start+=256)); do
                    printf '%s\0' "${'$'}{batch[@]}"
                done > raw-stream
                command cat > find <<'FINDER'
                #!/bin/sh
                [ "${'$'}5" != 0 ] || exit 0
                exec "${'$'}KETRA_TEST_CAT" "${'$'}KETRA_TEST_STREAM"
                FINDER
                command chmod +x find
                PATH=${'$'}PWD
                __ketraterm_collect_path_commands
                """.trimIndent(),
            )
        assertEquals("0".repeat(170) + "\u0000__ketraterm_inventory_overflow", output)
    }

    @ParameterizedTest
    @ValueSource(strings = ["bash", "zsh", "wsl-bash", "wsl-zsh"])
    fun `PATH component budget counts repeated directories before deduplication`(shell: String) {
        val output =
            runCollector(
                shell,
                """
                printf '#!/bin/sh\n' > command
                command chmod +x command
                PATH=.
                for ((i=1; i<4096; i++)); do PATH=${'$'}PATH:.; done
                __ketraterm_collect_path_commands
                printf '\0'
                PATH=${'$'}PATH:.
                __ketraterm_collect_path_commands
                """.trimIndent(),
            )
        assertEquals(
            "command\u0000__ketraterm_inventory_end\u0000__ketraterm_inventory_overflow",
            output,
        )
    }

    @ParameterizedTest
    @ValueSource(strings = ["bash", "wsl-bash"])
    fun `disabled Bash builtins leave local inventory while same named executables remain available`(shell: String) {
        val publisher = checkNotNull(javaClass.getResourceAsStream("bash-completion.bash")).use { it.readBytes().toString(UTF_8) }
        val output =
            runCollector(
                shell,
                publisher + "\n" +
                    """
                    command mkdir bin
                    printf '#!/bin/sh\n' > bin/times
                    command chmod +x bin/times
                    PATH="${'$'}PWD/bin"
                    builtin enable times
                    __ketraterm_publish_commands test 1 Local
                    builtin enable -n times
                    __ketraterm_publish_commands test 2 Local
                    __ketraterm_publish_commands test 3 Path
                    builtin enable times
                    __ketraterm_publish_commands test 4 Local
                    """.trimIndent(),
            )
        val names = inventoryCommandNames(output)
        assertEquals(4, names.size)
        assertEquals(listOf(true, false, true, true), names.map { "times" in it })
        assertEquals(setOf("times"), names[2])
        assertTrue(listOf(names[0], names[1], names[3]).all { "printf" in it })
    }

    @ParameterizedTest
    @ValueSource(strings = ["zsh", "wsl-zsh"])
    fun `zsh retains explicit hashes and refreshes PATH without changing caller options`(shell: String) {
        val publisher = checkNotNull(javaClass.getResourceAsStream("zsh-completion.zsh")).use { it.readBytes().toString(UTF_8) }
        val output =
            runCollector(
                shell,
                publisher + "\n" +
                    """
                    command mkdir first second outside
                    for file in first/first-command second/second-command outside/target; do
                        printf '#!/bin/sh\n' > "${'$'}file"
                        command chmod +x "${'$'}file"
                    done
                    rm_command=${'$'}(command -v rm)
                    PATH="${'$'}PWD/first"
                    hash explicit="${'$'}PWD/outside/target"
                    setopt HASH_LIST_ALL
                    __ketraterm_zsh_publish_commands test 1 Path
                    [[ -o HASH_LIST_ALL ]] || exit 30
                    command "${'$'}rm_command" outside/target
                    PATH="${'$'}PWD/second"
                    __ketraterm_zsh_publish_commands test 2 Path
                    [[ -o HASH_LIST_ALL ]] || exit 31
                    """.trimIndent(),
            )
        assertEquals(listOf(setOf("first-command", "explicit"), setOf("second-command")), inventoryCommandNames(output))
    }

    private fun inventoryCommandNames(output: String): List<Set<String>> =
        output
            .split(Regex("\u001b]633;KetraTerm(?:Shell)?Commands;3;Begin;test;[0-9]+\u0007"))
            .drop(1)
            .map { snapshot ->
                assertTrue(Regex("KetraTerm(?:Shell)?Commands;3;End;test;").containsMatchIn(snapshot))
                Regex(";Chunk;test;[0-9]+;[0-9]+;([^\u0007]+)")
                    .findAll(snapshot)
                    .flatMap { chunk ->
                        Base64
                            .getDecoder()
                            .decode(chunk.groupValues[1])
                            .toString(UTF_8)
                            .dropLast(1)
                            .split('\u0000')
                            .chunked(4)
                            .map { it[0] }
                    }.toSet()
            }

    private fun runCollector(
        shell: String,
        scenario: String,
    ): String {
        val command = shellCommand(shell)
        val collector = checkNotNull(javaClass.getResourceAsStream("path-commands.sh")).use { it.readBytes().toString(UTF_8) }
        val script =
            """
            if [[ -n ${'$'}{BASH_VERSION-} ]] && ! builtin compgen -V commands -A builtin 2>/dev/null; then
                printf '__ketraterm_inventory_unsupported'
                exit 0
            fi
            fixture=${'$'}(command mktemp -d -t ketraterm-collector.XXXXXXXX) || exit 10
            [[ ${'$'}fixture == /*/ketraterm-collector.* && -d ${'$'}fixture ]] || exit 11
            cleanup=${'$'}(command -v rm)
            trap 'command "${'$'}cleanup" -rf -- "${'$'}fixture"' EXIT
            builtin cd -- "${'$'}fixture" || exit 12
            """.trimIndent() + "\n" + collector + "\n" + scenario + "\n"
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = FutureTask { process.inputStream.use { it.readAllBytes().toString(UTF_8) } }
        Thread.ofVirtual().name("command-collector-test-output").start(output)
        try {
            process.outputStream.use { it.write(script.toByteArray(UTF_8)) }
            assertTrue(process.waitFor(120, TimeUnit.SECONDS), "Collector did not finish: $shell")
            val result = output.get(5, TimeUnit.SECONDS)
            assertEquals(0, process.exitValue(), result)
            assumeTrue(result != "__ketraterm_inventory_unsupported", "Command inventory requires Bash 5.3+")
            return result
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
                check(process.waitFor(5, TimeUnit.SECONDS)) { "Collector process did not stop: $shell" }
            }
            output.cancel(true)
        }
    }

    private fun shellCommand(shell: String): List<String> {
        val name = shell.removePrefix("wsl-")
        val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
        val executable =
            if (shell.startsWith("wsl-")) {
                val distribution = System.getProperty("terminal.pty.wsl.distribution")
                assumeTrue(windows && !distribution.isNullOrBlank(), "WSL collector checks require terminal.pty.wsl.distribution")
                listOf("wsl.exe", "-d", checkNotNull(distribution), "--", name)
            } else {
                val kind =
                    when {
                        name == "zsh" -> TerminalProfileKind.ZSH
                        windows -> TerminalProfileKind.GIT_BASH
                        else -> TerminalProfileKind.BASH
                    }
                val profile = TerminalProfileRegistry().availableProfiles().firstOrNull { it.kind == kind }
                assumeTrue(profile != null, "$name is not installed directly on this host")
                listOf(checkNotNull(profile).command.first())
            }
        return executable + if (name == "zsh") listOf("-f", "-s") else listOf("--noprofile", "--norc", "-s")
    }
}

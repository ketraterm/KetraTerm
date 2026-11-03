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
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.charset.StandardCharsets.UTF_8
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
                export KETRA_TEST_REAL_FIND="${'$'}native_find" KETRA_TEST_FIXTURE="${'$'}PWD"
                command cat > tools/find <<'FINDER'
                #!/bin/sh
                case "${'$'}KETRA_TEST_MODE" in
                    unsupported) exit 2 ;;
                    partial)
                        printf '%s\n' "${'$'}PWD" >> "${'$'}KETRA_TEST_FIXTURE/calls"
                        if [ "${'$'}6" = 1 ] && [ "${'$'}PWD" = "${'$'}KETRA_TEST_FIXTURE/first" ]; then
                            printf './duplicate\0'
                            exit 1
                        fi
                        ;;
                esac
                exec "${'$'}KETRA_TEST_REAL_FIND" "${'$'}@"
                FINDER
                command chmod +x tools/find
                chpwd() { printf 'unexpected directory hook'; }
                on_directory() { printf 'unexpected directory hook'; }
                chpwd_functions=(on_directory)
                origin=${'$'}PWD
                for mode in native unsupported partial missing; do
                    export KETRA_TEST_MODE=${'$'}mode
                    PATH="${'$'}origin/tools:first:second:first:missing:"
                    if [[ ${'$'}mode == missing ]]; then
                        PATH='first:second:first:missing:' 
                    fi
                    set -f
                    __ketraterm_collect_path_commands
                    printf '\0'
                    [[ ${'$'}PWD == "${'$'}origin" && ${'$'}- == *f* ]] || exit 20
                    if [[ ${'$'}mode == partial && ${'$'}OSTYPE != msys* && ${'$'}OSTYPE != cygwin* ]]; then
                        calls=${'$'}(<calls)
                        [[ ${'$'}calls == *"${'$'}origin/second"* ]] || exit 21
                    fi
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
    fun `large directories retain complete results and overflow never reports success`(shell: String) {
        val output =
            runCollector(
                shell,
                """
                command mkdir bin
                chmod_command=${'$'}(command -v chmod)
                native_find=${'$'}(command -v find)
                command mkdir tools
                command ln -s "${'$'}native_find" tools/find
                for ((start=0; start<32769; start+=1000)); do
                    batch=()
                    for ((i=start; i<start+1000 && i<32769; i++)); do
                        file="${'$'}PWD/bin/command_${'$'}i"
                        printf '#!/bin/sh\n' > "${'$'}file"
                        batch+=("${'$'}file")
                    done
                    command "${'$'}chmod_command" +x "${'$'}{batch[@]}"
                done
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
        val snapshots = output.split(Regex("\u001b]633;KetraTermCommands;3;Begin;test;[12]\u0007")).drop(1)
        assertEquals(2, snapshots.size)
        val names =
            snapshots.map { snapshot ->
                assertTrue(snapshot.contains("KetraTermCommands;3;End;test;"))
                Regex(";Chunk;test;[12];[0-9]+;([^\u0007]+)")
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
        assertEquals(listOf(setOf("first-command", "explicit"), setOf("second-command")), names)
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

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
package io.github.ketraterm.completion.engine

import io.github.ketraterm.completion.api.*
import io.github.ketraterm.completion.internal.commandLineAfterCandidate
import io.github.ketraterm.completion.model.*
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ArgumentHintsTest {
    @Test
    fun `built-in subcommands and options describe their arguments without changing edits`() =
        runTest {
            val engine = TerminalCompletionEngines.fromSources(emptyList())
            for ((line, text, hint) in listOf(
                Triple("git sw", "switch", "<branch>"),
                Triple("git commit -", "-m", "<message>"),
                Triple("c", "cd", "[directory]"),
                Triple("git che", "checkout", "[branch, file, tag or commit] [pathspec]"),
                Triple("git che", "cherry-pick", "<commit>"),
                Triple("git ad", "add", "[pathspec]"),
            )) {
                val request = request(line)
                val candidate = engine.completions(request).last().first { it.replacementText == text }
                assertEquals(hint, candidate.argumentHint, line)
                assertEquals(text, candidate.displayText)
                assertFalse(assertNotNull(request.commandLineAfterCandidate(candidate)).contains(hint))
            }
            for ((line, option) in listOf(
                "git commit --am" to "--amend",
                "git merge --ab" to "--abort",
                "git cherry-pick --con" to "--continue",
                "git branch --mer" to "--merged",
            )) {
                val flag = engine.completions(request(line)).last().first { it.replacementText == option }
                assertEquals("", flag.argumentHint, line)
            }
            val repeated = TerminalCompletionContext.resolve(request("git cherry-pick abc123 def456 "))
            assertEquals("commit", repeated.activePositionalArgument?.name)
            assertEquals(true, repeated.activePositionalArgument?.isVariadic)
            assertEquals(TerminalCompletionValueDomain.GIT_COMMIT, repeated.expectedValueDomain)
        }

    @Test
    fun `ordered optional and variadic arguments retain their declared names`() =
        runTest {
            val engine =
                TerminalCompletionEngines.fromSources(
                    emptyList(),
                    commandSpecs =
                        listOf(
                            TerminalCommandSpec(
                                "tool",
                                positionalArguments =
                                    listOf(
                                        TerminalArgumentSpec("input"),
                                        TerminalArgumentSpec("output", isOptional = true),
                                        TerminalArgumentSpec("extra", isOptional = true, isVariadic = true),
                                    ),
                                options =
                                    listOf(
                                        TerminalOptionSpec(
                                            listOf("--mode"),
                                            requiresValue = true,
                                            valueCandidates = listOf("fast", "slow"),
                                        ),
                                    ),
                            ),
                        ),
                )
            assertEquals(
                "<input> [output] [extra]",
                engine
                    .completions(request("to"))
                    .last()
                    .single()
                    .argumentHint,
            )
            assertEquals(
                "<value>",
                engine
                    .completions(request("tool --mo"))
                    .last()
                    .single()
                    .argumentHint,
            )
            val value = engine.completions(request("tool --mode f")).last().single()
            assertEquals("fast", value.replacementText)
            assertEquals("", value.argumentHint)
        }

    @Test
    fun `native command enrichment adds catalog hints without another shell query`() =
        runTest {
            var queries = 0
            val engine =
                TerminalCompletionEngines.fromSources(
                    emptyList(),
                    commandSpecs =
                        listOf(
                            TerminalCommandSpec(
                                "tool",
                                positionalArguments =
                                    listOf(
                                        TerminalArgumentSpec("directory", pathKind = TerminalPathArgumentKind.DIRECTORY),
                                    ),
                            ),
                        ),
                    shellProvider =
                        TerminalCompletionShellProvider { _, _, _ ->
                            queries++
                            val candidate = TerminalCompletionCandidate("tool", 0, 2, "native", TerminalCompletionCandidateKind.COMMAND)
                            TerminalCompletionShellResult(listOf(candidate))
                        },
                )
            assertEquals(
                "<directory>",
                engine
                    .completions(request("to"))
                    .last()
                    .single()
                    .argumentHint,
            )
            assertEquals(1, queries)
        }

    @Test
    fun `equivalent candidates retain hints regardless of source order and leave ranking unchanged`() =
        runTest {
            val plain = TerminalCompletionCandidate("tool", 0, 2, "primary", TerminalCompletionCandidateKind.COMMAND)
            val hinted = plain.copy(source = "metadata", argumentHint = "<input>")
            for (candidates in listOf(listOf(plain, hinted), listOf(hinted, plain))) {
                val engine =
                    TerminalCompletionEngines.fromSources(
                        candidates.map { item -> TerminalCompletionSourceEntry({ _, _, _ -> listOf(item) }) },
                        commandSpecs = emptyList(),
                    )
                val result = engine.completions(request("to")).last().single()
                assertEquals("<input>", result.argumentHint)
                assertEquals("tool", result.replacementText)
                val baseline =
                    TerminalCompletionEngines
                        .fromSources(
                            candidates.map { item ->
                                TerminalCompletionSourceEntry({ _, _, _ -> listOf(item.copy(argumentHint = "")) })
                            },
                            commandSpecs = emptyList(),
                        ).completions(request("to"))
                        .last()
                        .single()
                assertEquals(baseline.score, result.score)
            }
        }

    @Test
    fun `option value labels use the catalog localization callback`() =
        runTest {
            val specs =
                TerminalCommandSpecs.defaults { key, english ->
                    if (key == "spec.git.commit.option.-m.valueName") "mensaje" else english
                }
            val engine = TerminalCompletionEngines.fromSources(emptyList(), commandSpecs = specs)
            val option = engine.completions(request("git commit -")).last().first { it.replacementText == "-m" }
            assertEquals("<mensaje>", option.argumentHint)
        }

    @Test
    fun `localized operand labels preserve edits scores and domain routing`() =
        runTest {
            val labels =
                mapOf(
                    "spec.cd.argument.directory.name" to "directorio",
                    "spec.docker.exec.argument.container.name" to "contenedor",
                    "spec.git.reset.argument.0.name" to "commit o ruta",
                    "spec.npm.run.argument.script.name" to "guion",
                    "spec.git.restore.option.--source.valueName" to "revision",
                )
            val specs = TerminalCommandSpecs.defaults { key, english -> labels[key] ?: english }
            val englishEngine = TerminalCompletionEngines.fromSources(emptyList())
            val localizedEngine = TerminalCompletionEngines.fromSources(emptyList(), commandSpecs = specs)
            for ((line, text, hint) in listOf(
                Triple("c", "cd", "[directorio]"),
                Triple("docker ex", "exec", "<contenedor> <command> [args]"),
                Triple("git res", "reset", "[commit o ruta]"),
                Triple("npm ru", "run", "[guion] [args]"),
                Triple("git restore --sou", "--source", "<revision>"),
            )) {
                val english = englishEngine.completions(request(line)).last().first { it.replacementText == text }
                val localized = localizedEngine.completions(request(line)).last().first { it.replacementText == text }
                assertEquals(hint, localized.argumentHint, line)
                assertEquals(english.copy(argumentHint = hint), localized, line)
            }
            assertEquals(
                TerminalCompletionValueDomain.NPM_SCRIPT,
                TerminalCompletionContext.resolve(request("npm run "), specs).expectedValueDomain,
            )
        }

    @Test
    fun `argument value labels cover the built-in catalog beyond Git`() =
        runTest {
            val engine = TerminalCompletionEngines.fromSources(emptyList())
            for ((line, option, hint) in listOf(
                Triple("gradle --exclude", "--exclude-task", "<task>"),
                Triple("kubectl get --out", "--output", "<format>"),
                Triple("cargo --feat", "--features", "<features>"),
                Triple("kotlinc -class", "-classpath", "<classpath>"),
                Triple("aws --out", "--output", "<format>"),
            )) {
                val candidate = engine.completions(request(line)).last().first { it.replacementText == option }
                assertEquals(hint, candidate.argumentHint, line)
            }
        }

    @Test
    fun `every required built-in option value has named or typed metadata`() {
        fun verify(commands: List<TerminalCommandSpec>) {
            for (command in commands) {
                for (option in command.options.filter { it.requiresValue }) {
                    assertTrue(
                        option.valueName.isNotBlank() || option.valuePathKind != TerminalPathArgumentKind.NONE,
                        "${command.name} ${option.names}: missing argument metadata",
                    )
                }
                verify(command.subcommands)
            }
        }
        verify(TerminalCommandSpecs.defaults())
    }

    @Test
    fun `scalar routing metadata does not invent operand names or requiredness`() =
        runTest {
            val domain = TerminalCompletionValueDomain("host.internal.lookup-key")
            val specs =
                listOf(
                    TerminalCommandSpec(
                        "tool",
                        positionalArgumentPathKind = TerminalPathArgumentKind.DIRECTORY,
                        positionalArgumentValueDomain = domain,
                    ),
                )
            val engine =
                TerminalCompletionEngines.fromSources(
                    emptyList(),
                    commandSpecs = specs,
                )
            assertEquals(
                "",
                engine
                    .completions(request("to"))
                    .last()
                    .single()
                    .argumentHint,
            )
            val context = TerminalCompletionContext.resolve(request("tool "), commandSpecs = specs)
            assertEquals(TerminalPathArgumentKind.DIRECTORY, context.expectedPathKind)
            assertEquals(domain, context.expectedValueDomain)
        }

    @Test
    fun `option hints use display labels rather than opaque domain identifiers`() =
        runTest {
            val domain = TerminalCompletionValueDomain("host.internal.lookup-key")
            val specs =
                listOf(
                    TerminalCommandSpec(
                        "tool",
                        options =
                            listOf(
                                TerminalOptionSpec(listOf("--lookup"), requiresValue = true, valueDomain = domain),
                                TerminalOptionSpec(listOf("--named"), requiresValue = true, valueDomain = domain, valueName = "target"),
                                TerminalOptionSpec(listOf("--flag"), valueName = "unused"),
                            ),
                    ),
                )
            val engine = TerminalCompletionEngines.fromSources(emptyList(), commandSpecs = specs)
            val options = engine.completions(request("tool --")).last().associate { it.replacementText to it.argumentHint }
            assertEquals(mapOf("--lookup" to "<value>", "--named" to "<target>", "--flag" to ""), options)
            assertEquals(domain, TerminalCompletionContext.resolve(request("tool --lookup "), specs).expectedValueDomain)
        }

    @Test
    fun `bundled operand hints distinguish optional inputs from required operands`() =
        runTest {
            val engine = TerminalCompletionEngines.fromSources(emptyList())
            for ((line, command, hint) in listOf(
                Triple("push", "pushd", "[directory]"),
                Triple("l", "ls", "[path]"),
                Triple("ca", "cat", "[file]"),
                Triple("mk", "mkdir", "<directory>"),
                Triple("r", "rm", "<path>"),
                Triple("co", "code", "[path]"),
                Triple("git br", "branch", "[branch]"),
                Triple("git me", "merge", "[commit]"),
                Triple("git reb", "rebase", "[upstream] [branch]"),
                Triple("git res", "reset", "[commit or path]"),
                Triple("git sh", "show", "[object]"),
                Triple("git rest", "restore", "<pathspec>"),
                Triple("git r", "rm", "<pathspec>"),
                Triple("git rev", "revert", "<commit>"),
                Triple("npm ru", "run", "[script] [args]"),
                Triple("pnpm ru", "run", "[script] [args]"),
                Triple("yarn ru", "run", "<script> [args]"),
                Triple("bun ru", "run", "[script] [args]"),
                Triple("docker ru", "run", "<image> [command] [args]"),
                Triple("docker ex", "exec", "<container> <command> [args]"),
                Triple("docker bu", "build", "<context>"),
                Triple("docker im", "images", "[repository]"),
                Triple("docker pu", "pull", "<image>"),
                Triple("docker pu", "push", "<image>"),
                Triple("docker st", "stop", "<container>"),
                Triple("docker st", "start", "<container>"),
                Triple("docker re", "restart", "<container>"),
                Triple("docker r", "rm", "<container>"),
                Triple("docker r", "rmi", "<image>"),
                Triple("docker lo", "logs", "<container>"),
                Triple("docker in", "inspect", "<object>"),
                Triple("adb ins", "install", "<apk>"),
            )) {
                val candidate = engine.completions(request(line)).last().first { it.replacementText == command }
                assertEquals(hint, candidate.argumentHint, line)
                assertEquals(command, candidate.displayText)
            }
            for (command in listOf("cp", "mv")) {
                val candidate = engine.completions(request(command.take(1))).last().first { it.replacementText == command }
                assertEquals("", candidate.argumentHint, "A scalar path kind cannot express source and destination: $command")
            }
        }

    @Test
    fun `script names apply only to the script operand and never forwarded arguments`() {
        for (command in listOf("npm", "pnpm", "yarn", "bun")) {
            assertEquals(
                TerminalCompletionValueDomain.NPM_SCRIPT,
                TerminalCompletionContext.resolve(request("$command run ")).expectedValueDomain,
                command,
            )
            for (suffix in listOf("build ", "build first second ", "build -- ")) {
                val context = TerminalCompletionContext.resolve(request("$command run $suffix"))
                assertEquals(TerminalCompletionValueDomain.NONE, context.expectedValueDomain, "$command run $suffix")
                assertEquals("args", context.activePositionalArgument?.name)
            }
        }
    }

    @Test
    fun `explicit operand declarations retain path and value routing`() {
        for ((line, kind) in listOf(
            "cd " to TerminalPathArgumentKind.DIRECTORY,
            "ls first second " to TerminalPathArgumentKind.FILE_OR_DIRECTORY,
            "cat first second " to TerminalPathArgumentKind.FILE_OR_DIRECTORY,
            "mkdir first second " to TerminalPathArgumentKind.DIRECTORY,
            "rm first second " to TerminalPathArgumentKind.FILE_OR_DIRECTORY,
            "docker build " to TerminalPathArgumentKind.FILE_OR_DIRECTORY,
            "adb install " to TerminalPathArgumentKind.FILE,
        )) {
            assertEquals(kind, TerminalCompletionContext.resolve(request(line)).expectedPathKind, line)
        }
        for (line in listOf("git branch ", "git branch -d first second ", "git rebase main ")) {
            assertEquals(
                TerminalCompletionValueDomain.GIT_BRANCH,
                TerminalCompletionContext.resolve(request(line)).expectedValueDomain,
                line,
            )
        }
    }

    private fun request(line: String) = TerminalCompletionRequest(line, line.length, shellCapabilities = TerminalShellCapabilities.POSIX)
}

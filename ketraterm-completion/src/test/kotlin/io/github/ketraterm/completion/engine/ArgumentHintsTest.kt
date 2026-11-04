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
                Triple("c", "cd", "<directory>"),
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
            val flag = engine.completions(request("git commit --am")).last().first { it.replacementText == "--amend" }
            assertEquals("", flag.argumentHint)
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
                    commandSpecs = listOf(TerminalCommandSpec("tool", positionalArgumentPathKind = TerminalPathArgumentKind.DIRECTORY)),
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
                        option.valueName.isNotBlank() ||
                            option.valueDomain != TerminalCompletionValueDomain.NONE ||
                            option.valuePathKind != TerminalPathArgumentKind.NONE,
                        "${command.name} ${option.names}: missing argument metadata",
                    )
                }
                verify(command.subcommands)
            }
        }
        verify(TerminalCommandSpecs.defaults())
    }

    private fun request(line: String) = TerminalCompletionRequest(line, line.length, shellCapabilities = TerminalShellCapabilities.POSIX)
}

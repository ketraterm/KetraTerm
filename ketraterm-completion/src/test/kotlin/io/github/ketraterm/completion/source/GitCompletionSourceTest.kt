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
package io.github.ketraterm.completion.source

import io.github.ketraterm.completion.api.*
import io.github.ketraterm.completion.commandline.TerminalCommandLineTokenizer
import io.github.ketraterm.completion.model.TerminalCompletionDomainValue
import io.github.ketraterm.completion.model.TerminalCompletionValueDomain
import io.github.ketraterm.completion.model.TerminalGitCompletionSnapshot
import io.github.ketraterm.completion.testing.shellProvider
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GitCompletionSourceTest {
    private val snapshot =
        TerminalGitCompletionSnapshot(
            localBranches = listOf("main", "local-only", "feature/local").map(::TerminalCompletionDomainValue),
            remoteBranches = listOf("origin/release", "origin/feature", "upstream/other").map(::TerminalCompletionDomainValue),
            tags = listOf(TerminalCompletionDomainValue("v1")),
            remotes = listOf("origin", "upstream").map(::TerminalCompletionDomainValue),
            currentBranchName = "main",
        )
    private val source = TerminalCompletionSources.git("git") { _, _ -> snapshot }

    @Test
    fun `exhausted modes stop all sources history and native fallback before querying`() =
        runTest {
            var queries = 0
            val learning = TerminalCompletionLearningStore()
            val lines = listOf("git fetch --all ", "git push --all origin ", "git push --branches origin ", "git push --mirror origin ")
            for (line in lines) learning.recordCommandResult(line + "old", true, null, null, System.currentTimeMillis())
            val engine =
                TerminalCompletionEngines.fromSources(
                    listOf(
                        TerminalCompletionSourceEntry(
                            TerminalCompletionSource { _, _, _ ->
                                queries++
                                emptyList()
                            },
                        ),
                    ),
                    learningStore = learning,
                    shellProvider =
                        shellProvider { _, _, _ ->
                            queries++
                            emptyList()
                        },
                )
            for (line in lines) {
                assertFalse(TerminalCompletionContext.resolve(request(line)).isCompletionAllowed, line)
                assertTrue(engine.complete(request(line)).isEmpty(), line)
            }
            assertEquals(0, queries)
            assertTrue(engine.complete(request("git fetch --all --p")).any { it.replacementText == "--prune" })
        }

    @Test
    fun `multiple fetch offers remaining remotes while tags still permits push refs`() =
        runTest {
            assertEquals(listOf("upstream"), complete("git fetch --multiple origin ").map { it.replacementText })
            assertTrue(complete("git fetch --multiple origin upstream ").isEmpty())
            assertEquals(
                TerminalCompletionValueDomain.GIT_REMOTE,
                TerminalCompletionContext.resolve(request("git fetch --multiple origin u")).expectedValueDomain,
            )
            assertEquals(listOf("origin"), complete("git push --all or").map { it.replacementText })
            assertEquals(listOf("main"), complete("git push --tags origin ma").map { it.replacementText })
        }

    @Test
    fun `delete uses the selected remote tracking branches and preserves aliases`() =
        runTest {
            for (flag in listOf("--delete", "-d")) {
                assertEquals(listOf("release"), complete("git push $flag origin re").map { it.replacementText })
                assertTrue(complete("git push $flag origin local-").isEmpty())
                assertEquals(listOf("other"), complete("git push upstream $flag ot").map { it.replacementText })
            }
        }

    @Test
    fun `flag-looking values and post-terminator words do not change modes`() =
        runTest {
            assertEquals(listOf("origin"), complete("git -C --all fetch or").map { it.replacementText })
            for (line in listOf("git push -- origin --all ", "git fetch -- --all ")) {
                assertTrue(TerminalCompletionContext.resolve(request(line)).isCompletionAllowed, line)
            }
        }

    @Test
    fun `refspec edits replace only the selected component and retain force markers and suffixes`() =
        runTest {
            val sourceLine = "git push origin +ma:release"
            val sourceEdit = complete(sourceLine, sourceLine.indexOf(':')).single()
            assertEquals("git push origin +main:release", apply(sourceLine, sourceEdit))
            assertEquals(sourceLine.indexOf('+') + 1, sourceEdit.replacementStartOffset)
            assertEquals(sourceLine.indexOf(':'), sourceEdit.replacementEndOffset)

            val destinationLine = "git push origin +main:re"
            val destinationEdit = complete(destinationLine).single()
            assertEquals("git push origin +main:release", apply(destinationLine, destinationEdit))
            assertEquals(destinationLine.indexOf(':') + 1, destinationEdit.replacementStartOffset)

            val deletion = "git push origin :re"
            assertEquals("git push origin :release", apply(deletion, complete(deletion).single()))
            val fetch = "git fetch origin re:local-only"
            assertEquals("git fetch origin release:local-only", apply(fetch, complete(fetch, fetch.indexOf(':')).single()))
            val local = "git fetch origin release:lo"
            assertEquals(
                listOf("git fetch origin release:local-only", "git fetch origin release:feature/local"),
                complete(local).map { apply(local, it) },
            )
        }

    @Test
    fun `editing a component removes its stale suffix without replacing the other component from history`() =
        runTest {
            val line = "git push origin +mainx:release"
            val learning = TerminalCompletionLearningStore()
            learning.recordCommandResult("git push origin +main:other", true, null, null, System.currentTimeMillis())
            val engine =
                TerminalCompletionEngines.fromSources(
                    listOf(TerminalCompletionSourceEntry(source)),
                    learningStore = learning,
                )
            val candidates = engine.complete(request(line, line.indexOf('x')))
            assertEquals(1, candidates.size)
            assertEquals("git push origin +main:release", apply(line, candidates.single()))
        }

    @Test
    fun `qualified refspecs preserve namespaces in either direction`() =
        runTest {
            assertTrue(complete("git switch refs/remotes/").isEmpty())
            val push = "git push origin refs/heads/main:refs/heads/re"
            assertEquals("git push origin refs/heads/main:refs/heads/release", apply(push, complete(push).single()))
            val fetch = "git fetch origin refs/heads/release:refs/remotes/origin/fe"
            assertEquals("git fetch origin refs/heads/release:refs/remotes/origin/feature", apply(fetch, complete(fetch).single()))
        }

    @Test
    fun `quoted refspecs preserve both components and following arguments across shells`() =
        runTest {
            for (shell in listOf(TerminalShellCapabilities.POSIX, TerminalShellCapabilities.POWERSHELL)) {
                val line = "git push origin '+main:re' --verbose"
                val edit = complete(line, line.indexOf("' --verbose"), shell).single()
                assertEquals("git push origin '+main:release' --verbose", apply(line, edit))
                val escaped =
                    if (shell == TerminalShellCapabilities.POSIX) {
                        "git push origin +o\\'hare:re"
                    } else {
                        "git push origin '+o''hare:re'"
                    }
                val candidate = complete(escaped, escaped.length, shell).single()
                val edited = apply(escaped, candidate)
                assertEquals(
                    "+o'hare:release",
                    TerminalCommandLineTokenizer
                        .parse(edited, edited.length, shell.syntax)
                        .tokens
                        .last()
                        .text,
                )
            }
        }

    @Test
    fun `patterns and malformed refspecs are not rewritten and candidate limits remain bounded`() =
        runTest {
            for (line in listOf("git push origin +*:re", "git fetch origin ^feature", "git push origin main:one:two")) {
                assertFalse(TerminalCompletionContext.resolve(request(line)).isCompletionAllowed, line)
                assertTrue(complete(line).isEmpty(), line)
            }
            val request = request("git push origin ")
            assertEquals(1, source.complete(request, TerminalCompletionContext.resolve(request), 1).size)
        }

    private suspend fun complete(
        line: String,
        cursor: Int = line.length,
        shell: TerminalShellCapabilities = TerminalShellCapabilities.POSIX,
    ): List<TerminalCompletionCandidate> {
        val request = request(line, cursor, shell)
        return source.complete(request, TerminalCompletionContext.resolve(request), 20)
    }

    private fun request(
        line: String,
        cursor: Int = line.length,
        shell: TerminalShellCapabilities = TerminalShellCapabilities.POSIX,
    ) = TerminalCompletionRequest(line, cursor, shellCapabilities = shell)

    private fun apply(
        line: String,
        candidate: TerminalCompletionCandidate,
    ): String = line.replaceRange(candidate.replacementStartOffset, candidate.replacementEndOffset, candidate.replacementText)
}

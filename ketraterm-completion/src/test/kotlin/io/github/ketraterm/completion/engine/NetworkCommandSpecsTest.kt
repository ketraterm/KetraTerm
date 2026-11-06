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
import io.github.ketraterm.completion.model.TerminalPathArgumentKind
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NetworkCommandSpecsTest {
    @Test
    fun `network commands describe operands and option values without inserting hints`() =
        runTest {
            val engine = TerminalCompletionEngines.fromSources(emptyList())
            for ((line, text, hint) in listOf(
                Triple("cur", "curl", "<url>"),
                Triple("ss", "ssh", "<destination> [command] [args]"),
                Triple("curl --req", "--request", "<method>"),
                Triple("curl --hea", "--header", "<header>"),
                Triple("curl --out", "--output", "<file>"),
                Triple("ssh -", "-p", "<port>"),
                Triple("ssh -", "-i", "<identity-file>"),
                Triple("ssh -", "-J", "<jump-hosts>"),
            )) {
                val request = request(line)
                val candidate = engine.completions(request).last().first { it.replacementText == text }
                assertEquals(hint, candidate.argumentHint, line)
                assertEquals(text, candidate.displayText)
                assertEquals(
                    line.take(line.lastIndexOf(' ') + 1) + text,
                    request.commandLineAfterCandidate(candidate),
                    line,
                )
            }
        }

    @Test
    fun `short option case distinguishes help headers diagnostics and versions`() {
        for ((line, names) in listOf(
            "curl -H " to listOf("--header", "-H"),
            "curl -h " to null,
            "curl -I " to null,
            "curl -i " to null,
            "ssh -p " to listOf("-p"),
            "ssh -i " to listOf("-i"),
            "ssh -V " to null,
            "ssh -v " to null,
        )) {
            val context = TerminalCompletionContext.resolve(request(line))
            assertEquals(names, context.activeOption?.names, line)
            assertFalse(context.hasUnrecognizedOptions, line)
        }
    }

    @Test
    fun `request method values preserve attached replacement offsets in both shell dialects`() =
        runTest {
            val engine = TerminalCompletionEngines.fromSources(emptyList())
            for (shell in listOf(TerminalShellCapabilities.POSIX, TerminalShellCapabilities.POWERSHELL)) {
                for (line in listOf("curl --request PO", "curl --request=PO", "curl -H \"X-Test: a b\" --request=PO")) {
                    val request = request(line, shell)
                    val result = engine.completions(request).last().first { it.replacementText == "POST" }
                    assertEquals(line.length - 2, result.replacementStartOffset)
                    assertEquals(line.length, result.replacementEndOffset)
                    assertEquals(line.dropLast(2) + "POST", request.commandLineAfterCandidate(result))
                    assertEquals("", result.argumentHint)
                    if (line.contains("-H")) {
                        assertEquals(listOf("X-Test: a b"), TerminalCompletionContext.resolve(request).precedingOptionValues("--header"))
                    }
                }
            }
        }

    @Test
    fun `URLs and remote SSH operands never query the local filesystem`() =
        runTest {
            val paths = TerminalCompletionSources.path { error("Remote operands must not be resolved as local paths: $it") }
            val engine =
                TerminalCompletionEngines.fromSources(
                    listOf(TerminalCompletionSourceEntry(paths)),
                )
            for (line in listOf("curl https://example.test/api/", "ssh user@host /usr/", "ssh user@host command /remote/")) {
                val request = request(line).copy(workingDirectoryUri = "file:///project")
                assertEquals(TerminalPathArgumentKind.NONE, TerminalCompletionContext.resolve(request).expectedPathKind)
                assertTrue(engine.completions(request).last().isEmpty(), line)
            }
        }

    @Test
    fun `local configuration and identity files use shell-aware replacement quoting`() =
        runTest {
            val paths = TerminalCompletionSources.path { listOf(TerminalFileEntry("client config.txt", false)) }
            val engine =
                TerminalCompletionEngines.fromSources(
                    listOf(TerminalCompletionSourceEntry(paths)),
                )
            for (shell in listOf(TerminalShellCapabilities.POSIX, TerminalShellCapabilities.POWERSHELL)) {
                for (line in listOf("curl --config cl", "curl --output cl", "ssh -i cl", "ssh -F cl")) {
                    val request = request(line, shell).copy(workingDirectoryUri = "file:///project")
                    assertEquals(TerminalPathArgumentKind.FILE, TerminalCompletionContext.resolve(request).expectedPathKind)
                    val candidate = engine.completions(request).last().single { it.kind == TerminalCompletionCandidateKind.PATH }
                    assertEquals("client config.txt", candidate.displayText)
                    val escaped = if (shell == TerminalShellCapabilities.POSIX) "client\\ config.txt" else "'client config.txt'"
                    assertEquals(line.dropLast(2) + escaped, request.commandLineAfterCandidate(candidate))
                }
            }
        }

    @Test
    fun `PowerShell curl alias parameters remain native authoritative`() =
        runTest {
            val line = "curl -M"
            var queries = 0
            val engine =
                TerminalCompletionEngines.fromSources(
                    emptyList(),
                    shellProvider =
                        TerminalCompletionShellProvider { _, _, _ ->
                            queries++
                            TerminalCompletionShellResult(
                                listOf(
                                    TerminalCompletionCandidate(
                                        replacementText = "-Method",
                                        replacementStartOffset = 5,
                                        replacementEndOffset = line.length,
                                        source = "powershell",
                                        kind = TerminalCompletionCandidateKind.OPTION,
                                    ),
                                ),
                            )
                        },
                )
            val request = request(line, TerminalShellCapabilities.POWERSHELL)
            val result = engine.completions(request).last().single()
            assertEquals("-Method", result.replacementText)
            assertEquals("curl -Method", request.commandLineAfterCandidate(result))
            assertEquals("", result.argumentHint)
            assertEquals(1, queries)
        }

    private fun request(
        line: String,
        shell: TerminalShellCapabilities = TerminalShellCapabilities.POSIX,
    ) = TerminalCompletionRequest(line, line.length, shellCapabilities = shell)
}

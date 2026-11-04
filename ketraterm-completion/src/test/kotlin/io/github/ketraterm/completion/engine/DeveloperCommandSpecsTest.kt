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
import io.github.ketraterm.completion.model.TerminalCommandSpecs
import io.github.ketraterm.completion.model.TerminalPathArgumentKind
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class DeveloperCommandSpecsTest {
    @Test
    fun `developer commands and nested operations carry useful operand hints`() =
        runTest {
            val engine = TerminalCompletionEngines.fromSources(emptyList())
            for ((line, text, hint) in listOf(
                Triple("dotnet bu", "build", "[project]"),
                Triple("dotnet ru", "run", "[args]"),
                Triple("dotnet ne", "new", "[template]"),
                Triple("dotnet sln a", "add", "<project>"),
                Triple("dotnet new l", "list", ""),
                Triple("dotnet build --conf", "--configuration", "<configuration>"),
                Triple("dotnet publish --out", "--output", "<directory>"),
                Triple("mvn --act", "--activate-profiles", "<profiles>"),
                Triple("mvn --def", "--define", "<property=value>"),
                Triple("mvn --thr", "--threads", "<threads>"),
                Triple("py", "python", "[script] [args]"),
                Triple("python -", "-m", "<module>"),
                Triple("python -", "-c", "<code>"),
            )) {
                val candidate = engine.completions(request(line)).last().first { it.replacementText == text }
                assertEquals(hint, candidate.argumentHint, line)
                assertEquals(text, candidate.displayText)
            }
        }

    @Test
    fun `Maven wrappers resolve reactor phases and retain options between phases`() =
        runTest {
            val engine = TerminalCompletionEngines.fromSources(emptyList())
            for (name in listOf("mvn", "mvnw", "./mvnw", "mvnw.cmd", ".\\mvnw.cmd")) {
                val shell = if ('\\' in name) TerminalShellCapabilities.POWERSHELL else TerminalShellCapabilities.POSIX
                val request = request("$name clean --offline ").copy(shellCapabilities = shell)
                val context = TerminalCompletionContext.resolve(request)
                assertEquals(listOf("mvn", "clean"), context.commandPath.map { it.name }, name)
                val candidates = engine.completions(request).last()
                assertTrue(candidates.any { it.replacementText == "verify" }, name)
                assertFalse(candidates.any { it.replacementText == "clean" }, name)
            }
            val project = TerminalCompletionContext.resolve(request("mvn --projects api,core verify "))
            assertEquals(listOf("api,core"), project.precedingOptionValues("--projects"))
            assertEquals(listOf("mvn", "verify"), project.commandPath.map { it.name })
        }

    @Test
    fun `directory file and nonpath options keep their own argument routing`() {
        for ((line, kind) in listOf(
            "dotnet build --output " to TerminalPathArgumentKind.DIRECTORY,
            "dotnet run --project " to TerminalPathArgumentKind.FILE_OR_DIRECTORY,
            "dotnet restore --packages " to TerminalPathArgumentKind.DIRECTORY,
            "mvn -f " to TerminalPathArgumentKind.FILE_OR_DIRECTORY,
            "mvn -s " to TerminalPathArgumentKind.FILE,
            "mvn --activate-profiles " to TerminalPathArgumentKind.NONE,
            "python -c " to TerminalPathArgumentKind.NONE,
            "python -m " to TerminalPathArgumentKind.NONE,
        )) {
            assertEquals(kind, TerminalCompletionContext.resolve(request(line)).expectedPathKind, line)
        }
        val forwarded = TerminalCompletionContext.resolve(request("dotnet run -- --configuration Release "))
        assertEquals("args", forwarded.activePositionalArgument?.name)
        assertEquals(TerminalPathArgumentKind.NONE, forwarded.expectedPathKind)
        assertEquals(listOf("--configuration", "Release"), forwarded.precedingPositionalArguments)
        assertNull(forwarded.activeOption)
    }

    @Test
    fun `configuration and standard module values are completed without extra discovery`() =
        runTest {
            val engine = TerminalCompletionEngines.fromSources(emptyList())
            assertEquals(
                "Release",
                engine
                    .completions(request("dotnet build --configuration R"))
                    .last()
                    .single()
                    .replacementText,
            )
            assertEquals(
                "venv",
                engine
                    .completions(request("python3 -m v"))
                    .last()
                    .single()
                    .replacementText,
            )
            val alias = TerminalCompletionContext.resolve(request("python3 -c \"print('ok')\" "))
            assertEquals("python", alias.command?.name)
            assertEquals(listOf("print('ok')"), alias.precedingOptionValues("-c"))
        }

    @Test
    fun `expanded catalog cannot advertise unavailable commands when discovery is authoritative`() =
        runTest {
            var queries = 0
            val engine =
                TerminalCompletionEngines.fromSources(
                    emptyList(),
                    shellProvider =
                        TerminalCompletionShellProvider { _, _, _ ->
                            queries++
                            TerminalCompletionShellResult(emptyList())
                        },
                )
            for (shell in listOf(TerminalShellCapabilities.POSIX, TerminalShellCapabilities.POWERSHELL)) {
                for (prefix in listOf("cur", "ss", "mv", "dotn", "py")) {
                    assertTrue(engine.completions(request(prefix).copy(shellCapabilities = shell)).last().isEmpty(), prefix)
                }
            }
            assertEquals(10, queries)
        }

    @Test
    fun `verified command aliases receive catalog hints while preserving native edits`() =
        runTest {
            for ((name, hint) in listOf("curl" to "<url>", "python3" to "[script] [args]", "./mvnw" to "")) {
                var queries = 0
                val request = request(name.dropLast(1))
                val native =
                    TerminalCompletionCandidate(
                        name,
                        0,
                        request.commandLine.length,
                        "shell",
                        TerminalCompletionCandidateKind.COMMAND,
                    )
                val engine =
                    TerminalCompletionEngines.fromSources(
                        emptyList(),
                        shellProvider =
                            TerminalCompletionShellProvider { _, _, _ ->
                                queries++
                                TerminalCompletionShellResult(listOf(native))
                            },
                    )
                val candidate = engine.completions(request).last().single()
                assertEquals(name, candidate.replacementText)
                assertEquals(hint, candidate.argumentHint, name)
                assertTrue(candidate.detail.isNotBlank(), name)
                assertEquals(1, queries)
            }
        }

    @Test
    fun `new catalog descriptions and operand labels use literal translation keys`() =
        runTest {
            val translations =
                mapOf(
                    "spec.curl.description" to "Transferencia",
                    "spec.curl.option.--request.valueName" to "metodo",
                    "spec.ssh.argument.destination.name" to "destino",
                    "spec.dotnet.build.argument.project.name" to "proyecto",
                    "spec.python.option.-m.valueName" to "modulo",
                )
            val specs = TerminalCommandSpecs.defaults { key, english -> translations[key] ?: english }
            val engine = TerminalCompletionEngines.fromSources(emptyList(), commandSpecs = specs)
            for ((line, name, hint) in listOf(
                Triple("curl --req", "--request", "<metodo>"),
                Triple("ss", "ssh", "<destino> [command] [args]"),
                Triple("dotnet bu", "build", "[proyecto]"),
                Triple("python -", "-m", "<modulo>"),
            )) {
                assertEquals(
                    hint,
                    engine
                        .completions(request(line))
                        .last()
                        .first { it.replacementText == name }
                        .argumentHint,
                    line,
                )
            }
            assertEquals(
                "Transferencia",
                engine
                    .completions(request("cur"))
                    .last()
                    .first { it.replacementText == "curl" }
                    .detail,
            )
        }

    private fun request(line: String) = TerminalCompletionRequest(line, line.length, shellCapabilities = TerminalShellCapabilities.POSIX)
}

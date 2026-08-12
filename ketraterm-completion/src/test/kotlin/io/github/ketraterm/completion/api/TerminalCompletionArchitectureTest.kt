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
package io.github.ketraterm.completion.api

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.test.Test
import kotlin.test.assertTrue

/** Enforces module boundaries without mirroring the completion public API. */
internal class TerminalCompletionArchitectureTest {
    @Test
    fun `implementation packages expose no public top-level declarations`() {
        val violations =
            IMPLEMENTATION_PACKAGES.flatMap { packageName ->
                kotlinFiles(completionMainRoot.resolve(packageName)).flatMap(::publicTopLevelDeclarations)
            }

        assertTrue(
            actual = violations.isEmpty(),
            message = violations.joinToString(prefix = "Implementation declarations must be internal:\n", separator = "\n"),
        )
    }

    @Test
    fun `public completion contracts have KDoc`() {
        val violations =
            documentedCompletionRoots.flatMap { sourceRoot ->
                kotlinFiles(sourceRoot).flatMap { file ->
                    val lines = file.readSourceLines()
                    lines.mapIndexedNotNull { index, line ->
                        val isPublicContract =
                            PUBLIC_TOP_LEVEL_DECLARATION.matches(line) ||
                                PUBLIC_MEMBER_FUNCTION.matches(line)
                        if (isPublicContract && !lines.hasLeadingKDoc(index)) {
                            "${file.relativeToRepository()}:${index + 1}: $line"
                        } else {
                            null
                        }
                    }
                }
            }

        assertTrue(
            actual = violations.isEmpty(),
            message = violations.joinToString(prefix = "Public completion contracts require KDoc:\n", separator = "\n"),
        )
    }

    @Test
    fun `external modules import only completion api or model packages`() {
        val violations =
            externalModuleSourceRoots.flatMap { sourceRoot ->
                kotlinFiles(sourceRoot).flatMap { file ->
                    file
                        .readSourceLines()
                        .mapIndexedNotNull { index, line ->
                            if (IMPLEMENTATION_IMPORT.matches(line)) {
                                "${file.relativeToRepository()}:${index + 1}: $line"
                            } else {
                                null
                            }
                        }
                }
            }

        assertTrue(
            actual = violations.isEmpty(),
            message = violations.joinToString(prefix = "External modules must not import completion internals:\n", separator = "\n"),
        )
    }

    private fun publicTopLevelDeclarations(file: Path): List<String> =
        file
            .readSourceLines()
            .mapIndexedNotNull { index, line ->
                if (PUBLIC_TOP_LEVEL_DECLARATION.matches(line)) {
                    "${file.relativeToRepository()}:${index + 1}: $line"
                } else {
                    null
                }
            }

    private fun kotlinFiles(root: Path): List<Path> {
        if (!Files.isDirectory(root)) return emptyList()
        return Files
            .walk(root)
            .use { paths ->
                paths
                    .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".kt") }
                    .sorted()
                    .toList()
            }
    }

    private fun Path.readSourceLines(): List<String> = Files.readAllLines(this)

    private fun List<String>.hasLeadingKDoc(declarationIndex: Int): Boolean {
        var index = declarationIndex - 1
        while (index >= 0) {
            val line = this[index].trim()
            if (line.isEmpty() || line.startsWith("@")) {
                index--
            } else {
                break
            }
        }
        if (index < 0) return false
        val lastDocumentationLine = this[index].trim()
        if (lastDocumentationLine.startsWith("/**") && lastDocumentationLine.endsWith("*/")) return true
        if (lastDocumentationLine != "*/") return false
        while (index >= 0) {
            when (this[index].trim()) {
                "/**" -> return true
                "/*" -> return false
            }
            index--
        }
        return false
    }

    private fun Path.relativeToRepository(): String =
        repositoryRoot
            .relativize(this)
            .invariantSeparatorsPathString

    private companion object {
        private val workingDirectory: Path = Paths.get("").toAbsolutePath()
        private val repositoryRoot: Path =
            if (Files.isDirectory(workingDirectory.resolve("ketraterm-completion"))) {
                workingDirectory
            } else {
                workingDirectory.parent
            }
        private val completionMainRoot: Path =
            repositoryRoot.resolve("ketraterm-completion/src/main/kotlin/io/github/ketraterm/completion")
        private val documentedCompletionRoots =
            listOf(
                completionMainRoot.resolve("api"),
                completionMainRoot.resolve("model"),
                repositoryRoot.resolve(
                    "ketraterm-completion-host/src/main/kotlin/io/github/ketraterm/completion/host",
                ),
                repositoryRoot.resolve(
                    "ketraterm-completion-persistence/src/main/kotlin/io/github/ketraterm/completion/persistence",
                ),
            )
        private val IMPLEMENTATION_PACKAGES =
            listOf(
                "commandline",
                "engine",
                "history",
                "internal",
                "ranking",
                "source",
                "spec",
                "stats",
            )
        private val externalModuleSourceRoots: List<Path> =
            Files
                .list(repositoryRoot)
                .use { paths ->
                    paths
                        .filter(Files::isDirectory)
                        .filter { path -> path.fileName.toString().startsWith("ketraterm-") }
                        .filter { path -> path.fileName.toString() != "ketraterm-completion" }
                        .map { path -> path.resolve("src") }
                        .filter(Files::isDirectory)
                        .sorted()
                        .toList()
                }
        private val PUBLIC_TOP_LEVEL_DECLARATION =
            Regex("""^(data class|enum class|fun interface|sealed interface|class|fun|interface|object)\s+([A-Za-z0-9_]+).*""")
        private val PUBLIC_MEMBER_FUNCTION = Regex("""^\s+(?!private |internal )fun\s+([A-Za-z0-9_]+)\(.*""")
        private val IMPLEMENTATION_IMPORT =
            Regex("""import io\.github\.ketraterm\.completion\.(commandline|engine|history|internal|ranking|source|spec|stats)(\.|$).*""")
    }
}

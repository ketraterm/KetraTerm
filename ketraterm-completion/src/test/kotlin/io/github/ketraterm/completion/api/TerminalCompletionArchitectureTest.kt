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
    fun `public model package contains only public contracts`() {
        val violations =
            kotlinFiles(completionMainRoot.resolve("model")).flatMap { file ->
                file
                    .readSourceLines()
                    .mapIndexedNotNull { index, line ->
                        if (line.startsWith("internal ")) "${file.relativeToRepository()}:${index + 1}: $line" else null
                    }
            }

        assertTrue(
            actual = violations.isEmpty(),
            message = violations.joinToString(prefix = "Internal implementation types do not belong in model:\n", separator = "\n"),
        )
    }

    @Test
    fun `external modules import only completion api or model packages`() {
        val violations =
            EXTERNAL_MODULES.flatMap { moduleName ->
                kotlinFiles(repositoryRoot.resolve(moduleName)).flatMap { file ->
                    file
                        .readSourceLines()
                        .mapIndexedNotNull { index, line ->
                            if (IMPLEMENTATION_IMPORT.matches(line)) "${file.relativeToRepository()}:${index + 1}: $line" else null
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

        private val IMPLEMENTATION_PACKAGES =
            listOf(
                "commandline",
                "engine",
                "internal",
                "ranking",
                "source",
                "spec",
                "stats",
            )
        private val EXTERNAL_MODULES =
            listOf(
                "ketraterm-app",
                "ketraterm-ui-swing",
                "ketraterm-workspace",
            )

        private val PUBLIC_TOP_LEVEL_DECLARATION =
            Regex("""^(class|data class|enum class|fun|fun interface|interface|object|sealed interface)\s+.*""")
        private val IMPLEMENTATION_IMPORT =
            Regex("""import io\.github\.ketraterm\.completion\.(commandline|engine|internal|ranking|source|spec|stats)(\.|$).*""")
    }
}

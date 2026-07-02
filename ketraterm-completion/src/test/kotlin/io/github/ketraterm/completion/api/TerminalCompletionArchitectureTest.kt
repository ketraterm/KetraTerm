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
                        when {
                            line.startsWith("internal ") -> "${file.relativeToRepository()}:${index + 1}: $line"
                            PUBLIC_TOP_LEVEL_DECLARATION.matches(line) && line.declaredName() !in PUBLIC_MODEL_DECLARATIONS ->
                                "${file.relativeToRepository()}:${index + 1}: $line"
                            else -> null
                        }
                    }
            }

        assertTrue(
            actual = violations.isEmpty(),
            message = violations.joinToString(prefix = "Only durable public contracts belong in model:\n", separator = "\n"),
        )
    }

    @Test
    fun `public api package contains only host-facing contracts`() {
        val violations =
            kotlinFiles(completionMainRoot.resolve("api")).flatMap { file ->
                file
                    .readSourceLines()
                    .mapIndexedNotNull { index, line ->
                        when {
                            line.startsWith("internal ") -> "${file.relativeToRepository()}:${index + 1}: $line"
                            PUBLIC_TOP_LEVEL_DECLARATION.matches(line) && line.declaredName() !in PUBLIC_API_DECLARATIONS ->
                                "${file.relativeToRepository()}:${index + 1}: $line"
                            else -> null
                        }
                    }
            }

        assertTrue(
            actual = violations.isEmpty(),
            message = violations.joinToString(prefix = "Only host-facing contracts belong in api:\n", separator = "\n"),
        )
    }

    @Test
    fun `public source and engine factories expose only reviewed functions`() {
        val violations =
            listOf(
                completionMainRoot.resolve("api/TerminalCompletionSources.kt") to PUBLIC_COMPLETION_SOURCE_FACTORIES,
                completionMainRoot.resolve("api/TerminalCompletionEngines.kt") to PUBLIC_COMPLETION_ENGINE_FACTORIES,
            ).flatMap { (file, allowedFunctions) ->
                file
                    .readSourceLines()
                    .mapIndexedNotNull { index, line ->
                        val functionName = PUBLIC_MEMBER_FUNCTION.find(line)?.groupValues?.get(1)
                        if (functionName != null && functionName !in allowedFunctions) {
                            "${file.relativeToRepository()}:${index + 1}: $line"
                        } else {
                            null
                        }
                    }
            }

        assertTrue(
            actual = violations.isEmpty(),
            message = violations.joinToString(prefix = "Unreviewed completion factory functions:\n", separator = "\n"),
        )
    }

    @Test
    fun `public model helper functions expose only reviewed contracts`() {
        val violations =
            kotlinFiles(completionMainRoot.resolve("model")).flatMap { file ->
                val allowedFunctions = PUBLIC_MODEL_MEMBER_FUNCTIONS[file.relativeToCompletionRoot()] ?: emptySet()
                file
                    .readSourceLines()
                    .mapIndexedNotNull { index, line ->
                        val functionName = PUBLIC_MEMBER_FUNCTION.find(line)?.groupValues?.get(1)
                        if (functionName != null && functionName !in allowedFunctions) {
                            "${file.relativeToRepository()}:${index + 1}: $line"
                        } else {
                            null
                        }
                    }
            }

        assertTrue(
            actual = violations.isEmpty(),
            message = violations.joinToString(prefix = "Unreviewed public model helper functions:\n", separator = "\n"),
        )
    }

    @Test
    fun `public api member functions expose only reviewed contracts`() {
        val violations =
            kotlinFiles(completionMainRoot.resolve("api")).flatMap { file ->
                val allowedFunctions = PUBLIC_API_MEMBER_FUNCTIONS[file.relativeToCompletionRoot()] ?: emptySet()
                file
                    .readSourceLines()
                    .mapIndexedNotNull { index, line ->
                        val functionName = PUBLIC_MEMBER_FUNCTION.find(line)?.groupValues?.get(1)
                        if (functionName != null && functionName !in allowedFunctions) {
                            "${file.relativeToRepository()}:${index + 1}: $line"
                        } else {
                            null
                        }
                    }
            }

        assertTrue(
            actual = violations.isEmpty(),
            message = violations.joinToString(prefix = "Unreviewed public api member functions:\n", separator = "\n"),
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

    private fun Path.relativeToCompletionRoot(): String =
        completionMainRoot
            .relativize(this)
            .invariantSeparatorsPathString

    private fun String.declaredName(): String? =
        PUBLIC_TOP_LEVEL_DECLARATION
            .find(this)
            ?.groupValues
            ?.get(2)

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
                "ketraterm-intellij-plugin",
                "ketraterm-ui-swing",
                "ketraterm-workspace",
            )
        private val PUBLIC_MODEL_DECLARATIONS =
            setOf(
                "TerminalCommandCompletionStats",
                "TerminalCommandCompletionStatsSnapshot",
                "TerminalCommandCompletionStatsSnapshotCodec",
                "TerminalCommandLineShape",
                "TerminalCommandShapeStats",
                "TerminalCommandSpec",
                "TerminalCommandSpecs",
                "TerminalCompletionFeedbackContext",
                "TerminalCompletionFeedbackKind",
                "TerminalCompletionFeedbackStats",
                "TerminalCompletionTokenPosition",
                "TerminalOptionSpec",
            )
        private val PUBLIC_API_DECLARATIONS =
            setOf(
                "TerminalCommandStatsCompletionSource",
                "TerminalCompletionCandidate",
                "TerminalCompletionCandidateKind",
                "TerminalCompletionEngine",
                "TerminalCompletionEngines",
                "TerminalCompletionRequest",
                "TerminalCompletionSource",
                "TerminalCompletionSourceEntry",
                "TerminalCompletionSources",
                "TerminalSessionMruCompletionSource",
                "TerminalCompletionTriggerEvaluator",
            )
        private val PUBLIC_COMPLETION_SOURCE_FACTORIES =
            setOf(
                "commandStats",
                "feedbackAware",
                "fromSpecs",
                "sessionMru",
            )
        private val PUBLIC_COMPLETION_ENGINE_FACTORIES =
            setOf(
                "fromSources",
            )
        private val PUBLIC_MODEL_MEMBER_FUNCTIONS =
            mapOf(
                "model/TerminalCommandCompletionStatsSnapshotCodec.kt" to setOf("currentFileName", "decode", "encode"),
                "model/TerminalCommandSpecs.kt" to
                    setOf("defaults", "docker", "git", "gradle", "npm", "cargo", "kubectl", "gh", "pip", "go", "aws", "ketra"),
                "model/TerminalCompletionFeedbackStats.kt" to setOf("fromCandidateKind"),
            )
        private val PUBLIC_API_MEMBER_FUNCTIONS =
            mapOf(
                "api/TerminalCommandStatsCompletionSource.kt" to
                    setOf(
                        "replaceSnapshot",
                        "snapshot",
                        "shapeSnapshot",
                        "feedbackSnapshot",
                        "snapshotAll",
                        "recordCommandResult",
                        "recordSuggestionFeedback",
                    ),
                "api/TerminalCompletionEngine.kt" to setOf("complete"),
                "api/TerminalCompletionEngines.kt" to setOf("fromSources"),
                "api/TerminalCompletionSource.kt" to setOf("complete"),
                "api/TerminalCompletionSources.kt" to setOf("commandStats", "feedbackAware", "fromSpecs", "sessionMru"),
                "api/TerminalSessionMruCompletionSource.kt" to setOf("recordSuccessfulCommand", "clear"),
                "api/TerminalCompletionTriggerEvaluator.kt" to setOf("shouldTrigger", "isLiveTrigger"),
            )

        private val PUBLIC_TOP_LEVEL_DECLARATION =
            Regex("""^(data class|enum class|fun interface|sealed interface|class|fun|interface|object)\s+([A-Za-z0-9_]+).*""")
        private val PUBLIC_MEMBER_FUNCTION = Regex("""^\s+(?!private |internal )fun\s+([A-Za-z0-9_]+)\(.*""")
        private val IMPLEMENTATION_IMPORT =
            Regex("""import io\.github\.ketraterm\.completion\.(commandline|engine|internal|ranking|source|spec|stats)(\.|$).*""")
    }
}

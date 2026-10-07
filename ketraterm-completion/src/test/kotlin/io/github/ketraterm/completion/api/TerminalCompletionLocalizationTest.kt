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

import io.github.ketraterm.completion.commandline.resolveCompletionContext
import io.github.ketraterm.completion.model.TerminalCommandSpecs
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.StringReader
import java.util.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TerminalCompletionLocalizationTest {
    @Test
    fun `partial UTF8 bundles format complete messages and retain English fallback`() {
        val properties = "completion.directory=répertoire { d'utilisateur\ncompletion.gradleTask=Tâche {0} de Gradle"
        val messages =
            TerminalCompletionMessages.forLocale(
                Locale.FRANCE,
                PropertyResourceBundle(ByteArrayInputStream(properties.toByteArray(Charsets.UTF_8))),
            )

        assertEquals("répertoire { d'utilisateur", messages.message("completion.directory"))
        assertEquals("file", messages.message("completion.file"))
        assertEquals("Tâche :app:run de Gradle", messages.message("completion.gradleTask", ":app:run"))
        assertEquals("directory", TerminalCompletionMessages.forLocale(Locale.JAPAN).message("completion.directory"))
    }

    @Test
    fun `catalog message keys cover the bundle and preserve canonical completion behavior`() =
        runBlocking {
            val keys = LinkedHashSet<String>()
            val localized =
                TerminalCommandSpecs.defaults { key, _ ->
                    assertTrue(keys.add(key), "Duplicate catalog key: $key")
                    "locale:$key"
                }
            val bundle = ResourceBundle.getBundle("io.github.ketraterm.completion.model.CommandSpecMessages", Locale.ROOT)
            assertEquals(bundle.keySet(), keys)
            assertEquals(TerminalCommandSpecs.defaults(), TerminalCommandSpecs.defaults(Locale.ROOT))
            val partialBundle = PropertyResourceBundle(StringReader("spec.git.description=Contrôle de version"))
            val partial = TerminalCommandSpecs.defaults(partialBundle)
            assertEquals("Contrôle de version", partial.single { it.name == "git" }.description)
            assertEquals("change directory", partial.single { it.name == "cd" }.description)

            val englishEngine = TerminalCompletionEngines.fromSources(emptyList())
            val localizedEngine = TerminalCompletionEngines.fromSources(emptyList(), localized)
            for (command in listOf("g", "git st", "git status --s", "kubectl get p", "gradle --console=")) {
                val request = request(command)
                val english = englishEngine.completions(request).last()
                val translated = localizedEngine.completions(request).last()
                assertTrue(english.isNotEmpty(), command)
                assertEquals(english.map { it.copy(detail = "") }, translated.map { it.copy(detail = "") }, command)
                assertTrue(translated.all { it.detail.startsWith("locale:") }, command)
            }
            val englishKubectl = TerminalCommandSpecs.defaults().single { it.name == "kubectl" }
            val localizedKubectl = localized.single { it.name == "kubectl" }
            assertEquals(
                "resource",
                englishKubectl.subcommands
                    .single { it.name == "get" }
                    .positionalArguments
                    .single()
                    .name,
            )
            assertEquals(
                "locale:spec.kubectl.get.argument.resource.name",
                localizedKubectl.subcommands
                    .single { it.name == "get" }
                    .positionalArguments
                    .single()
                    .name,
            )
        }

    @Test
    fun `localized source descriptions preserve replacements source identity and scores`() =
        runBlocking {
            val messages =
                TerminalCompletionMessages.forLocale(
                    Locale.FRANCE,
                    PropertyResourceBundle(
                        StringReader(
                            """
                            completion.directory=répertoire
                            completion.file=fichier
                            completion.projectDirectory=répertoire du projet
                            completion.projectFile=fichier du projet
                            completion.gradleTask=Tâche {0} de Gradle
                            """.trimIndent(),
                        ),
                    ),
                )
            val fileSystem =
                TerminalFileSystemProvider {
                    listOf(TerminalFileEntry("new.txt", false), TerminalFileEntry("notes", true))
                }
            assertLocalizedSource(
                TerminalCompletionSources.path(fileSystem),
                TerminalCompletionSources.path(messages, fileSystem),
                request("cat n"),
                mapOf("new.txt" to "fichier", "notes/" to "répertoire"),
            )
            val fuzzyPaths =
                TerminalFuzzyPathProvider { _, _ ->
                    listOf(
                        TerminalFuzzyPathEntry("notes/new.txt", false),
                        TerminalFuzzyPathEntry("notes", true),
                        TerminalFuzzyPathEntry("host.txt", false, "Description du fournisseur"),
                    )
                }
            assertLocalizedSource(
                TerminalCompletionSources.fuzzyPath("project", fuzzyPaths),
                TerminalCompletionSources.fuzzyPath(messages, "project", fuzzyPaths),
                request("cat n"),
                mapOf(
                    "notes/new.txt" to "fichier du projet",
                    "notes/" to "répertoire du projet",
                    "host.txt" to "Description du fournisseur",
                ),
            )
            val tasks: suspend (TerminalCompletionRequest, TerminalCompletionContext) -> List<TerminalGradleTask> = { _, _ ->
                listOf(TerminalGradleTask(":app:run"), TerminalGradleTask(":app:runIde", "Description du modèle"))
            }
            assertLocalizedSource(
                TerminalCompletionSources.gradleTask("gradle-model", tasks),
                TerminalCompletionSources.gradleTask(messages, "gradle-model", tasks),
                request("gradle :app:ru"),
                mapOf(":app:run" to "Tâche :app:run de Gradle", ":app:runIde" to "Description du modèle"),
            )
        }

    @Test
    fun `learning descriptions are cached at engine creation without changing ranking`() =
        runBlocking {
            val learning = TerminalCompletionLearningStore()
            learning.recordCommandResult("unknown-tool status --verbose", true, null, "file:///repo", 1)
            val lookupCounts = HashMap<String, Int>()
            val messages =
                TerminalCompletionMessages { key, _ ->
                    lookupCounts[key] = lookupCounts.getOrDefault(key, 0) + 1
                    "locale:$key"
                }
            val english = TerminalCompletionEngines.fromSources(emptyList(), emptyList(), learning)
            val localized = TerminalCompletionEngines.fromSources(messages, emptyList(), emptyList(), learning)
            val constructionCounts = lookupCounts.toMap()
            val request = request("unknown-tool s")

            val original = english.completions(request).last()
            val translated = localized.completions(request).last()
            assertEquals(setOf("learned", "observed"), translated.map { it.source }.toSet())
            assertEquals(original.map { it.copy(detail = "") }, translated.map { it.copy(detail = "") })
            assertEquals("locale:completion.learnedCommand", translated.single { it.source == "learned" }.detail)
            assertEquals("locale:completion.observedToken", translated.single { it.source == "observed" }.detail)
            localized.completions(request).last()
            assertEquals(constructionCounts, lookupCounts)
        }

    private suspend fun assertLocalizedSource(
        original: TerminalCompletionSource,
        localized: TerminalCompletionSource,
        request: TerminalCompletionRequest,
        details: Map<String, String>,
    ) {
        val context = request.resolveCompletionContext(TerminalCommandSpecs.defaults())
        val english = original.complete(request, context, 256)
        val translated = localized.complete(request, context, 256)
        assertEquals(english.map { it.copy(detail = "") }, translated.map { it.copy(detail = "") })
        assertEquals(details, translated.associate { it.replacementText to it.detail })
    }

    private fun request(command: String): TerminalCompletionRequest =
        TerminalCompletionRequest(command, command.length, workingDirectoryUri = "file:///repo")
}
